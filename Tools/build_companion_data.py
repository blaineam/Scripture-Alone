#!/usr/bin/env python3
"""Builds the small data files the widgets and the Apple Watch bundle instead of the full Bibles.

    python3 Tools/build_companion_data.py            # rebuild everything below
    python3 Tools/build_companion_data.py --check    # rebuild, then assert known values

Reads the databases Tools/build_bibles.py writes (ScriptureAlone/Resources/Bibles/*.sqlite)
and Data/daily-verses.tsv, and writes:

  ScriptureAlone/Shared/DailyVerses.json
      The curated Verse of the Day list with its text in every bundled translation, so the
      widget extensions never open a 15 MB database. Shape:
        {"version": 1, "translations": ["ASV", "BSB", "KJV"],
         "verses": [{"ref": "43003016-43003016", "theme": "...",
                     "text": {"ASV": "...", ...}, "red": {"KJV": [[start, length], ...]}}]}
      Multi-verse passages are joined with single spaces. Red-letter spans (words of Christ)
      are offsets in Unicode scalars into that joined text, like the databases' own spans.

  docs/daily-verses.md
      The list, with themes, for people reviewing the choices.

  ScriptureAloneWatch/Resources/{ASV,BSB,KJV}-Watch.sqlite
      A compact edition of each bundled translation for the watch reader: meta, books,
      per-chapter verse counts and verse text with red letters. It drops the reader's layout
      JSON and the full-text index, which are most of the phone database's size.
      ScriptureAloneCore's BibleStore opens it as is.

      ScriptureAloneCore's WatchEdition writes the SAME schema on the phone, for a translation
      the reader imported, and sends it to the watch. Change one, change both.
"""

import json
import os
import sqlite3
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BIBLES = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles")
LIST = os.path.join(ROOT, "Data", "daily-verses.tsv")
JSON_OUT = os.path.join(ROOT, "ScriptureAlone", "Shared", "DailyVerses.json")
DOC_OUT = os.path.join(ROOT, "docs", "daily-verses.md")
WATCH_DIR = os.path.join(ROOT, "ScriptureAloneWatch", "Resources")
TRANSLATIONS = ["ASV", "BSB", "KJV"]
# Verse counts differ by versification: the KJV keeps verses the others fold or omit.
WATCH_VERSE_COUNTS = {"ASV": 31086, "BSB": 31086, "KJV": 31102}


def watch_db(tid):
    return os.path.join(WATCH_DIR, f"{tid}-Watch.sqlite")

# Same canonical order as ScriptureAloneCore's BookID and Tools/build_bibles.py.
BOOKS = [
    "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI",
    "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER",
    "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC", "NAM", "HAB", "ZEP",
    "HAG", "ZEC", "MAL", "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL",
    "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE",
    "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
]
MAX_VERSES = 3


def parse_list():
    entries = []
    for number, line in enumerate(open(LIST, encoding="utf-8"), start=1):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        ref, _, theme = line.partition("\t")
        code, _, cv = ref.partition(" ")
        if code not in BOOKS or ":" not in cv or not theme.strip():
            sys.exit(f"{LIST}:{number}: expected 'CODE chapter:verse[-verse]<TAB>theme', got {line!r}")
        chapter, _, verses = cv.partition(":")
        first, _, last = verses.partition("-")
        book = BOOKS.index(code) + 1
        start = book * 1_000_000 + int(chapter) * 1_000 + int(first)
        end = book * 1_000_000 + int(chapter) * 1_000 + int(last or first)
        if end < start or end - start + 1 > MAX_VERSES:
            sys.exit(f"{LIST}:{number}: {ref} must be 1–{MAX_VERSES} verses")
        entries.append({"code": code, "label": ref, "start": start, "end": end, "theme": theme.strip()})
    keys = [(e["start"], e["end"]) for e in entries]
    if len(set(keys)) != len(keys):
        sys.exit("daily-verses.tsv lists a passage twice")
    return entries


def passage(db, entry, tid):
    rows = db.execute("SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id",
                      (entry["start"], entry["end"])).fetchall()
    expected = entry["end"] - entry["start"] + 1
    if len(rows) != expected:
        sys.exit(f"{entry['label']} has {len(rows)} of {expected} verses in {tid}")
    text, red = "", []
    for _, verse_text, verse_red in rows:
        # The KJV opens paragraphs with a pilcrow; a lone verse on a widget doesn't want it.
        trim = 2 if verse_text.startswith("¶ ") else 0
        verse_text = verse_text[trim:]
        if text:
            text += " "
        offset = len(text)  # Python indexes code points, the same unit as the databases' spans.
        for start, length in json.loads(verse_red) if verse_red else []:
            start, length = max(0, start - trim), length - max(0, trim - start)
            if length > 0:
                red.append([offset + start, length])
        text += verse_text
    return text, red


def build_json(entries):
    dbs = {tid: sqlite3.connect(os.path.join(BIBLES, f"{tid}.sqlite")) for tid in TRANSLATIONS}
    verses = []
    for entry in entries:
        item = {"ref": f"{entry['start']}-{entry['end']}", "theme": entry["theme"], "text": {}}
        reds = {}
        for tid, db in dbs.items():
            text, red = passage(db, entry, tid)
            item["text"][tid] = text
            if red:
                reds[tid] = red
        if reds:
            item["red"] = reds
        verses.append(item)
    catalog = {"version": 1, "translations": TRANSLATIONS, "verses": verses}
    os.makedirs(os.path.dirname(JSON_OUT), exist_ok=True)
    with open(JSON_OUT, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    print(f"daily verses: {len(verses)} passages -> {os.path.relpath(JSON_OUT, ROOT)} "
          f"({os.path.getsize(JSON_OUT) / 1e3:.0f} KB)")
    return catalog


GROUPS = [
    ("Law", "GEN", "DEU"), ("History", "JOS", "EST"), ("Wisdom & Poetry", "JOB", "SNG"),
    ("Prophets", "ISA", "MAL"), ("Gospels & Acts", "MAT", "ACT"), ("Paul’s Letters", "ROM", "PHM"),
    ("General Letters & Revelation", "HEB", "REV"),
]


def build_doc(entries):
    lines = [
        "# Verse of the Day",
        "",
        "The Verse of the Day widget, the Apple Watch app and its complications draw from one",
        f"hand-curated list of **{len(entries)} passages** — one for every day of the year. This page",
        "lists every choice so it can be reviewed; the source of truth is",
        "[`Data/daily-verses.tsv`](../Data/daily-verses.tsv), and",
        "`python3 Tools/build_companion_data.py` regenerates this page along with the text the",
        "widgets bundle.",
        "",
        "## How the passages were chosen",
        "",
        "- **Beloved and well known.** Verses Christians have long memorized, sung and quoted:",
        "  the Shema, the Aaronic blessing, Psalm 23, Isaiah 53, John 3:16, Romans 8, the",
        "  fruit of the Spirit, the Great Commission. Topical memory plans and verse-of-the-day",
        "  traditions overlap heavily on these passages, and this list leans on that common core",
        "  rather than on any one publisher’s plan.",
        "- **The whole canon.** Every section of the Bible is represented — Law, History, Wisdom,",
        "  the Major and Minor Prophets, the Gospels, Acts, Paul’s letters, the General Letters",
        "  and Revelation — not only the Psalms and the New Testament.",
        "- **Readable on a wrist.** Each passage is one to three verses that make sense on their",
        "  own, so a small widget or a watch complication is not a fragment of an argument.",
        "- **Translation-neutral.** Every passage exists in all bundled translations (ASV, BSB,",
        "  KJV); the build fails otherwise. Verses the ASV relegates to footnotes are avoided.",
        "",
        "## How a day picks its passage",
        "",
        "The day’s passage depends only on the local calendar date, so every device shows the same",
        "verse on the same day, offline. `DailyVerseCatalog` counts days since 1 January 2000 and",
        "steps through the list by a fixed stride that is coprime with its length, so each passage",
        "appears exactly once per cycle and consecutive days jump across the canon instead of",
        "walking through Genesis in January. Widgets change at local midnight.",
        "",
        "## The list",
        "",
    ]
    for title, first, last in GROUPS:
        lo, hi = BOOKS.index(first), BOOKS.index(last)
        group = [e for e in entries if lo <= BOOKS.index(e["code"]) <= hi]
        lines += [f"### {title} ({len(group)})", "", "| Passage | Theme |", "|---|---|"]
        lines += [f"| {display(e)} | {e['theme']} |" for e in group]
        lines.append("")
    os.makedirs(os.path.dirname(DOC_OUT), exist_ok=True)
    with open(DOC_OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"daily verses: list -> {os.path.relpath(DOC_OUT, ROOT)}")


NAMES = {}


def display(entry):
    name = NAMES[entry["code"]]
    chapter = entry["start"] // 1_000 % 1_000
    v1, v2 = entry["start"] % 1_000, entry["end"] % 1_000
    return f"{name} {chapter}:{v1}" + (f"–{v2}" if v2 != v1 else "")


def build_watch_dbs():
    for tid in TRANSLATIONS:
        build_watch_db(tid)


def build_watch_db(tid):
    WATCH_DB = watch_db(tid)
    source = sqlite3.connect(os.path.join(BIBLES, f"{tid}.sqlite"))
    os.makedirs(os.path.dirname(WATCH_DB), exist_ok=True)
    fd, tmp = tempfile.mkstemp(suffix=".sqlite", dir=os.path.dirname(WATCH_DB))
    os.close(fd)
    db = sqlite3.connect(tmp)
    db.executescript(
        """
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE books (book INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL, chapters INTEGER NOT NULL);
        CREATE TABLE chapters (book INTEGER NOT NULL, chapter INTEGER NOT NULL, verses INTEGER NOT NULL,
                               PRIMARY KEY (book, chapter)) WITHOUT ROWID;
        CREATE TABLE verses (id INTEGER PRIMARY KEY, text TEXT NOT NULL, red TEXT);
        """
    )
    db.executemany("INSERT INTO meta VALUES (?, ?)", source.execute("SELECT key, value FROM meta"))
    db.execute("INSERT OR REPLACE INTO meta VALUES ('edition', 'watch')")
    db.executemany("INSERT INTO books VALUES (?, ?, ?, ?)", source.execute("SELECT book, code, name, chapters FROM books"))
    db.executemany("INSERT INTO chapters VALUES (?, ?, ?)", source.execute("SELECT book, chapter, verses FROM chapters"))
    db.executemany("INSERT INTO verses VALUES (?, ?, ?)", source.execute("SELECT id, text, red FROM verses"))
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.chmod(tmp, 0o644)
    os.replace(tmp, WATCH_DB)
    full = os.path.getsize(os.path.join(BIBLES, f"{tid}.sqlite"))
    print(f"watch: {tid} {full / 1e6:.1f} MB -> {os.path.relpath(WATCH_DB, ROOT)} "
          f"({os.path.getsize(WATCH_DB) / 1e6:.1f} MB)")


def check(catalog):
    verses = {v["ref"]: v for v in catalog["verses"]}
    assert len(verses) == 365, len(verses)
    john = verses["43003016-43003016"]
    assert john["text"]["KJV"].startswith("For God so loved the world"), john
    assert john["text"]["BSB"].startswith("For God so loved the world"), john
    ps = verses["19023001-19023001"]
    assert ps["text"]["BSB"] == "The LORD is my shepherd; I shall not want.", ps
    prov = verses["20003005-20003006"]
    assert prov["text"]["KJV"].startswith("Trust in the LORD with all thine heart") and " In all thy ways" in prov["text"]["KJV"], prov
    way = verses["43014006-43014006"]
    for tid in TRANSLATIONS:
        start, length = way["red"][tid][0]
        spoken = way["text"][tid][start:start + length]
        assert "I am the way" in spoken, (tid, spoken)
    # Red spans in a multi-verse passage are shifted into the joined text.
    come = verses["43011025-43011026"]
    for start, length in come["red"]["KJV"]:
        assert 0 <= start and start + length <= len(come["text"]["KJV"]), come
    for tid in TRANSLATIONS:
        watch = sqlite3.connect(watch_db(tid))
        count = watch.execute("SELECT count(*) FROM verses").fetchone()[0]
        assert count == WATCH_VERSE_COUNTS[tid], (tid, count)
        assert watch.execute("SELECT text FROM verses WHERE id = 43011035").fetchone()[0] == "Jesus wept.", tid
        assert watch.execute("SELECT sum(chapters) FROM books").fetchone()[0] == 1189, tid
        assert watch.execute("SELECT value FROM meta WHERE key = 'edition'").fetchone()[0] == "watch", tid
    print("check: ok")


def main():
    names = sqlite3.connect(os.path.join(BIBLES, "BSB.sqlite")).execute("SELECT code, name FROM books")
    NAMES.update(dict(names))
    entries = parse_list()
    catalog = build_json(entries)
    build_doc(entries)
    build_watch_dbs()
    if "--check" in sys.argv:
        check(catalog)


if __name__ == "__main__":
    main()
