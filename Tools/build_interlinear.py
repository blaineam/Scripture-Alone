#!/usr/bin/env python3
"""Compile the offline original-language data (interlinear + Strong's lexicon) the app bundles.

    python3 Tools/build_interlinear.py             # fetch (once, cached) + build
    python3 Tools/build_interlinear.py --fetch     # force a re-download of the sources
    python3 Tools/build_interlinear.py --verify    # build, then assert known facts
    python3 Tools/build_interlinear.py --verify-only   # verify the database already on disk

Output (bundled by the app):
  ScriptureAlone/Resources/Study/Interlinear.sqlite

Inputs, both cached under Data/cache/interlinear/ (git-ignored) and verified by SHA-256:
  bereanbible.com/bsb_tables.tsv          public domain; the BSB translation tables — every
                                          Hebrew/Greek word with transliteration, parsing,
                                          Strong's number, the BSB English that renders it,
                                          and both word orders.
  STEPBible TBESH + TBESG                 CC BY 4.0; abridged BDB / Abbott-Smith keyed to
                                          extended Strong's, pinned to a commit.
Sources, licences and the reasoning behind this choice are in docs/original-language-data.md.

Verse keys are book * 1_000_000 + chapter * 1_000 + verse, as everywhere else in the project.
Chapter keys are book * 1_000 + chapter.

Database
--------
  meta         key/value, including the two attribution strings the licences ask for.
  chapters     chapter_key INTEGER PRIMARY KEY, words BLOB
               Every word of one chapter, joined and raw-DEFLATE compressed. One chapter at a
               time is the same storage pattern build_study.py uses for commentary_text. A real
               SQL row per word measures 25.5 MB for the same content; this is 8.5 MB.
  verses       verse_key INTEGER PRIMARY KEY, chapter_key, first, count, aligned
               `first`/`count` slice this verse's word records out of its chapter blob.
               `aligned` is 1 when the records reproduce BSB.sqlite's verse text exactly.
  parsings     id INTEGER PRIMARY KEY, code, description   — the 3,800-odd parsing codes, interned.
  lexicon      strongs TEXT PRIMARY KEY, entry_id, part    — one row per Strong's number.
  lexicon_text id INTEGER PRIMARY KEY, body BLOB           — a bucket of entries, compressed.

  Indexes: the two primary keys are the two indexes the app needs — `verses` keyed by verse for
  "all the words of this verse, in reading order", `lexicon` keyed by Strong's number for
  "this number's entry". Nothing else is indexed, which is most of why this file is small.

Word records
------------
A chapter blob decompresses (raw DEFLATE, what Apple's COMPRESSION_ZLIB reads) to UTF-8 text:
one record per line (U+000A), ten fields per record separated by tabs, in BSB reading order.
A verse's words are lines [first, first + count). A word's position in the verse is its index
in that slice. The format string is also stored in meta['word_record'].

  0 english     the BSB words this word renders — but ONLY when `start` is -1. When `start` is
                0 or more the English is BSB.sqlite's own verse text at [start, start+length),
                and this field is left empty rather than storing those 3.3 MB twice. The field
                is also legitimately empty when the table renders the word with no English at
                all (its "-" and ". . ." rows) or when the row is only a footnote caller.
  1 original    the Hebrew/Aramaic/Greek word (WLC for the OT, the Nestle base for the NT).
  2 translit    transliteration.
  3 parsing     id into `parsings`; 0 when the row carries no parsing.
  4 strongs     zero-padded key into `lexicon` ("H0430", "G3056"); empty when untagged.
  5 lang        H Hebrew, A Aramaic, G Greek.
  6 order       1-based position of this word in ORIGINAL-language order within the verse.
                (The record's own index is its position in English order.)
  7 start       UTF-16 offset of this word's English inside BSB.sqlite's text for this verse,
                or -1 when there is none to point at.
  8 length      length of that slice in UTF-16 code units, or -1.
  9 flags       bit 0: the word belongs to a superscription (a Psalm title or Zechariah 12:1's
                oracle heading) — the app renders those as their own block, and BSB.sqlite's
                verses.text does not contain them, so start/length are -1.

So the app's rule for one word's English is one line:

    let english = start >= 0 ? verseText[start ..< start + length] : record.english

which also means the highlight is exact by construction — the build proved the slice, so nothing
re-implements the table's spacing rules at runtime. start/length are -1 for every word of a
superscription and for every word of a verse whose reconstruction does not match BSB.sqlite
(`verses.aligned = 0`, 7 verses); those keep their text in `english`. See the alignment report
the build writes next to the cached sources.
"""

import csv
import hashlib
import html
import os
import re
import sqlite3
import sys
import tempfile
import unicodedata
import urllib.parse
import urllib.request
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE_DIR = os.path.join(ROOT, "Data", "cache", "interlinear")
OUTPUT_DIR = os.path.join(ROOT, "ScriptureAlone", "Resources", "Study")
OUTPUT = os.path.join(OUTPUT_DIR, "Interlinear.sqlite")
BSB = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles", "BSB.sqlite")
REPORT = os.path.join(CACHE_DIR, "alignment-mismatches.txt")

# The lexicons are pinned to the last commit that touched STEPBible-Data/Lexicons.
STEPBIBLE_COMMIT = "48b7cfbda441adb6445ea565b4ed23dd98dfdf2e"
STEPBIBLE_RAW = f"https://raw.githubusercontent.com/STEPBible/STEPBible-Data/{STEPBIBLE_COMMIT}/Lexicons/"
TBESH = "TBESH - Translators Brief lexicon of Extended Strongs for Hebrew - STEPBible.org CC BY.txt"
TBESG = "TBESG - Translators Brief lexicon of Extended Strongs for Greek - STEPBible.org CC BY.txt"

SOURCES = {
    # bereanbible.com publishes no versioned URL, so a changed digest is a warning, not an error:
    # review the diff, re-run the alignment check, then record the new digest here.
    "bsb_tables.tsv": ("https://bereanbible.com/bsb_tables.tsv",
                       "09bbee6f9fe4fa22b5df28e8a9ffa99bf9c33435f4eb8c47c2dc221d855d35cb", False),
    TBESH: (STEPBIBLE_RAW + urllib.parse.quote(TBESH),
            "464dccadd95fd8620dd05fa0d7a4caba58ec3c4d5db3ebf38e43d046ca25b591", True),
    TBESG: (STEPBIBLE_RAW + urllib.parse.quote(TBESG),
            "312f723d7b8ef263bbdfb0451c9b8057125804dfff390b6f8544cff2a84b57f4", True),
}

CHECKED = "2026-09-19"

# Both licences require attribution, and the app displays these verbatim. The wording follows what
# each source's own licence page asks for; see docs/original-language-data.md for the quotations.
ATTRIBUTION = {
    "bsb_attribution":
        "Interlinear word data from the Berean Standard Bible Translation Tables "
        "(bsb_tables.tsv), Berean Bible, bereanbible.com. "
        "“The Berean Bible and Majority Bible texts are officially dedicated to the public domain "
        "as of April 30, 2023. All uses are freely permitted.” "
        "Attribution is appreciated but not required. The Berean Standard Bible text bundled with "
        "this app is unaltered.",
    "bsb_license": "Public domain",
    "bsb_license_url": "https://berean.bible/terms.htm",
    "bsb_url": "https://bereanbible.com/bsb_tables.tsv",
    "stepbible_attribution":
        "Lexicon entries from TBESH and TBESG, the Translators Brief lexicons of Extended Strongs "
        "for Hebrew and for Greek. Data created by www.STEPBible.org based on work at Tyndale "
        "House Cambridge, licensed under CC BY 4.0. "
        "TBESH is an abridged Brown-Driver-Briggs; TBESG is based on the Abbott-Smith definitions. "
        "Refer others to github.com/STEPBible as the source of the data.",
    "stepbible_license": "CC BY 4.0",
    "stepbible_license_url": "https://creativecommons.org/licenses/by/4.0/",
    "stepbible_url": "https://github.com/STEPBible/STEPBible-Data",
    # CC BY 4.0 requires that modifications be indicated.
    "stepbible_changes":
        "Changes made for this app: entries were selected to the Strong's numbers used by the "
        "Berean Standard Bible Translation Tables, extended Strong's keys were collapsed to their "
        "base number (H0430G to H0430) with the sub-senses kept in order, and the HTML markup in "
        "the definitions was converted to plain text. No definition wording was altered.",
}

BOOKS = [
    "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth",
    "1 Samuel", "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra",
    "Nehemiah", "Esther", "Job", "Psalm", "Proverbs", "Ecclesiastes", "Song of Solomon", "Isaiah",
    "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos", "Obadiah", "Jonah",
    "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah", "Malachi", "Matthew", "Mark",
    "Luke", "John", "Acts", "Romans", "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians",
    "Philippians", "Colossians", "1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy",
    "Titus", "Philemon", "Hebrews", "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John",
    "Jude", "Revelation",
]
BOOK_ORD = {name: i + 1 for i, name in enumerate(BOOKS)}

# Columns of bsb_tables.tsv, which has no stable ordering guarantee beyond its header row.
COLUMNS = ["Heb Sort", "Greek Sort", "BSB Sort", "Verse", "Language", "WLC / Nestle Base plain",
           "WLC / Nestle Base bracketed", "Translit", "Parsing", "Parsing expanded", "Str Heb",
           "Str Grk", "VerseId", "Hdg", "Crossref", "Par", "Space", "begQ", "BSB version", "pnc",
           "endQ", "footnotes", "End text"]
C = {name: i for i, name in enumerate(COLUMNS)}


def key(book, chapter, verse):
    return book * 1_000_000 + chapter * 1_000 + verse


def reference(verse_key):
    return f"{BOOKS[verse_key // 1_000_000 - 1]} {verse_key // 1_000 % 1_000}:{verse_key % 1_000}"


# MARK: - Sources

def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(name, force=False):
    url, expected, pinned = SOURCES[name]
    path = os.path.join(CACHE_DIR, name)
    if force or not os.path.exists(path):
        os.makedirs(CACHE_DIR, exist_ok=True)
        print(f"  fetching {name}")
        tmp = path + ".part"
        urllib.request.urlretrieve(url, tmp)
        os.replace(tmp, path)
    actual = sha256(path)
    if actual != expected:
        if pinned:
            sys.exit(f"{name}: SHA-256 {actual} does not match the pinned {expected}. "
                     f"Delete {path} and retry.")
        print(f"warning: {name} sha256 {actual} differs from the recorded {expected}; "
              f"upstream has changed. Review the alignment report before recording the new digest.")
    return path


# MARK: - The BSB translation tables

TAG = re.compile(r"<[^>]*>")
REFTEXT = re.compile(r"<span class=\|reftext\|>.*?</span>")   # the inline verse-number marker
PSHDG = re.compile(r"pshdg")                                   # the superscription paragraph class
VERSE_ID = re.compile(r"^(.*) (\d+):(\d+)$")
SUPPLIED = str.maketrans("", "", "[]{}")   # the table italicises supplied words with [ ] and { }
OPENERS = "(“‘"


def squash(text):
    return re.sub(r"\s+", " ", text.replace("\u00a0", " ")).strip()


def strip_markup(text, supplied=True):
    text = TAG.sub("", text.replace("\u00a0", " "))
    if supplied:
        text = text.translate(SUPPLIED)
    return squash(text)


def english_of(row):
    """The BSB words one table row renders, or "" when it renders none.

    The table writes "-" for an original word with no English counterpart, ". . ." for English
    that belongs to an earlier word, and "vvv" for a footnote caller. A cell can also mix a
    placeholder with real text ("( - " opens a parenthesis with no word of its own).
    """
    text = strip_markup(row[C["BSB version"]])
    if not text or text == "vvv" or set(text) <= set(". -"):
        return ""
    return " ".join(part for part in text.split(" ") if part != "-")


def fragments(row):
    """(text, glue, is_english) pieces one row contributes to the rendered verse.

    glue "left" joins the piece to what precedes it, "right" to what follows, "free" takes a
    space. The table pads cells that want a space (" light ", " — ") and leaves the ones that
    should touch their neighbour unpadded (",", "”"); opening quotes always take a space before
    and none after.
    """
    pieces = []
    opening = strip_markup(REFTEXT.sub("", row[C["begQ"]]))
    if opening:
        pieces.append((opening, "right", False))
    english = english_of(row)
    if english:
        pieces.append((english, "right" if english[-1] in OPENERS else "free", True))
    for column, supplied in (("pnc", True), ("endQ", False), ("End text", False)):
        raw = row[C[column]].replace("\u00a0", " ")
        text = strip_markup(raw, supplied)
        if text:
            pieces.append((text, "free" if raw[:1] == " " else "left", False))
    return pieces


def render(rows):
    """Join a verse's rows back into the running English text, and say where each word landed.

    Returns (text, spans) where spans[i] is the (start, length) of rows[i]'s English inside text,
    in UTF-16 code units, or None when that row renders no English.
    """
    out = []
    length = 0          # in UTF-16 code units, which is what Swift's String indexing wants
    spans = []
    previous = None

    def append(text, glue):
        nonlocal length
        if out and glue != "left" and previous != "right":
            out.append(" ")
            length += 1
        start = length
        out.append(text)
        length += len(text.encode("utf-16-le")) // 2
        return start

    for row in rows:
        span = None
        for text, glue, is_english in fragments(row):
            start = append(text, glue)
            if is_english:
                span = (start, length - start)
            previous = glue
        spans.append(span)
    return "".join(out), spans


def split_superscription(rows):
    """Split a verse's rows into (superscription, body).

    The table puts Psalm titles and Zechariah 12:1's oracle heading inside verse 1, in a
    "pshdg" paragraph, and then marks the start of the verse proper with an inline verse number.
    BSB.sqlite keeps them out of verses.text and renders them as a separate layout block.
    """
    count = 0
    paragraph = ""
    for index, row in enumerate(rows):
        if row[C["Par"]].strip():
            paragraph = row[C["Par"]]
        if REFTEXT.search(row[C["begQ"]]) or not PSHDG.search(paragraph):
            break
        count = index + 1
    return rows[:count], rows[count:]


def read_tables(path):
    """Group the table into verses: {verse_key: [row, ...]} in BSB reading order."""
    with open(path, encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle, delimiter="\t", quoting=csv.QUOTE_NONE)
        header = next(reader)
        if len(header) != len(COLUMNS) or header[0].strip() != "Heb Sort" or header[12].strip() != "VerseId":
            sys.exit(f"bsb_tables.tsv: unexpected {len(header)} columns {header[:4]}…; "
                     "the layout changed, re-read the header before trusting COLUMNS")
        verses = {}
        order = []
        current = None
        for row in reader:
            marker = row[C["VerseId"]].strip()
            if marker:
                match = VERSE_ID.match(marker)
                if not match or match.group(1) not in BOOK_ORD:
                    sys.exit(f"bsb_tables.tsv: cannot parse verse id {marker!r}")
                current = key(BOOK_ORD[match.group(1)], int(match.group(2)), int(match.group(3)))
                verses[current] = []
                order.append(current)
            if current is not None:
                verses[current].append(row)
    return verses, order


def is_word(row):
    """The table pads every verse out with empty rows; a real word has a form or a number."""
    return bool(row[C["WLC / Nestle Base plain"]].strip() or row[C["Str Heb"]].strip()
                or row[C["Str Grk"]].strip())


def word_rows(rows):
    return [row for row in rows if is_word(row)]


def strongs_of(row):
    hebrew, greek = row[C["Str Heb"]].strip(), row[C["Str Grk"]].strip()
    if hebrew:
        return f"H{int(hebrew):04d}"
    if greek:
        return f"G{int(greek):04d}"
    return ""


def language_of(row):
    return {"Hebrew": "H", "Aramaic": "A", "Greek": "G"}.get(row[C["Language"]].strip(), "H")


def original_order(rows):
    """1-based rank of each row in original-language order, from the table's own sort keys."""
    keys = []
    for index, row in enumerate(rows):
        hebrew, greek = row[C["Heb Sort"]].strip(), row[C["Greek Sort"]].strip()
        # The sort keys are mostly integers but the table uses halves (8132.5) to slot a word in.
        raw = greek if language_of(row) == "G" and greek not in ("", "0") else hebrew
        value = float(raw) if raw else 0.0
        keys.append((value, index))
    ranks = [0] * len(rows)
    for rank, (_, index) in enumerate(sorted(keys), start=1):
        ranks[index] = rank
    return ranks


# MARK: - The STEPBible lexicons

LEX_KEY = re.compile(r"^([HG])(\d{1,5})([a-zA-Z]*)$")
BREAK = re.compile(r"<\s*br\s*/?\s*>", re.IGNORECASE)


def plain(text):
    """STEPBible definitions carry <b>, <i>, <ref=…> and <BR />; render them as plain text."""
    text = html.unescape(TAG.sub("", BREAK.sub("\n", text)))
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.replace("\u00a0", " ").split("\n")]
    return "\n".join(line for line in lines if line)


def read_lexicon(path):
    """{base Strong's number: [(form, translit, morph, gloss, meaning), ...]} in file order."""
    entries = {}
    for line in open(path, encoding="utf-8-sig"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 7:
            continue
        match = LEX_KEY.match(parts[0].strip())
        if not match:
            continue
        base = f"{match.group(1)}{int(match.group(2)):04d}"
        meaning = plain(parts[7]) if len(parts) > 7 else ""
        entries.setdefault(base, []).append(
            (squash(parts[3]), squash(parts[4]), squash(parts[5]), squash(parts[6]), meaning))
    return entries


# MARK: - Build

def compress(text):
    packer = zlib.compressobj(9, zlib.DEFLATED, -15)  # raw DEFLATE: what Apple's COMPRESSION_ZLIB reads
    return packer.compress(text.encode("utf-8")) + packer.flush()


def bundled_verses():
    db = sqlite3.connect(f"file:{BSB}?mode=ro", uri=True)
    text = {k: t for k, t in db.execute("SELECT id, text FROM verses")}
    db.close()
    return text


def build():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    force = "--fetch" in sys.argv
    tables_path = fetch("bsb_tables.tsv", force)
    hebrew_path = fetch(TBESH, force)
    greek_path = fetch(TBESG, force)

    print("Reading the BSB translation tables")
    verses, order = read_tables(tables_path)
    bundled = bundled_verses()
    print(f"  {len(order)} verses, {len(bundled)} in BSB.sqlite")
    only_table = sorted(set(order) - set(bundled))
    missing = sorted(set(bundled) - set(order))
    if missing:
        sys.exit(f"{len(missing)} verses of BSB.sqlite are absent from the tables: "
                 + ", ".join(reference(k) for k in missing[:10]))
    print(f"  {len(only_table)} verses the tables carry and BSB.sqlite omits "
          f"({', '.join(reference(k) for k in only_table[:4])}…) — dropped")

    parsings = {}
    chapters = {}
    verse_rows = []
    strongs_used = set()
    aligned = 0
    superscriptions = 0
    mismatches = []
    tagged = 0

    for verse_key in order:
        if verse_key not in bundled:
            continue
        superscription, body = split_superscription(verses[verse_key])
        if superscription:
            superscriptions += 1
        rendered, spans = render(body)
        want = squash(bundled[verse_key])
        ok = rendered == want
        if ok:
            aligned += 1
        else:
            mismatches.append((verse_key, rendered, want))

        # render() walked every row of the body; keep the spans of the ones that are words.
        head = word_rows(superscription)
        tail = [(row, span) for row, span in zip(body, spans) if is_word(row)]
        rows = head + [row for row, _ in tail]
        row_spans = [None] * len(head) + [span for _, span in tail]
        ranks = original_order(rows)

        records = []
        for index, row in enumerate(rows):
            parsing = (squash(row[C["Parsing"]]), squash(row[C["Parsing expanded"]]))
            parsing_id = 0
            if parsing[0] or parsing[1]:
                parsing_id = parsings.setdefault(parsing, len(parsings) + 1)
            number = strongs_of(row)
            if number:
                strongs_used.add(number)
                tagged += 1
            is_superscription = index < len(head)
            span = row_spans[index] if ok else None
            records.append("\t".join((
                "" if span else english_of(row).replace("\t", " "),
                squash(row[C["WLC / Nestle Base plain"]]),
                squash(row[C["Translit"]]),
                str(parsing_id),
                number,
                language_of(row),
                str(ranks[index]),
                str(span[0] if span else -1),
                str(span[1] if span else -1),
                str(1 if is_superscription else 0),
            )))
        chapter_key = verse_key // 1_000
        block = chapters.setdefault(chapter_key, [])
        verse_rows.append((verse_key, chapter_key, len(block), len(records), 1 if ok else 0))
        block.extend(records)

    print(f"  {tagged} words carry a Strong's number, {len(strongs_used)} distinct numbers")
    print(f"  {superscriptions} verses carry a superscription, split off from the verse text")
    print(f"alignment: {aligned} of {len(bundled)} verses reproduce BSB.sqlite exactly "
          f"({aligned / len(bundled) * 100:.2f}%), {len(mismatches)} do not")
    write_report(mismatches)

    print("Reading the STEPBible lexicons")
    lexicon = read_lexicon(hebrew_path)
    for number, senses in read_lexicon(greek_path).items():
        lexicon.setdefault(number, []).extend(senses)
    resolved = sorted(n for n in strongs_used if n in lexicon)
    unresolved = sorted(n for n in strongs_used if n not in lexicon)
    print(f"  {len(lexicon)} base Strong's numbers in TBESH + TBESG; "
          f"{len(resolved)} of {len(strongs_used)} used numbers resolve "
          f"({len(resolved) / len(strongs_used) * 100:.2f}%)")
    if unresolved:
        sys.exit(f"{len(unresolved)} Strong's numbers have no lexicon entry: "
                 + ", ".join(unresolved[:20]))

    fd, tmp = tempfile.mkstemp(suffix=".sqlite", dir=OUTPUT_DIR)
    os.close(fd)
    db = sqlite3.connect(tmp)
    db.executescript("""
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE chapters (chapter_key INTEGER PRIMARY KEY, words BLOB NOT NULL);
        CREATE TABLE verses (verse_key INTEGER PRIMARY KEY, chapter_key INTEGER NOT NULL,
            first INTEGER NOT NULL, count INTEGER NOT NULL, aligned INTEGER NOT NULL);
        CREATE TABLE parsings (id INTEGER PRIMARY KEY, code TEXT NOT NULL, description TEXT NOT NULL);
        CREATE TABLE lexicon (strongs TEXT PRIMARY KEY, entry_id INTEGER NOT NULL,
            part INTEGER NOT NULL) WITHOUT ROWID;
        CREATE TABLE lexicon_text (id INTEGER PRIMARY KEY, body BLOB NOT NULL);
    """)

    meta = dict(ATTRIBUTION)
    meta.update({
        "version": "1",
        "checked": CHECKED,
        "stepbible_commit": STEPBIBLE_COMMIT,
        "words": str(sum(len(block) for block in chapters.values())),
        "tagged_words": str(tagged),
        "strongs": str(len(strongs_used)),
        "verses_aligned": str(aligned),
        "verses_total": str(len(bundled)),
        "word_record": "english\toriginal\ttranslit\tparsing\tstrongs\tlang\torder\tstart\tlength\tflags",
        "english_from_bsb": "1",   # english is empty whenever start >= 0; slice BSB.sqlite instead
    })
    db.executemany("INSERT INTO meta VALUES (?, ?)", sorted(meta.items()))

    words_raw = words_packed = 0
    for chapter_key in sorted(chapters):
        joined = "\n".join(chapters[chapter_key])
        body = compress(joined)
        words_raw += len(joined.encode("utf-8"))
        words_packed += len(body)
        db.execute("INSERT INTO chapters VALUES (?, ?)", (chapter_key, body))
    db.executemany("INSERT INTO verses VALUES (?, ?, ?, ?, ?)", verse_rows)
    db.executemany("INSERT INTO parsings VALUES (?, ?, ?)",
                   [(i, code, description) for (code, description), i in parsings.items()])
    print(f"interlinear: {len(chapters)} chapters, {words_raw / 1e6:.1f} MB of records -> "
          f"{words_packed / 1e6:.2f} MB compressed; {len(parsings)} parsing codes")

    # The lexicon is compressed a bucket of entries at a time: 13,876 entries compressed one by
    # one lose ~40% to DEFLATE's per-stream overhead, and a bucket still costs one decompress.
    lexicon_raw = lexicon_packed = 0
    entry_id = 0
    for start in range(0, len(resolved), 64):
        entry_id += 1
        bucket = resolved[start:start + 64]
        joined = "\0".join(
            "\x1e".join("\t".join(field.replace("\t", " ").replace("\0", " ").replace("\x1e", " ")
                                  for field in sense)
                        for sense in lexicon[number])
            for number in bucket)
        body = compress(joined)
        lexicon_raw += len(joined.encode("utf-8"))
        lexicon_packed += len(body)
        db.execute("INSERT INTO lexicon_text VALUES (?, ?)", (entry_id, body))
        db.executemany("INSERT INTO lexicon VALUES (?, ?, ?)",
                       [(number, entry_id, part) for part, number in enumerate(bucket)])
    senses = sum(len(lexicon[n]) for n in resolved)
    print(f"lexicon: {len(resolved)} Strong's numbers, {senses} sub-senses, "
          f"{lexicon_raw / 1e6:.1f} MB of text -> {lexicon_packed / 1e6:.2f} MB compressed")

    db.commit()
    db.execute("VACUUM")
    db.close()
    os.chmod(tmp, 0o644)  # mkstemp creates 0600
    os.replace(tmp, OUTPUT)
    report_size()
    return OUTPUT


def report_size():
    db = sqlite3.connect(f"file:{OUTPUT}?mode=ro", uri=True)
    total = os.path.getsize(OUTPUT)
    print(f"-> {os.path.relpath(OUTPUT, ROOT)} ({total / 1e6:.2f} MB)")
    try:
        rows = db.execute("SELECT name, pgsize FROM dbstat WHERE aggregate = 1").fetchall()
    except sqlite3.OperationalError:
        rows = [(name, size) for name, size in (
            ("chapters (payload)", db.execute("SELECT sum(length(words)) FROM chapters").fetchone()[0]),
            ("lexicon_text (payload)", db.execute("SELECT sum(length(body)) FROM lexicon_text").fetchone()[0]),
        )]
    for name, size in sorted(rows, key=lambda r: -r[1]):
        print(f"     {name:<24} {size / 1e6:6.2f} MB")
    db.close()


def write_report(mismatches):
    os.makedirs(CACHE_DIR, exist_ok=True)
    with open(REPORT, "w", encoding="utf-8") as handle:
        handle.write(f"{len(mismatches)} verses where the BSB translation tables do not reproduce "
                     f"ScriptureAlone/Resources/Bibles/BSB.sqlite exactly.\n"
                     f"'tables' is this build's reconstruction; 'bundled' is BSB.sqlite.\n\n")
        for verse_key, got, want in mismatches:
            handle.write(f"{reference(verse_key)}  ({verse_key})\n  tables : {got}\n  bundled: {want}\n\n")
    print(f"  alignment report -> {os.path.relpath(REPORT, ROOT)}")


# MARK: - Verify

CHAPTER_CACHE = {}


def chapter_words(db, chapter_key):
    if chapter_key not in CHAPTER_CACHE:
        CHAPTER_CACHE.clear()
        blob = db.execute("SELECT words FROM chapters WHERE chapter_key = ?",
                          (chapter_key,)).fetchone()[0]
        CHAPTER_CACHE[chapter_key] = [line.split("\t")
                                      for line in zlib.decompress(blob, -15).decode("utf-8").split("\n")]
    return CHAPTER_CACHE[chapter_key]


def words_of(db, verse_key):
    row = db.execute("SELECT chapter_key, first, count FROM verses WHERE verse_key = ?",
                     (verse_key,)).fetchone()
    if not row:
        return []
    chapter_key, first, count = row
    return chapter_words(db, chapter_key)[first:first + count]


def lexicon_entry(db, number):
    row = db.execute("SELECT body, part FROM lexicon JOIN lexicon_text ON id = entry_id "
                     "WHERE strongs = ?", (number,)).fetchone()
    if not row:
        return None
    part = zlib.decompress(row[0], -15).decode("utf-8").split("\0")[row[1]]
    return [sense.split("\t") for sense in part.split("\x1e")]


def consonants(word):
    """Hebrew without its vowel points and cantillation marks."""
    return "".join(c for c in unicodedata.normalize("NFD", word) if not unicodedata.combining(c))


def verify(path=OUTPUT):
    if not os.path.exists(path):
        sys.exit(f"{path} does not exist; build it first")
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    bsb = sqlite3.connect(f"file:{BSB}?mode=ro", uri=True)
    text = {k: squash(t) for k, t in bsb.execute("SELECT id, text FROM verses")}
    bsb.close()
    failures = []

    def expect(condition, message):
        if not condition:
            failures.append(message)

    def english(words, verse_key):
        """What the app will show under each word: the rule from the module docstring."""
        units = text[verse_key].encode("utf-16-le")
        out = []
        for word in words:
            start, length = int(word[7]), int(word[8])
            out.append(units[start * 2:(start + length) * 2].decode("utf-16-le")
                       if start >= 0 else word[0])
        return out

    meta = dict(db.execute("SELECT key, value FROM meta"))
    for field in ("bsb_attribution", "stepbible_attribution", "stepbible_changes", "word_record"):
        expect(meta.get(field), f"meta.{field} is missing")
    expect("public domain" in meta.get("bsb_attribution", ""), "the BSB dedication is not quoted")
    expect("CC BY 4.0" in meta.get("stepbible_attribution", ""), "STEPBible's licence is not named")

    # Genesis 1:1 — seven Hebrew words, English order 1,3,4,2,5,6,7 in the original.
    genesis = words_of(db, key(1, 1, 1))
    expect(len(genesis) == 7, f"Genesis 1:1 has {len(genesis)} words, expected 7")
    if len(genesis) == 7:
        expect(english(genesis, key(1, 1, 1)) ==
               ["In the beginning", "God", "", "created", "the heavens", "and", "the earth"],
               f"Genesis 1:1 English: {english(genesis, key(1, 1, 1))}")
        expect([int(w[6]) for w in genesis] == [1, 3, 4, 2, 5, 6, 7],
               f"Genesis 1:1 original order: {[w[6] for w in genesis]}")
        expect([w[4] for w in genesis] ==
               ["H7225", "H0430", "H0853", "H1254", "H8064", "H0853", "H0776"],
               f"Genesis 1:1 Strong's: {[w[4] for w in genesis]}")
        expect(consonants(genesis[0][1]) == "בראשית",
               f"Genesis 1:1 opens with {genesis[0][1]!r}")
        expect(genesis[0][2] == "bə·rê·šîṯ", f"Genesis 1:1 transliteration {genesis[0][2]!r}")
        expect(all(w[5] == "H" for w in genesis), "Genesis 1:1 should be Hebrew throughout")

    # John 1:1 — Greek, and the English reads in order.
    john = words_of(db, key(43, 1, 1))
    expect(len(john) >= 15, f"John 1:1 has {len(john)} words")
    if john:
        expect(all(w[5] == "G" for w in john), "John 1:1 should be Greek throughout")
        reading = " ".join(w for w in english(john, key(43, 1, 1)) if w)
        expect(reading.startswith("In the beginning was the Word"), f"John 1:1 reads {reading!r}")
        forms = [unicodedata.normalize("NFC", w[1]).lower() for w in john]
        expect(forms.count("λόγος") == 3 and "θεόν" in forms,
               f"John 1:1 should say λόγος three times: {forms}")
        expect(sorted(int(w[6]) for w in john) == list(range(1, len(john) + 1)),
               "John 1:1 original-language order is not a permutation")

    # Every verse resolves, its spans land on real words of the bundled verse, in reading order,
    # and its original-language order is a permutation of 1…n.
    keys = [k for (k,) in db.execute("SELECT verse_key FROM verses")]
    expect(set(keys) == set(text), "verse keys do not line up with BSB.sqlite: "
           f"{len(set(keys) - set(text))} extra, {len(set(text) - set(keys))} missing")
    empty = []
    bad_spans = bad_order = out_of_order = 0
    for verse_key in keys:
        words = words_of(db, verse_key)
        if not words:
            empty.append(verse_key)
            continue
        if sorted(int(w[6]) for w in words) != list(range(1, len(words) + 1)):
            bad_order += 1
        previous = -1
        for word, slice_ in zip(words, english(words, verse_key)):
            start = int(word[7])
            if start < 0:
                continue
            if not slice_ or slice_ != squash(slice_) or word[0]:
                bad_spans += 1
            if start < previous:
                out_of_order += 1
            previous = start
    # Nehemiah 7:68 is the one verse with no original-language words at all: the BSB supplies it
    # from "some Hebrew manuscripts", and most of the MT does not have it.
    expect(empty == [key(16, 7, 68)],
           f"verses with no words: {[reference(k) for k in empty]}")
    expect(bad_spans == 0, f"{bad_spans} words point at the wrong text in BSB.sqlite")
    expect(bad_order == 0, f"{bad_order} verses have a broken original-language order")
    expect(out_of_order == 0, f"{out_of_order} words are not in English reading order")

    # Every Strong's number in the data resolves to a lexicon entry.
    numbers = set()
    for (blob,) in db.execute("SELECT words FROM chapters"):
        for line in zlib.decompress(blob, -15).decode("utf-8").split("\n"):
            field = line.split("\t")[4]
            if field:
                numbers.add(field)
    have = {n for (n,) in db.execute("SELECT strongs FROM lexicon")}
    expect(numbers <= have, f"{len(numbers - have)} Strong's numbers have no entry: "
           f"{sorted(numbers - have)[:10]}")
    for number, gloss in (("H0430", "God"), ("G3056", "word")):
        entry = lexicon_entry(db, number)
        expect(entry and any(gloss.lower() in sense[3].lower() for sense in entry),
               f"{number} should gloss as {gloss!r}; got {entry and [s[3] for s in entry][:4]}")
        expect(entry and all(len(sense) == 5 for sense in entry), f"{number} entry is malformed")

    # Parsing ids resolve, and the two indexes the app relies on exist.
    parsing_ids = {int(line.split("\t")[3])
                   for (blob,) in db.execute("SELECT words FROM chapters")
                   for line in zlib.decompress(blob, -15).decode("utf-8").split("\n")}
    known = {i for (i,) in db.execute("SELECT id FROM parsings")} | {0}
    expect(parsing_ids <= known, f"{len(parsing_ids - known)} parsing ids are dangling")
    plan = db.execute("EXPLAIN QUERY PLAN SELECT * FROM verses WHERE verse_key = 1001001").fetchall()
    expect(any("PRIMARY KEY" in str(step) or "rowid" in str(step).lower() for step in plan),
           f"verse lookup is not indexed: {plan}")
    plan = db.execute("EXPLAIN QUERY PLAN SELECT * FROM lexicon WHERE strongs = 'H0430'").fetchall()
    expect(any("PRIMARY KEY" in str(step) for step in plan), f"lexicon lookup is not indexed: {plan}")

    aligned = db.execute("SELECT count(*) FROM verses WHERE aligned = 1").fetchone()[0]
    expect(aligned >= 30_900, f"only {aligned} verses align with BSB.sqlite")
    size = os.path.getsize(path)
    expect(size < 13_000_000, f"the database is {size / 1e6:.1f} MB")

    db.close()
    if failures:
        print("VERIFY FAILED")
        for failure in failures:
            print("  - " + failure)
        sys.exit(1)
    print(f"verify: ok ({aligned} verses aligned, {len(numbers)} Strong's numbers, "
          f"{size / 1e6:.2f} MB)")


def main():
    if "--verify-only" in sys.argv:
        verify()
        return
    build()
    if "--verify" in sys.argv:
        verify()


if __name__ == "__main__":
    main()
