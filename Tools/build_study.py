#!/usr/bin/env python3
"""Compile Study-mode data (cross references and commentary) into the SQLite database the app bundles.

    python3 Tools/build_study.py --fetch     # (re)download commentary snapshots into Data/source/study/
    python3 Tools/build_study.py             # builds ScriptureAlone/Resources/Study/Study.sqlite
    python3 Tools/build_study.py --check     # builds, then asserts known entries

Sources and licenses are documented in docs/study-sources.md and stored in the `sources` table,
which the app's "About Study Resources" screen reads.

Database:
  sources     one row per dataset: id, kind, name, author, license, attribution...
  crossrefs   from_key INTEGER PRIMARY KEY, refs BLOB
              refs packs every target of one verse, strongest first, as little-endian
              (to_start uint32, to_end uint32, votes uint16) records — 10 bytes each.
  commentary       source, start_key, end_key, text_id, part — one row per comment
  commentary_text  id, body — one row per source and chapter: that chapter's comments joined by
                   U+0000 and compressed as raw DEFLATE; `part` indexes the pieces. Compressing a
                   chapter at a time is ~15% smaller than a comment at a time.
  A chapter introduction is stored with start_key = end_key = the chapter's verse-0 key.

Verse keys are book * 1_000_000 + chapter * 1_000 + verse, the same key the Bible databases use.
"""

import gzip
import hashlib
import json
import os
import re
import sqlite3
import struct
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_DIR = os.path.join(ROOT, "Data", "source", "study")
OUTPUT_DIR = os.path.join(ROOT, "ScriptureAlone", "Resources", "Study")
OUTPUT = os.path.join(OUTPUT_DIR, "Study.sqlite")
# Cross references and the source list, split out of Study.sqlite so they can ship inside the app
# while the commentary (38 MB of the 42) is an on-demand Background Assets pack. StudyStore opens
# either file: it reads `sources` at init and touches `crossrefs` or `commentary` only on query.
CROSSREFS_OUTPUT = os.path.join(OUTPUT_DIR, "CrossReferences.sqlite")
BIBLE_FOR_COUNTS = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles", "KJV.sqlite")

BOOKS = [
    "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI",
    "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER",
    "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC", "NAM", "HAB", "ZEP",
    "HAG", "ZEC", "MAL", "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL",
    "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE",
    "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
]

# OpenBible.info uses OSIS book names.
OSIS = [
    "Gen", "Exod", "Lev", "Num", "Deut", "Josh", "Judg", "Ruth", "1Sam", "2Sam", "1Kgs", "2Kgs",
    "1Chr", "2Chr", "Ezra", "Neh", "Esth", "Job", "Ps", "Prov", "Eccl", "Song", "Isa", "Jer",
    "Lam", "Ezek", "Dan", "Hos", "Joel", "Amos", "Obad", "Jonah", "Mic", "Nah", "Hab", "Zeph",
    "Hag", "Zech", "Mal", "Matt", "Mark", "Luke", "John", "Acts", "Rom", "1Cor", "2Cor", "Gal",
    "Eph", "Phil", "Col", "1Thess", "2Thess", "1Tim", "2Tim", "Titus", "Phlm", "Heb", "Jas", "1Pet",
    "2Pet", "1John", "2John", "3John", "Jude", "Rev",
]

CROSS_REFERENCES = {
    "id": "openbible",
    "kind": "crossrefs",
    "file": "openbible-cross-references.zip",
    "sha256": "30379be544785f4c2cdf8eba0d83d10dedc04a6903b5dcd0d91d670e90619d6d",
    "name": "OpenBible.info Cross References",
    "short_name": "OpenBible.info",
    "author": "OpenBible.info, drawing chiefly on the public-domain Treasury of Scripture Knowledge",
    "year": "2026-09-14 snapshot",
    "license": "CC BY 4.0",
    "license_url": "https://creativecommons.org/licenses/by/4.0/",
    "url": "https://www.openbible.info/labs/cross-references/",
    "attribution": "Cross references from OpenBible.info (www.openbible.info), licensed CC BY 4.0. "
                   "Ranked by reader votes; references with no net votes are omitted.",
}

# Commentaries: public-domain works, taken from the Free Use Bible API (AO Lab), which publishes
# them under the Creative Commons Public Domain Mark 1.0 with "no copyright restrictions whatsoever".
# Matthew Henry is deliberately absent: that API's copy truncates 36 long comments at 32,767 bytes
# (see docs/study-sources.md).
HELLOAO = "https://bible.helloao.org/api/c"
COMMENTARIES = [
    {
        "id": "calvin",
        "kind": "commentary",
        "helloao": "john-calvin",
        "sha256": "92d2cba35e9b0c8eb070e3ed66d207548f0f7607dbab5fbb2a8a802cd750e0fa",
        "name": "Calvin’s Commentaries",
        "short_name": "Calvin",
        "author": "John Calvin (1509–1564); Calvin Translation Society English edition",
        "year": "1540–1564 (English 1843–1855)",
        "license": "Public domain",
        "license_url": "https://creativecommons.org/publicdomain/mark/1.0/",
        "url": "https://bible.helloao.org/api/c/john-calvin/books.json",
        "attribution": "John Calvin, Commentaries, tr. Calvin Translation Society (Edinburgh, 1843–1855). Public domain. "
                       "Text from the Free Use Bible API by AO Lab (bible.helloao.org), Public Domain Mark 1.0.",
    },
    {
        "id": "gill",
        "kind": "commentary",
        "helloao": "john-gill",
        "sha256": "bc192641caa9d63333562abf4fd38081466a451f963e784ef8922d8cc2b47aef",
        "name": "John Gill’s Exposition of the Bible",
        "short_name": "Gill",
        "author": "John Gill (1697–1771)",
        "year": "1746–1766",
        "license": "Public domain",
        "license_url": "https://creativecommons.org/publicdomain/mark/1.0/",
        "url": "https://bible.helloao.org/api/c/john-gill/books.json",
        "attribution": "John Gill, An Exposition of the Old and New Testament (1746–1766). Public domain. "
                       "Text from the Free Use Bible API by AO Lab (bible.helloao.org), Public Domain Mark 1.0.",
    },
    {
        "id": "jfb",
        "kind": "commentary",
        "helloao": "jamieson-fausset-brown",
        "sha256": "af32175dda3f4d7193ae5bf33b636f4e5ba0ed625c736831abd77426eda7a08a",
        "name": "Jamieson, Fausset & Brown Commentary",
        "short_name": "JFB",
        "author": "Robert Jamieson, A. R. Fausset and David Brown",
        "year": "1871",
        "license": "Public domain",
        "license_url": "https://creativecommons.org/publicdomain/mark/1.0/",
        "url": "https://bible.helloao.org/api/c/jamieson-fausset-brown/books.json",
        "attribution": "Robert Jamieson, A. R. Fausset and David Brown, Commentary Critical and Explanatory on the Whole Bible (1871). "
                       "Public domain. Text from the Free Use Bible API by AO Lab (bible.helloao.org), Public Domain Mark 1.0.",
    },
]


def snapshot_path(c):
    return os.path.join(SOURCE_DIR, f"{c['helloao']}.json.gz")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# -- fetching ------------------------------------------------------------------

def get_json(url, attempts=5):
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "ScriptureAlone-build_study/1.0"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(1 + attempt * 2)
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            time.sleep(1 + attempt * 2)
    raise SystemExit(f"fetch failed: {url}")


def fetch(c):
    """Download every chapter of one commentary into a single gzipped snapshot:
    {"books": [...books.json...], "chapters": {"JHN 3": {...chapter...}}}"""
    books = get_json(f"{HELLOAO}/{c['helloao']}/books.json")["books"]
    chapters = {}
    for book in books:
        for chapter in range(book["firstChapterNumber"], book["lastChapterNumber"] + 1):
            data = get_json(f"{HELLOAO}/{c['helloao']}/{book['id']}/{chapter}.json")
            if data:
                chapters[f"{book['id']} {chapter}"] = data["chapter"]
        print(f"  {c['helloao']} {book['id']}: {len(chapters)} chapters so far", flush=True)
    os.makedirs(SOURCE_DIR, exist_ok=True)
    payload = json.dumps({"source": f"{HELLOAO}/{c['helloao']}", "books": books, "chapters": chapters},
                         ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    with open(snapshot_path(c), "wb") as f:
        # mtime=0 keeps the snapshot byte-for-byte reproducible.
        with gzip.GzipFile(fileobj=f, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write(payload)
    print(f"{c['helloao']}: {len(chapters)} chapters -> {os.path.relpath(snapshot_path(c), ROOT)} "
          f"({os.path.getsize(snapshot_path(c)) / 1e6:.1f} MB, sha256 {sha256(snapshot_path(c))})")


# -- cross references ----------------------------------------------------------

OSIS_INDEX = {name: i + 1 for i, name in enumerate(OSIS)}


def osis_key(ref):
    book, chapter, verse = ref.split(".")
    return OSIS_INDEX[book] * 1_000_000 + int(chapter) * 1_000 + int(verse)


def load_cross_references():
    path = os.path.join(SOURCE_DIR, CROSS_REFERENCES["file"])
    digest = sha256(path)
    assert digest == CROSS_REFERENCES["sha256"], f"{path}: sha256 {digest} does not match the recorded snapshot"
    by_verse = {}
    dropped = 0
    with zipfile.ZipFile(path) as z:
        lines = z.read("cross_references.txt").decode("utf-8").splitlines()
    for line in lines[1:]:
        if not line.strip():
            continue
        source, target, votes = line.split("\t")
        votes = int(votes)
        if votes < 1:
            dropped += 1
            continue
        start, _, end = target.partition("-")
        to_start = osis_key(start)
        to_end = osis_key(end) if end else to_start
        if to_end < to_start:
            to_start, to_end = to_end, to_start
        targets = by_verse.setdefault(osis_key(source), {})
        # Keep the strongest vote when the same target appears twice.
        targets[(to_start, to_end)] = max(votes, targets.get((to_start, to_end), 0))
    return by_verse, dropped


# -- commentary ----------------------------------------------------------------

def verse_counts():
    db = sqlite3.connect(BIBLE_FOR_COUNTS)
    counts = {(b, c): v for b, c, v in db.execute("SELECT book, chapter, verses FROM chapters")}
    db.close()
    return counts


def flatten(content):
    """helloao content arrays hold strings (and occasionally small objects); join them into text."""
    parts = []
    for item in content if isinstance(content, list) else [content]:
        if isinstance(item, str):
            parts.append(item)
        elif isinstance(item, dict):
            for field in ("text", "content", "heading"):
                if field in item:
                    parts.append(flatten(item[field]))
                    break
    return "".join(parts)


FOOTNOTE_CALLER = re.compile(r"\s?\[\d{1,4}\]")
RANGE_LINE = re.compile(r"^\s*(?:[1-3] )?[A-Z][A-Za-z ]+? (\d+):(\d+)(?:\s*[-–]\s*(?:(\d+):)?(\d+))?\s*$")


# e-Sword style abbreviations in the commentary text ("Pe1 2:25", "Mar 4:3") become ones the
# app's reference detector links ("1Pe 2:25", "Mark 4:3").
NUMBERED = {"Sa": "Sa", "Kg": "Ki", "Ch": "Ch", "Co": "Co", "Th": "Th", "Ti": "Ti", "Pe": "Pe", "Jo": "Jo"}
NUMBERED_REF = re.compile(r"\b(Sa|Kg|Ch|Co|Th|Ti|Pe|Jo)([1-3])(?= \d+:\d)")
RENAMED = {"Mar": "Mark", "Joe": "Joel", "Jon": "Jonah", "Jde": "Jude", "Sol": "Song", "Eze": "Ezek", "Oba": "Obad"}
RENAMED_REF = re.compile(r"\b(" + "|".join(RENAMED) + r")(?= \d+:\d)")


def normalize_references(text):
    text = NUMBERED_REF.sub(lambda m: f"{m.group(2)}{NUMBERED[m.group(1)]}", text)
    return RENAMED_REF.sub(lambda m: RENAMED[m.group(1)], text)


# JFB carries UTF-8 that was once read as Windows-1252 ("CÃ¦sarea", "Â£"), and æ additionally
# mangled into an entity ("CÃ&brvbrsarea"). Undo both.
MOJIBAKE = re.compile("(?:â€|[ÃÂ])[-¿Œ-ƒˆ-˜–-™]")


def fix_mojibake(text):
    text = text.replace("Ã&brvbr", "æ")

    def repair(m):
        try:
            return m.group(0).encode("cp1252").decode("utf-8")
        except UnicodeError:
            return m.group(0)
    return MOJIBAKE.sub(repair, text)


def clean(text, strip_callers=False):
    text = normalize_references(fix_mojibake(text.replace("\r\n", "\n").replace(" ", " ")))
    if strip_callers:
        text = FOOTNOTE_CALLER.sub("", text)
    paragraphs = [re.sub(r"[ \t]+", " ", p).strip() for p in re.split(r"\n\s*\n|\n", text)]
    return "\n\n".join(p for p in paragraphs if p)


def compress(text):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)  # raw DEFLATE: what Apple's COMPRESSION_ZLIB reads
    return c.compress(text.encode("utf-8")) + c.flush()


def load_commentary(c, counts):
    path = snapshot_path(c)
    if not os.path.exists(path):
        raise SystemExit(f"{path} is missing; run: python3 Tools/build_study.py --fetch {c['id']}")
    digest = sha256(path)
    if digest != c["sha256"]:
        # A re-fetch picked up upstream corrections: review the diff, then record the new checksum.
        print(f"warning: {os.path.basename(path)} sha256 {digest} differs from the recorded {c['sha256']}")
    with gzip.open(path, "rt", encoding="utf-8") as f:
        snapshot = json.load(f)
    rows = []  # (start_key, end_key, text)
    for name, chapter in snapshot["chapters"].items():
        code, number = name.split()
        book = BOOKS.index(code) + 1
        number = int(number)
        last = counts.get((book, number))
        if last is None:
            continue
        base = book * 1_000_000 + number * 1_000
        intro = clean(flatten(chapter.get("introduction") or ""))
        if intro:
            rows.append((base, base, intro))
        entries = []
        for item in chapter.get("content", []):
            if item.get("type") != "verse" or not item.get("number"):
                continue
            text = flatten(item.get("content", []))
            if text.strip():
                entries.append((int(item["number"]), text))
        entries.sort(key=lambda e: e[0])
        for i, (start, text) in enumerate(entries):
            end = (entries[i + 1][0] - 1) if i + 1 < len(entries) else last
            end = max(start, min(end, last))
            if c["id"] == "calvin":
                # Calvin's sections open with their own range ("John 3:13-18") and the verses quoted in full.
                first, _, rest = text.lstrip().partition("\n")
                m = RANGE_LINE.match(first)
                if m and int(m.group(1)) == number and int(m.group(2)) == start:
                    text = rest
                    if m.group(4) and not m.group(3):
                        end = max(start, min(int(m.group(4)), last))
            body = clean(text, strip_callers=c["id"] == "calvin")
            if body:
                rows.append((base + start, base + end, body))
    return rows


# -- build ---------------------------------------------------------------------

def build():
    counts = verse_counts()
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    fd, tmp = tempfile.mkstemp(suffix=".sqlite", dir=OUTPUT_DIR)
    os.close(fd)
    db = sqlite3.connect(tmp)
    db.executescript(
        """
        PRAGMA page_size = 4096;
        CREATE TABLE sources (id TEXT PRIMARY KEY, kind TEXT NOT NULL, sort INTEGER NOT NULL, name TEXT NOT NULL,
                              short_name TEXT NOT NULL, author TEXT NOT NULL, year TEXT NOT NULL, license TEXT NOT NULL,
                              license_url TEXT NOT NULL, url TEXT NOT NULL, attribution TEXT NOT NULL);
        CREATE TABLE crossrefs (from_key INTEGER PRIMARY KEY, refs BLOB NOT NULL);
        CREATE TABLE commentary (source TEXT NOT NULL, start_key INTEGER NOT NULL, end_key INTEGER NOT NULL,
                                 text_id INTEGER NOT NULL, part INTEGER NOT NULL,
                                 PRIMARY KEY (source, start_key, end_key)) WITHOUT ROWID;
        CREATE TABLE commentary_text (id INTEGER PRIMARY KEY, body BLOB NOT NULL);
        """
    )
    for sort, s in enumerate([CROSS_REFERENCES] + COMMENTARIES):
        db.execute("INSERT INTO sources VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                   (s["id"], s["kind"], sort, s["name"], s["short_name"], s["author"], s["year"], s["license"],
                    s["license_url"], s["url"], s["attribution"]))

    by_verse, dropped = load_cross_references()
    total = 0
    for from_key in sorted(by_verse):
        targets = sorted(by_verse[from_key].items(), key=lambda kv: (-kv[1], kv[0]))
        blob = b"".join(struct.pack("<IIH", s, e, min(v, 0xFFFF)) for (s, e), v in targets)
        db.execute("INSERT INTO crossrefs VALUES (?, ?)", (from_key, blob))
        total += len(targets)
    print(f"cross references: {total} links from {len(by_verse)} verses ({dropped} without net votes dropped)")

    text_id = 0
    for c in COMMENTARIES:
        chapters = {}
        seen = set()
        raw = packed = 0
        for start, end, text in load_commentary(c, counts):
            if (start, end) in seen:
                continue
            seen.add((start, end))
            assert "\0" not in text
            chapters.setdefault(start // 1000, []).append((start, end, text))
        for chapter in sorted(chapters):
            text_id += 1
            entries = sorted(chapters[chapter])
            joined = "\0".join(text for _, _, text in entries)
            body = compress(joined)
            raw += len(joined.encode("utf-8"))
            packed += len(body)
            db.execute("INSERT INTO commentary_text VALUES (?, ?)", (text_id, body))
            for part, (start, end, _) in enumerate(entries):
                db.execute("INSERT INTO commentary VALUES (?, ?, ?, ?, ?)", (c["id"], start, end, text_id, part))
        print(f"{c['id']}: {len(seen)} entries in {len(chapters)} chapters, "
              f"{raw / 1e6:.1f} MB of text -> {packed / 1e6:.1f} MB compressed")

    db.commit()
    db.execute("VACUUM")
    db.close()
    os.chmod(tmp, 0o644)  # mkstemp creates 0600
    os.replace(tmp, OUTPUT)
    print(f"-> {os.path.relpath(OUTPUT, ROOT)} ({os.path.getsize(OUTPUT) / 1e6:.1f} MB)")


def split_cross_references():
    """Writes CrossReferences.sqlite from the existing Study.sqlite: `sources` and `crossrefs` only.

    Derived rather than built from source, so it can be regenerated without re-fetching anything and
    always matches the Study.sqlite beside it byte for byte in the rows it carries.
    """
    fd, tmp = tempfile.mkstemp(suffix=".sqlite", dir=OUTPUT_DIR)
    os.close(fd)
    os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript(
        """
        PRAGMA page_size = 4096;
        CREATE TABLE sources (id TEXT PRIMARY KEY, kind TEXT NOT NULL, sort INTEGER NOT NULL, name TEXT NOT NULL,
                              short_name TEXT NOT NULL, author TEXT NOT NULL, year TEXT NOT NULL, license TEXT NOT NULL,
                              license_url TEXT NOT NULL, url TEXT NOT NULL, attribution TEXT NOT NULL);
        CREATE TABLE crossrefs (from_key INTEGER PRIMARY KEY, refs BLOB NOT NULL);
        """
    )
    db.execute("ATTACH DATABASE ? AS study", (OUTPUT,))
    db.execute("INSERT INTO sources SELECT * FROM study.sources")
    db.execute("INSERT INTO crossrefs SELECT * FROM study.crossrefs")
    db.commit()
    db.execute("DETACH DATABASE study")
    db.execute("VACUUM")
    db.close()
    os.chmod(tmp, 0o644)
    os.replace(tmp, CROSSREFS_OUTPUT)
    print(f"-> {os.path.relpath(CROSSREFS_OUTPUT, ROOT)} ({os.path.getsize(CROSSREFS_OUTPUT) / 1e6:.1f} MB)")


# -- check ---------------------------------------------------------------------

def key(code, chapter, verse):
    return (BOOKS.index(code) + 1) * 1_000_000 + chapter * 1_000 + verse


def cross_refs(db, k):
    row = db.execute("SELECT refs FROM crossrefs WHERE from_key = ?", (k,)).fetchone()
    if not row:
        return []
    blob = row[0]
    return [struct.unpack_from("<IIH", blob, i) for i in range(0, len(blob), 10)]


def entry_text(body, part):
    return zlib.decompress(body, -15).decode("utf-8").split("\0")[part]


def commentary(db, source, k):
    rows = db.execute("SELECT start_key, end_key, part, body FROM commentary JOIN commentary_text ON id = text_id "
                      "WHERE source = ? AND start_key <= ? AND end_key >= ? AND start_key % 1000 != 0 ORDER BY start_key",
                      (source, k, k)).fetchall()
    return [(s, e, entry_text(b, p)) for s, e, p, b in rows]


def introduction(db, source, k):
    row = db.execute("SELECT part, body FROM commentary JOIN commentary_text ON id = text_id "
                     "WHERE source = ? AND start_key = ? AND end_key = ?", (source, k, k)).fetchone()
    assert row, f"{source}: no introduction at {k}"
    return entry_text(row[1], row[0])


def check():
    db = sqlite3.connect(OUTPUT)
    kinds = dict(db.execute("SELECT id, kind FROM sources"))
    assert kinds == {"openbible": "crossrefs", "calvin": "commentary", "gill": "commentary", "jfb": "commentary"}, kinds
    for lic, attribution in db.execute("SELECT license, attribution FROM sources"):
        assert lic and attribution, (lic, attribution)

    # John 3:16 -> Romans 5:8 is the strongest link, and 1 John 4:9-10 is among the top.
    refs = cross_refs(db, key("JHN", 3, 16))
    assert len(refs) >= 15, len(refs)
    assert refs[0][:2] == (key("ROM", 5, 8), key("ROM", 5, 8)), refs[0]
    assert (key("1JN", 4, 9), key("1JN", 4, 10)) in [r[:2] for r in refs[:5]], refs[:5]
    votes = [r[2] for r in refs]
    assert votes == sorted(votes, reverse=True), "cross references must be strongest first"
    targets = {r[0] for r in cross_refs(db, key("GEN", 1, 1))}
    assert key("JHN", 1, 1) in targets and key("HEB", 11, 3) in targets, "Genesis 1:1"
    # Every key decodes to a real verse position.
    bad = 0
    for (blob,) in db.execute("SELECT refs FROM crossrefs"):
        for i in range(0, len(blob), 10):
            s, e, _ = struct.unpack_from("<IIH", blob, i)
            if not (1_000_000 <= s <= e < 67_000_000) or s % 1000 == 0:
                bad += 1
    assert bad == 0, f"{bad} malformed cross-reference keys"

    # Psalm 23:1 has commentary from Calvin and Gill; JFB treats it in the psalm's introduction.
    for source in ("calvin", "gill"):
        entries = commentary(db, source, key("PSA", 23, 1))
        assert entries, f"{source}: no commentary on Psalm 23:1"
        assert any("shepherd" in text.lower() for _, _, text in entries), (source, entries[0][2][:200])
    assert "shepherd" in introduction(db, "jfb", key("PSA", 23, 0)).lower()
    assert commentary(db, "jfb", key("PSA", 23, 2)), "jfb: Psalm 23:2"
    # John 3:16: Gill and JFB comment verse by verse, Calvin on 3:13-18.
    gill = commentary(db, "gill", key("JHN", 3, 16))
    assert gill and gill[0][:2] == (key("JHN", 3, 16),) * 2, gill and gill[0][:2]
    assert gill[0][2].startswith("For God so loved the world"), gill[0][2][:100]
    jfb = commentary(db, "jfb", key("JHN", 3, 16))
    assert jfb and jfb[0][2].startswith("For God so loved"), jfb and jfb[0][2][:100]
    calvin = commentary(db, "calvin", key("JHN", 3, 16))
    assert calvin and calvin[0][:2] == (key("JHN", 3, 13), key("JHN", 3, 18)), calvin and calvin[0][:2]
    assert "John 3:13" not in calvin[0][2][:40], calvin[0][2][:80]
    assert "[61]" not in calvin[0][2], "Calvin footnote callers leak"
    # Chapter introductions sit at verse 0.
    assert "Nicodemus" in introduction(db, "jfb", key("JHN", 3, 0))
    # e-Sword abbreviations are normalized so references link ("Pe1 2:25" -> "1Pe 2:25").
    jfb_intro = introduction(db, "jfb", key("PSA", 23, 0))
    assert "1Pe 2:25" in jfb_intro and "Pe1" not in jfb_intro, jfb_intro[:400]
    # Ranges never run backwards or leave their chapter.
    broken = db.execute("SELECT count(*) FROM commentary WHERE end_key < start_key OR end_key / 1000 != start_key / 1000").fetchone()[0]
    assert broken == 0, f"{broken} commentary ranges are malformed"
    # No markup leaks into the text.
    # and no comment looks cut off at a spreadsheet's 32,767-character cell limit (what sank Henry).
    leaks = clipped = 0
    for (body,) in db.execute("SELECT body FROM commentary_text"):
        for text in zlib.decompress(body, -15).decode("utf-8").split("\0"):
            # Calvin's Greek is in an old ASCII transliteration ("ojrqwv prosene>nkHus") that uses
            # < and >, and Gill writes "&c;" for et cetera, so look for real tags and entities only.
            if (re.search(r"</?(?:p|br|i|b|em|strong|span|div|a|sup|sub|font|h[1-6])\b[^>]*>", text)
                    or re.search(r"&(?!c;)[a-zA-Z]{2,8};|&#\d+;|Ã|Â|â€", text) or not text.strip()):
                leaks += 1
            if 32_000 <= len(text.encode("utf-8")) <= 32_767 or 32_000 <= len(text) <= 32_767:
                clipped += 1
    assert leaks == 0, f"{leaks} commentary entries contain markup"
    assert clipped == 0, f"{clipped} commentary entries look truncated at 32,767"
    print("check: ok")


def main():
    if "--fetch" in sys.argv:
        only = [a for a in sys.argv[1:] if not a.startswith("--")]
        for c in COMMENTARIES:
            if not only or c["helloao"] in only or c["id"] in only:
                fetch(c)
        return
    if "--cross-references" in sys.argv:
        split_cross_references()
        return
    build()
    split_cross_references()
    if "--check" in sys.argv:
        check()


if __name__ == "__main__":
    main()


