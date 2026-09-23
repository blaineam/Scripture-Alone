#!/usr/bin/env python3
"""Compile USFM sources into the offline SQLite databases the app bundles.

    python3 Tools/build_bibles.py            # builds every translation in TRANSLATIONS
    python3 Tools/build_bibles.py --check    # builds, then asserts known verses

Each database holds:
  meta      key/value (id, name, abbreviation, copyright, license)
  books     66 rows: canonical ordinal, USFM code, short name, chapter count
  chapters  one row per chapter: the layout JSON the reader renders
  verses    one row per verse: plain text + red-letter spans (search, TTS, sharing)
  verses_fts  FTS5 index over verses.text
  kjv_map   native verse id -> KJV verse id (and last KJV verse of a range), only where they
            differ. Each translation keeps its own numbering — a French reader's Psalm 51:12 is
            Psalm 51:12 — while highlights, notes and cross-references are stored against the one
            KJV key space, so they line up whichever translation is open. No rows = identity.

Verse ids are book * 1_000_000 + chapter * 1_000 + verse, the same key the app's
highlights and notes store, so they survive a translation switch.

Layout JSON (compact keys to keep the bundle small):
  {"b": [block, ...]}
  block    {"k": kind, "t": text}                 heading kinds: s1 s2 ms r qa
           {"k": kind, "f": [fragment, ...]}      text kinds: p m pmo pc li1 li2 q1 q2 qr d
           {"k": "b"}                             stanza break
  fragment {"v": verse, "n": 1 if the verse number starts here, "t": text, "e": last verse of a range,
            "s": [[start, length, style], ...], "fn": [[position, note], ...]}
  styles   r = words of Christ, i = supplied words (KJV italics), c = small caps (LORD)
Offsets count Unicode scalars, which is what Swift's String.unicodeScalars indexes.
"""

import json
import os
import re
import sqlite3
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_DIR = os.path.join(ROOT, "Data", "source")
OUTPUT_DIR = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles")

# Canonical Protestant order. Ordinals match BookID in ScriptureAloneCore.
BOOKS = [
    "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI",
    "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER",
    "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC", "NAM", "HAB", "ZEP",
    "HAG", "ZEC", "MAL", "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL",
    "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE",
    "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
]

TRANSLATIONS = [
    {
        "id": "ASV",
        "zip": "asv_usfm.zip",
        "name": "American Standard Version",
        "abbreviation": "ASV",
        "copyright": "American Standard Version (1901). Public domain. Section headings from the Berean Standard Bible (public domain).",
        "license": "Public domain",
        "source": "https://ebible.org/find/details.php?id=eng-asv",
        # The ASV ships without red letters or section headings. Words of Christ are
        # aligned word-by-word from the KJV it revised; headings come from the BSB.
        "red_from": "KJV",
        "headings_from": "BSB",
    },
    {
        "id": "BSB",
        "zip": "bsb_usfm.zip",
        "name": "Berean Standard Bible",
        "abbreviation": "BSB",
        "copyright": "The Holy Bible, Berean Standard Bible, BSB. Dedicated to the public domain on April 30, 2023.",
        "license": "Public domain",
        "source": "https://berean.bible/downloads.htm",
    },
    {
        "id": "KJV",
        "zip": "kjv_usfm.zip",
        "name": "King James Version",
        "abbreviation": "KJV",
        "copyright": "King James Version (1769 Oxford text). Public domain outside the United Kingdom.",
        "license": "Public domain",
        "source": "https://ebible.org/find/details.php?id=eng-kjv2006",
    },
    # The big-8 locales' Bibles (docs/localization.md): each the whole 66-book Protestant canon,
    # translated from the Hebrew and Greek, and free to redistribute. Delivered as on-demand packs
    # and chosen by the device's language at first launch.
    {
        "id": "CUVS",
        "locale": "zh-Hans",
        "zip": "cmn-cu89s_usfm.zip",
        "name": "和合本（新标点）",
        "abbreviation": "和合本",
        "copyright": "新标点和合本（简体）。中文和合本圣经（1919），公有领域。",
        "license": "Public domain",
        "source": "https://ebible.org/find/details.php?id=cmn-cu89s",
        "john_3_16": "「 神爱世人，",
    },
    {
        # The 文語訳 rather than the 口語訳: the 口語訳's CrossWire module is missing whole chapters
        # and its public-domain status is disputed (docs/localization.md). Literary Japanese.
        "id": "BUNGO",
        "locale": "ja",
        "zip": "jpn-bungo_usfm.zip",   # converted by Tools/sword_to_usfm.py from CrossWire JapBungo
        "name": "文語訳聖書",
        "abbreviation": "文語訳",
        "copyright": "文語訳聖書（旧約：明治元訳 1887年、新約：大正改訳 1917年）。パブリックドメイン。",
        "license": "Public domain",
        "source": "https://www.crosswire.org/sword/modules/ModInfo.jsp?modName=JapBungo",
        "john_3_16": "それ神はその獨子を賜ふほどに世を愛し給へり",
    },
    {
        "id": "LUT1912",
        "locale": "de",
        "zip": "deu1912_usfm.zip",
        "name": "Lutherbibel 1912",
        "abbreviation": "LUT",
        "copyright": "Lutherbibel 1912. Gemeinfrei.",
        "license": "Public domain",
        "source": "https://ebible.org/find/details.php?id=deu1912",
        "john_3_16": "Also hat Gott die Welt geliebt",
    },
    {
        "id": "LSG",
        "locale": "fr",
        "zip": "fraLSG_usfm.zip",
        "name": "Louis Segond 1910",
        "abbreviation": "LSG",
        "copyright": "La Sainte Bible, traduction Louis Segond (1910). Domaine public.",
        "license": "Public domain",
        "source": "https://ebible.org/find/details.php?id=fraLSG",
        "john_3_16": "Car Dieu a tant aimé le monde",
    },
    {
        "id": "RVR1909",
        "locale": "es",
        "zip": "spaRV1909_usfm.zip",
        "name": "Reina-Valera 1909",
        "abbreviation": "RV1909",
        "copyright": "Santa Biblia, Reina-Valera 1909. Dominio público.",
        "license": "Public domain",
        "source": "https://ebible.org/find/details.php?id=spaRV1909",
        "john_3_16": "Porque de tal manera amó Dios al mundo",
    },
    {
        "id": "KRV",
        "locale": "ko",
        "zip": "kor-rv_usfm.zip",   # converted by Tools/sword_to_usfm.py from CrossWire KorRV
        "name": "개역한글",
        "abbreviation": "개역한글",
        "copyright": "성경전서 개역한글판(1952/1961). 공유 저작물.",
        "license": "Public domain",
        "source": "https://www.crosswire.org/sword/modules/ModInfo.jsp?modName=KorRV",
        "john_3_16": "하나님이 세상을 이처럼 사랑하사",
    },
    {
        "id": "BLIVRE",
        "locale": "pt-BR",
        "zip": "porbr2018_usfm.zip",
        "name": "Bíblia Livre",
        "abbreviation": "BLIVRE",
        # CC BY 4.0: this line is the attribution the licence requires, and it is shown wherever
        # the text is (the reader's copyright footer, exports, share images).
        "copyright": "Bíblia Livre (BLIVRE), Copyright © 2018 Diego Santos, Mario Sérgio e Marco Teles, "
                     "https://sites.google.com/site/biblialivre/. Licença Creative Commons Atribuição 4.0 "
                     "(https://creativecommons.org/licenses/by/4.0/).",
        "license": "CC BY 4.0",
        "source": "https://ebible.org/find/details.php?id=porbr2018",
        "john_3_16": "Porque Deus amou ao mundo de tal maneira",
    },
    {
        "id": "RIV1927",
        "locale": "it",
        "zip": "ita1927_usfm.zip",
        "name": "Riveduta 1927",
        "abbreviation": "RIV",
        "copyright": "La Sacra Bibbia, versione Riveduta (Giovanni Luzzi, 1927). Pubblico dominio.",
        "license": "Public domain",
        "source": "https://ebible.org/find/details.php?id=ita1927",
        "john_3_16": "Poiché Iddio ha tanto amato il mondo",
    },
]

# Markers whose text is file metadata, never shown.
SKIP = {"id", "usfm", "ide", "h", "toc1", "toc2", "toc3", "mt", "mt1", "mt2", "mt3", "rem", "sts", "cl"}
# Paragraph markers that carry only a heading string.
HEADINGS = {"s": "s1", "s1": "s1", "s2": "s2", "s3": "s2", "ms": "ms", "ms1": "ms", "mr": "r", "r": "r", "qa": "qa", "sp": "s2"}
# Paragraph markers that carry verse text; value is the stored kind.
TEXT_BLOCKS = {
    "p": "p", "m": "m", "nb": "m", "pmo": "pmo", "pm": "pmo", "pc": "pc", "pi": "pmo", "pi1": "pmo",
    "mi": "pmo", "li": "li1", "li1": "li1", "li2": "li2", "q": "q1", "q1": "q1", "q2": "q2",
    "q3": "q2", "qr": "qr", "qc": "pc", "d": "d",
}
CHAR_STYLES = {"wj": "r", "add": "i", "nd": "c"}
MARKER = re.compile(r"\\(\+?)([a-z]+[0-9]*)(\*?)")
SPACE = re.compile(r"\s+")


class Book:
    def __init__(self, code):
        self.code = code
        self.name = code
        self.chapters = {}   # chapter -> list of blocks
        self.verses = {}     # (chapter, verse) -> {"t": str, "s": [[start, len, style]]}
        self.ranges = {}     # (chapter, verse) -> last verse, for "\\v 12-13"


def append_span(spans, start, length, style):
    """Adds a styled run, extending the latest run of the same style when it ends where this one
    starts. Looking past runs of other styles matters: text that is both red and italic appends
    one run of each, and a red run must still merge across it. Merging only with the very last
    run made the result depend on which style was appended first — which came from iterating a
    set, so the same USFM built different layouts from one run to the next."""
    if length <= 0:
        return
    for span in reversed(spans):
        if span[2] == style:
            if span[0] + span[1] == start:
                span[1] += length
                return
            break
    spans.append([start, length, style])


class Parser:
    def __init__(self, text):
        self.text = text.replace("\ufeff", "")
        self.book = None
        self.chapter = 0
        self.verse = 0
        self.block = None       # current block dict
        self.fragment = None    # current fragment dict (inside a text block)
        self.styles = []        # open character styles (r / i / c)
        self.in_word = False    # inside \w ... \w* (drop |attributes)
        self.note = None        # collecting a footnote: list of strings, or None
        self.note_field = None  # current footnote sub-marker
        self.skip_text = False  # inside a metadata marker
        self.in_ref = False     # inside \ref ... \ref* (drop |target)
        self.pending_number = False  # a \v inside a psalm title (\d) numbers the next line instead

    # -- blocks ---------------------------------------------------------------
    def blocks(self):
        return self.book.chapters.setdefault(self.chapter, [])

    def start_block(self, kind):
        self.fragment = None
        if kind == "b":
            self.blocks().append({"k": "b"})
            self.block = None
            return
        if kind in HEADINGS.values():
            self.block = {"k": kind, "t": ""}
        else:
            self.block = {"k": kind, "f": []}
        self.blocks().append(self.block)

    def ensure_text_block(self):
        if self.block is None or "f" not in self.block:
            self.start_block("m")

    def start_fragment(self, numbered):
        self.ensure_text_block()
        self.fragment = {"v": self.verse, "t": ""}
        if (self.chapter, self.verse) in self.book.ranges and numbered:
            self.fragment["e"] = self.book.ranges[(self.chapter, self.verse)]   # shown as "12–13"
        if numbered and self.block["k"] == "d":
            # Superscriptions ("A Psalm of David.") print as unnumbered titles.
            self.pending_number = True
        elif numbered or (self.pending_number and self.block["k"] != "d"):
            self.fragment["n"] = 1
            self.pending_number = False
        self.block["f"].append(self.fragment)

    # -- text -----------------------------------------------------------------
    def add_text(self, raw):
        if self.skip_text or not raw:
            return
        if self.in_word:
            raw = raw.split("|", 1)[0]
        if self.in_ref:
            raw = raw.split("|", 1)[0]
        text = SPACE.sub(" ", raw)
        if self.note is not None:
            if self.note_field not in ("fr", "caller"):
                self.note.append(text)
            return
        if self.block is not None and "t" in self.block:  # heading
            if not self.block["t"] or self.block["t"].endswith(" "):
                text = text.lstrip()
            self.block["t"] += text
            return
        if not text.strip() and (self.fragment is None or not self.fragment["t"] or self.fragment["t"].endswith(" ")):
            return
        if self.verse == 0:
            # Text before the first verse of a chapter (e.g. a KJV psalm title in \d) is a heading.
            if self.block is None or self.block.get("k") != "d":
                return
        if self.fragment is None:
            self.start_fragment(numbered=False)
        frag = self.fragment
        if getattr(self, "note_closed_without_space", False):
            self.note_closed_without_space = False
            # Only when the next run starts a *word*. A note before closing punctuation —
            # "…strike his heel.\f ...\f*”" — must not become "heel. ”", which is what a
            # blanket rule did to 217 verses.
            # Letters and opening quotes only. Brackets are not safe: Exodus 25:40 ends
            # "…on the mountain.\f ...\f*[’’]", where the bracket is an artifact in the source
            # rather than the start of anything.
            opens_a_word = bool(text) and (text[0].isalnum() or text[0] in "\u201c\u2018")
            if frag["t"] and not frag["t"].endswith(" ") and not text.startswith(" ") and opens_a_word:
                text = " " + text
        if not frag["t"] or frag["t"].endswith(" "):
            text = text.lstrip()
        if not text:
            return
        start = len(frag["t"])
        frag["t"] += text
        style = self.styles[-1] if self.styles else None
        for s in sorted(set(self.styles)):
            append_span(frag.setdefault("s", []), start, len(text), s)
        if self.verse and self.block["k"] != "d":
            self.add_verse_text(text, set(self.styles))

    def add_verse_text(self, text, styles):
        entry = self.book.verses.setdefault((self.chapter, self.verse), {"t": "", "s": []})
        if entry["t"] and not entry["t"].endswith(" ") and not text.startswith(" ") and self.fragment and self.fragment["t"] == text:
            # First text of a new block continuing the same verse: separate with a space.
            entry["t"] += " "
        if not entry["t"] or entry["t"].endswith(" "):
            text = text.lstrip()
        start = len(entry["t"])
        entry["t"] += text
        if "r" in styles:
            append_span(entry["s"], start, len(text), "r")

    # -- driver ---------------------------------------------------------------
    def parse(self, book):
        self.book = book
        pos = 0
        for m in MARKER.finditer(self.text):
            self.add_text(self.text[pos:m.start()])
            pos = m.end()
            self.marker(m.group(2), closing=bool(m.group(3)), nested=bool(m.group(1)), after=m.end())
            self._strip_one = not m.group(3)
        self.add_text(self.text[pos:])
        self.finish()

    def marker(self, name, closing, nested, after):
        # Footnotes and cross-reference notes.
        if name in ("f", "x", "fe"):
            if closing:
                if self.note is not None and name != "x" and self.fragment is not None:
                    body = SPACE.sub(" ", "".join(self.note)).strip()
                    if body:
                        self.fragment.setdefault("fn", []).append([len(self.fragment["t"]), body])
                self.note = None
                # A footnote can sit *between* two words with no space either side, because the
                # marker itself occupies that gap: "Samuel,\f ...\f*saying". Dropping the note
                # from the running text would glue them together ("Samuel,saying"), which is how
                # six verses shipped. Remember the gap; the next text run closes it.
                self.note_closed_without_space = True
            else:
                self.note = []
                self.note_field = "caller"
            return
        if self.note is not None:
            if name.startswith("f") or name.startswith("x"):
                self.note_field = name if not closing else "ft"
            elif name == "ref":
                self.in_ref = not closing
            return

        if closing:
            if name in CHAR_STYLES and CHAR_STYLES[name] in self.styles:
                # Remove the most recent occurrence.
                idx = len(self.styles) - 1 - self.styles[::-1].index(CHAR_STYLES[name])
                self.styles.pop(idx)
            elif name == "w":
                self.in_word = False
            elif name == "ref":
                self.in_ref = False
            return

        self.skip_text = False
        if name in SKIP:
            if name == "toc2":
                # Capture the short book name.
                end = self.text.find("\n", after)
                self.book.name = self.text[after:end].strip() or self.book.name
            self.skip_text = True
            self.block = None
            self.fragment = None
            return
        if name == "c":
            end = self.text.find("\n", after)
            num = re.match(r"\s*(\d+)", self.text[after:end])
            self.chapter = int(num.group(1))
            self.verse = 0
            self.block = None
            self.fragment = None
            self.styles = [s for s in self.styles if s == "r"]  # red letters can run on
            self.skip_text = True  # the chapter number itself
            return
        if name == "v":
            num = re.match(r"\s*(\d+)(?:-(\d+))?[^\s]*\s?", self.text[after:])
            self.verse = int(num.group(1))
            # "\\v 12-13": one passage for two verses (the 和合本 has dozens). The text is keyed to
            # the first; the range is kept so both verses' keys resolve to it.
            if num.group(2) and int(num.group(2)) > self.verse:
                self.book.ranges[(self.chapter, self.verse)] = int(num.group(2))
            self.consumed = num.end()
            self.start_fragment(numbered=True)
            self._skip_chars = num.end()
            return
        if name in HEADINGS:
            self.start_block(HEADINGS[name])
            return
        if name == "b":
            self.start_block("b")
            return
        if name in TEXT_BLOCKS:
            self.start_block(TEXT_BLOCKS[name])
            return
        if name in CHAR_STYLES:
            self.styles.append(CHAR_STYLES[name])
            return
        if name == "w":
            self.in_word = True
            return
        if name == "ref":
            self.in_ref = True
            return
        # Unknown character markers (tl, it, qs, bk, ...) just pass their text through.

    def finish(self):
        # Drop empty fragments/blocks and trailing spaces.
        for chapter, blocks in self.book.chapters.items():
            kept = []
            for block in blocks:
                if "f" in block:
                    for frag in block["f"]:
                        stripped = frag["t"].rstrip()
                        cut = len(frag["t"]) - len(stripped)
                        frag["t"] = stripped
                        if cut and "s" in frag:
                            frag["s"] = [[s, min(l, len(stripped) - s), st] for s, l, st in frag["s"] if s < len(stripped)]
                        if "fn" in frag:
                            frag["fn"] = [[min(p, len(stripped)), n] for p, n in frag["fn"]]
                    block["f"] = [f for f in block["f"] if f["t"] or f.get("n") or f.get("fn")]
                    if not block["f"]:
                        continue
                elif "t" in block:
                    block["t"] = block["t"].strip()
                    if not block["t"]:
                        continue
                kept.append(block)
            # Collapse stanza breaks that lead or repeat.
            out = []
            for block in kept:
                if block["k"] == "b" and (not out or out[-1]["k"] == "b"):
                    continue
                out.append(block)
            while out and out[-1]["k"] == "b":
                out.pop()
            self.book.chapters[chapter] = out
        for key, entry in self.book.verses.items():
            stripped = entry["t"].rstrip()
            entry["t"] = stripped
            entry["s"] = [[s, min(l, len(stripped) - s), st] for s, l, st in entry["s"] if s < len(stripped)]


def strip_verse_prefix(parser_cls):
    """Wrap add_text so the verse number after \\v is not treated as text."""
    original = parser_cls.add_text

    def add_text(self, raw):
        skip = getattr(self, "_skip_chars", 0)
        if skip:
            raw = raw[skip:]
            self._skip_chars = 0
        elif getattr(self, "_strip_one", False) and raw[:1].isspace():
            # USFM: the one space after an opening marker separates it from its text.
            raw = raw[1:]
        self._strip_one = False
        original(self, raw)

    parser_cls.add_text = add_text


strip_verse_prefix(Parser)


# Two places where the BSB's USFM omits a space that bereanbible.com's own translation tables
# contain. These are not footnote gaps (those are handled in the parser) — the space is simply
# missing. Found by aligning the interlinear tables against the built store. A correction that
# stops matching is reported, so a fixed upstream file leaves no silent no-op behind.
USFM_CORRECTIONS = [
    ("NUM", "his sons:This", "his sons: This"),
    ("JER", "\u201cevenif you", "\u201ceven if you"),
]


def apply_corrections(code, text, applied):
    for book, wrong, right in USFM_CORRECTIONS:
        if book == code and wrong in text:
            text = text.replace(wrong, right)
            applied.add((book, wrong))
    return text

def load_books(zip_path, report_corrections=True):
    books = {}
    applied = set()
    with zipfile.ZipFile(zip_path) as z:
        for name in z.namelist():
            if not name.lower().endswith((".usfm", ".sfm")):
                continue
            text = z.read(name).decode("utf-8-sig")
            code = re.match(r"\\id\s+(\S+)", text)
            if not code or code.group(1) not in BOOKS:
                continue
            text = apply_corrections(code.group(1), text, applied)
            book = Book(code.group(1))
            Parser(text).parse(book)
            books[book.code] = book
    missing = [c for c in BOOKS if c not in books]
    if missing:
        raise SystemExit(f"{zip_path}: missing books {missing}")
    for book, wrong, _ in USFM_CORRECTIONS if report_corrections else ():
        if (book, wrong) not in applied:
            print(f"  note: unused correction for {book}: {wrong!r} — upstream may have fixed it")
    return books


_BOOK_CACHE = {}


def books_for(tid):
    if tid not in _BOOK_CACHE:
        t = next(t for t in TRANSLATIONS if t["id"] == tid)
        # The corrections are for the BSB's USFM; only its load says whether they still apply.
        _BOOK_CACHE[tid] = load_books(os.path.join(SOURCE_DIR, t["zip"]), report_corrections=tid == "BSB")
    return _BOOK_CACHE[tid]


WORD = re.compile(r"[\w’']+")


def word_tokens(text):
    return [(m.start(), m.end(), m.group(0).lower().strip("’'")) for m in WORD.finditer(text)]


def red_mask(text, spans):
    covered = [False] * len(text)
    for start, length, *_ in spans:
        for i in range(start, min(start + length, len(text))):
            covered[i] = True
    return [sum(covered[s:e]) * 2 > (e - s) for s, e, _ in word_tokens(text)]


def fragments_of(book, chapter, verse):
    return [f for b in book.chapters.get(chapter, []) for f in b.get("f", []) if f["v"] == verse and f["t"]]


def borrow_red_letters(book, source):
    """Project the source translation's words of Christ onto this book, word by word."""
    import difflib

    aligned = skipped = 0
    for (chapter, verse), src in source.verses.items():
        if not src["s"] or (chapter, verse) not in book.verses:
            continue
        entry = book.verses[(chapter, verse)]
        src_tokens = word_tokens(src["t"])
        src_red = red_mask(src["t"], src["s"])
        dst_tokens = word_tokens(entry["t"])
        dst_red = [False] * len(dst_tokens)
        ops = difflib.SequenceMatcher(None, [t[2] for t in src_tokens], [t[2] for t in dst_tokens], autojunk=False)
        for tag, i1, i2, j1, j2 in ops.get_opcodes():
            if tag == "equal":
                for k in range(j2 - j1):
                    dst_red[j1 + k] = src_red[i1 + k]
            elif tag == "replace":
                red = sum(src_red[i1:i2]) * 2 >= (i2 - i1)
                for j in range(j1, j2):
                    dst_red[j] = red
            elif tag == "insert":
                before = src_red[i1 - 1] if i1 > 0 else False
                after = src_red[i1] if i1 < len(src_red) else before
                for j in range(j1, j2):
                    dst_red[j] = before and after or (before if i1 == len(src_red) else False) or (after and i1 == 0)
        # Character spans: run from the first red word to the end of the last one,
        # carrying attached punctuation (”, ?, .) along.
        spans = []
        text = entry["t"]
        j = 0
        while j < len(dst_tokens):
            if not dst_red[j]:
                j += 1
                continue
            k = j
            while k + 1 < len(dst_tokens) and dst_red[k + 1]:
                k += 1
            start = dst_tokens[j][0]
            while start > 0 and not text[start - 1].isspace() and not text[start - 1].isalnum():
                start -= 1
            end = dst_tokens[k][1]
            while end < len(text) and not text[end].isspace():
                end += 1
            spans.append([start, end - start, "r"])
            j = k + 1
        entry["s"] = spans
        # Map verse offsets onto the layout fragments (joined by single spaces).
        frags = fragments_of(book, chapter, verse)
        if " ".join(f["t"] for f in frags) != text:
            skipped += 1
            continue
        offset = 0
        for frag in frags:
            lo, hi = offset, offset + len(frag["t"])
            for start, length, _ in spans:
                a, b = max(start, lo), min(start + length, hi)
                if a < b:
                    append_span(frag.setdefault("s", []), a - lo, b - a, "r")
            if "s" in frag:
                frag["s"].sort()
            offset = hi + 1
        aligned += 1
    return aligned, skipped


def borrow_headings(book, source):
    """Insert the source's section headings before the same verse in this book."""
    placed = 0
    for chapter, src_blocks in source.chapters.items():
        pending = []
        anchors = []
        for block in src_blocks:
            if block["k"] in ("s1", "s2", "r"):
                pending.append(dict(block))
            elif pending:
                numbered = [f["v"] for f in block.get("f", []) if f.get("n")]
                if numbered:
                    anchors.append((numbered[0], pending))
                    pending = []
        blocks = book.chapters.get(chapter)
        if not blocks:
            continue
        for verse, headings in anchors:
            for bi, block in enumerate(blocks):
                frags = block.get("f", [])
                fi = next((i for i, f in enumerate(frags) if f["v"] == verse and f.get("n")), None)
                if fi is None:
                    continue
                if fi > 0:
                    tail = {"k": "p" if block["k"] in ("p", "m") else block["k"], "f": frags[fi:]}
                    block["f"] = frags[:fi]
                    blocks.insert(bi + 1, tail)
                    bi += 1
                insert_at = bi
                while insert_at > 0 and (blocks[insert_at - 1]["k"] == "b" or
                                         (blocks[insert_at - 1]["k"] == "d" and not any(f.get("n") for f in blocks[insert_at - 1]["f"]))):
                    insert_at -= 1
                blocks[insert_at:insert_at] = headings
                placed += len(headings)
                break
    return placed


def verse_slots(book):
    """A book's verses in order as (chapter, verse, last, length), with text only — an empty
    "\\v 16" whose words the translation prints as the next chapter's verse 1 is not a verse."""
    out = []
    for (chapter, verse) in sorted(book.verses):
        text = book.verses[(chapter, verse)]["t"].strip()
        if text:
            out.append((chapter, verse, book.ranges.get((chapter, verse), verse), len(text)))
    return out


def align(native, kjv, ratio, band=40):
    """Pairs native verses with KJV verses by length (Gale & Church's sentence alignment, 1993):
    a verse is about as long in any language, give or take the language's own ratio, so two
    English verses merged into one show up as one verse twice the length. Moves: 1-1, 1-2 (a
    merge), 2-1 (a split — a psalm title before its first verse), and 1-0 / 0-1 at a price.
    A 1-1 pair that keeps its own number gets a token preference, so ties go to the obvious
    reading — only a token: over a run of verses a larger one added up to more than a skipped
    verse costs, and the alignment "resynced" French 1 Samuel 24:13-23 onto the wrong verses.

    native: [(chapter, verse, last, length)]; kjv: [(chapter, verse, length)].
    Only cells within `band` verses of the diagonal are considered — displacements between Bibles
    are a few verses, never dozens — which keeps a whole book cheap to align.

    Returns {(chapter, verse): (kjv_chapter, kjv_verse, kjv_last_chapter, kjv_last_verse)}."""
    import math

    avg = (sum(n[3] for n in native) + sum(k[2] * ratio for k in kjv)) / max(1, len(native) + len(kjv))
    smooth = 0.15 * avg

    def cost(a, b):
        return abs(math.log((a + smooth) / (b * ratio + smooth)))

    # A merge or split must cost more than length noise, so the verse counts decide how many
    # there are and the lengths only decide where. Cheaper, and Job's evenly sized lines of poetry
    # read as merges: Spanish Job 39:1 ("¿Cazarás tú la presa para el león?") landed on KJV 38:41
    # instead of 38:39, where a plain chapter-break shift puts it.
    MERGE = 2.0
    INF = float("inf")
    rows, cols = len(native), len(kjv)
    best = [[INF] * (cols + 1) for _ in range(rows + 1)]
    back = [[None] * (cols + 1) for _ in range(rows + 1)]
    best[0][0] = 0.0
    for i in range(rows + 1):
        centre = i * cols // max(1, rows)
        for j in range(max(0, centre - band), min(cols, centre + band) + 1):
            here = best[i][j]
            if here == INF:
                continue
            moves = []
            if i < rows and j < cols:
                same = (native[i][0], native[i][1]) == (kjv[j][0], kjv[j][1])
                moves.append((1, 1, cost(native[i][3], kjv[j][2]) - (0.05 if same else 0)))
            # One native verse over several KJV verses. Usually two; but a source can lose its
            # verse markers — the Reina-Valera's Job 39:30 holds the KJV's 39:27-40:5, nine verses.
            for width in range(2, 11):
                if i < rows and j + width <= cols:
                    moves.append((1, width, cost(native[i][3], sum(x[2] for x in kjv[j:j + width]))
                                  + MERGE * (width - 1)))
            for width in range(2, 4):
                if i + width <= rows and j < cols:
                    moves.append((width, 1, cost(sum(x[3] for x in native[i:i + width]), kjv[j][2])
                                  + MERGE * (width - 1)))
            if i < rows:
                moves.append((1, 0, 4.0))
            if j < cols:
                moves.append((0, 1, 4.0))
            for di, dj, c in moves:
                if here + c < best[i + di][j + dj]:
                    best[i + di][j + dj] = here + c
                    back[i + di][j + dj] = (di, dj)
    result, i, j = {}, rows, cols
    pending = []
    while i or j:
        di, dj = back[i][j]
        i, j = i - di, j - dj
        pending.append((i, j, di, dj))
    last_kjv = kjv[0] if kjv else None
    for i, j, di, dj in reversed(pending):
        if dj:
            last_kjv = kjv[j]
        for n in native[i:i + di]:
            if dj:
                first, final = kjv[j], kjv[j + dj - 1]
                result[(n[0], n[1])] = (first[0], first[1], final[0], final[1])
            elif last_kjv:
                result[(n[0], n[1])] = (last_kjv[0], last_kjv[1], last_kjv[0], last_kjv[1])
    return result


def kjv_map(book, kjv_book):
    """Maps a translation's verses onto the KJV's numbering, for one book.

    Returns ({(chapter, verse): (kjv_chapter, kjv_verse, kjv_last_chapter, kjv_last_verse)},
    [books or psalms aligned by text]).
    Chapters are compared by their highest verse number, not their verse count, so a verse a
    translation omits (the BSB's Matthew 17:21) is a gap, not a renumbering. In order:

    1. Same numbering: every verse maps to itself (a range "12-13" to KJV 12-13).
    2. Psalm titles numbered as verses (Louis Segond: "Psaume de David." is Psalm 3:1): one or
       two extra verses at the start fold onto the KJV's verse 1; the rest shift down.
    3. Anything else — chapter breaks in other places (the Hebrew numbering: French Exodus
       7:26-29 is English 8:1-4), verses split or
       merged without saying so (the Reina-Valera's Numbers 13) — is aligned by length (`align`)
       over the run of chapters that brings the numbering back into step. Those runs are returned
       for the build log. Checked against SWORD's independent Segond table: see the ledger.
    """
    slots = verse_slots(book)
    kjv = [(c, v, len(kjv_book.verses[(c, v)]["t"])) for (c, v) in sorted(kjv_book.verses)
           if kjv_book.verses[(c, v)]["t"].strip()]
    ratio = sum(s[3] for s in slots) / max(1, sum(k[2] for k in kjv))
    top, kjv_top = {}, {}
    for c, v, last, _ in slots:
        top[c] = max(top.get(c, 0), last)
    for c, v, _ in kjv:
        kjv_top[c] = max(kjv_top.get(c, 0), v)
    chapters = sorted(set(top) | set(kjv_top))
    native_in = {c: [s for s in slots if s[0] == c] for c in chapters}
    kjv_in = {c: [k for k in kjv if k[0] == c] for c in chapters}
    result, aligned = {}, []

    def positional(run):
        # Verse positions through the run, by number, so an omitted verse keeps its place.
        kjv_at, offset = {}, 0
        for ch in run:
            for c, v, _ in kjv_in[ch]:
                kjv_at[offset + v] = (c, v)
            offset += kjv_top.get(ch, 0)
        offset = 0
        for ch in run:
            for c, v, last, _ in native_in[ch]:
                first = kjv_at.get(offset + v)
                final = kjv_at.get(offset + last, first)
                if first:
                    result[(c, v)] = (first[0], first[1], final[0], final[1])
            offset += top.get(ch, 0)

    index = 0
    while index < len(chapters):
        c = chapters[index]
        n, k = top.get(c, 0), kjv_top.get(c, 0)
        if n == k:
            positional([c])
            index += 1
            continue
        if book.code == "PSA" and 0 < n - k <= 2:
            titles = n - k
            for s in native_in[c]:
                if s[1] <= titles:
                    result[(c, s[1])] = (c, 1, c, 1)
                else:
                    result[(c, s[1])] = (c, s[1] - titles, c, s[2] - titles)
            index += 1
            continue
        if book.code == "PSA":
            # Psalms is long and its differences are local: align this psalm with the next.
            run = chapters[index:index + 2]
            result.update(align([s for ch in run for s in native_in[ch]],
                                [x for ch in run for x in kjv_in[ch]], ratio))
            aligned.append(f"PSA {run[0]}")
            index += len(run)
            continue
        # Any other difference: align the whole book by its text. Cutting it into runs where the
        # numbering seems to come back into step was tried and was wrong exactly where it matters
        # — French 1 Samuel 20-24 splits one verse and moves two chapter breaks, and a run that
        # "balanced" at chapter 23 stole the KJV's 23:29 from French 24:1.
        result = align(slots, kjv, ratio)
        return result, [f"{book.code} (whole book)"]
    return result, aligned


def build(translation):
    books = books_for(translation["id"])
    if translation.get("red_from"):
        source = books_for(translation["red_from"])
        counts = [borrow_red_letters(book, source[code]) for code, book in books.items()]
        print(f"{translation['id']}: red letters aligned from {translation['red_from']} in "
              f"{sum(a for a, _ in counts)} verses ({sum(s for _, s in counts)} layouts unmatched)")
    if translation.get("headings_from"):
        source = books_for(translation["headings_from"])
        placed = sum(borrow_headings(book, source[code]) for code, book in books.items())
        print(f"{translation['id']}: {placed} section headings borrowed from {translation['headings_from']}")
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    out_path = os.path.join(OUTPUT_DIR, f"{translation['id']}.sqlite")
    fd, tmp = tempfile.mkstemp(suffix=".sqlite", dir=OUTPUT_DIR)
    os.close(fd)
    db = sqlite3.connect(tmp)
    db.executescript(
        """
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE books (book INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL, chapters INTEGER NOT NULL);
        CREATE TABLE chapters (book INTEGER NOT NULL, chapter INTEGER NOT NULL, verses INTEGER NOT NULL,
                               layout TEXT NOT NULL, PRIMARY KEY (book, chapter)) WITHOUT ROWID;
        CREATE TABLE verses (id INTEGER PRIMARY KEY, text TEXT NOT NULL, red TEXT);
        CREATE VIRTUAL TABLE verses_fts USING fts5(text, content='verses', content_rowid='id',
                                                  tokenize='unicode61 remove_diacritics 2');
        CREATE TABLE kjv_map (id INTEGER PRIMARY KEY, kjv INTEGER NOT NULL, kjv_last INTEGER NOT NULL);
        CREATE INDEX kjv_map_kjv ON kjv_map (kjv);
        """
    )
    for key in ("id", "name", "abbreviation", "copyright", "license", "source"):
        db.execute("INSERT INTO meta VALUES (?, ?)", (key, translation[key]))
    total = 0
    for ordinal, code in enumerate(BOOKS, start=1):
        book = books[code]
        chapter_count = len([c for c in book.chapters if c > 0])
        db.execute("INSERT INTO books VALUES (?, ?, ?, ?)", (ordinal, code, book.name, chapter_count))
        for chapter in sorted(c for c in book.chapters if c > 0):
            verse_count = max((v for (c, v) in book.verses if c == chapter), default=0)
            layout = json.dumps({"b": book.chapters[chapter]}, ensure_ascii=False, separators=(",", ":"))
            db.execute("INSERT INTO chapters VALUES (?, ?, ?, ?)", (ordinal, chapter, verse_count, layout))
        for (chapter, verse), entry in sorted(book.verses.items()):
            vid = ordinal * 1_000_000 + chapter * 1_000 + verse
            red = json.dumps([[s, l] for s, l, _ in entry["s"]]) if entry["s"] else None
            db.execute("INSERT INTO verses VALUES (?, ?, ?)", (vid, entry["t"], red))
            total += 1
    if translation["id"] != "KJV":
        kjv_books = books_for("KJV")
        mapped, unsure_all = 0, []
        for ordinal, code in enumerate(BOOKS, start=1):
            mapping, aligned = kjv_map(books[code], kjv_books[code])
            unsure_all += aligned
            for (c, v), (kc, kv, lc, lv) in mapping.items():
                # Identity rows are left out — except a range, whose row is how a mark on the
                # KJV's 13:13 finds the 和合本's "12-13".
                if (c, v) == (kc, kv) == (lc, lv):
                    continue
                db.execute("INSERT INTO kjv_map VALUES (?, ?, ?)",
                           (ordinal * 1_000_000 + c * 1_000 + v,
                            ordinal * 1_000_000 + kc * 1_000 + kv,
                            ordinal * 1_000_000 + lc * 1_000 + lv))
                mapped += 1
        if mapped or unsure_all:
            print(f"{translation['id']}: {mapped} verses numbered differently from the KJV"
                  + (f"; aligned by text in {len(unsure_all)}: {', '.join(unsure_all)}" if unsure_all else ""))
    db.execute("INSERT INTO verses_fts(verses_fts) VALUES ('rebuild')")
    db.execute("INSERT INTO verses_fts(verses_fts) VALUES ('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, out_path)
    print(f"{translation['id']}: {total} verses -> {os.path.relpath(out_path, ROOT)} ({os.path.getsize(out_path) / 1e6:.1f} MB)")
    return out_path


def verse(db, book, chapter, v):
    vid = (BOOKS.index(book) + 1) * 1_000_000 + chapter * 1_000 + v
    row = db.execute("SELECT text, red FROM verses WHERE id = ?", (vid,)).fetchone()
    return row


def check(paths):
    bsb = sqlite3.connect(paths["BSB"])
    kjv = sqlite3.connect(paths["KJV"])
    for db, expected in ((bsb, 31086), (kjv, 31102)):
        n = db.execute("SELECT count(*) FROM verses").fetchone()[0]
        assert n == expected, (n, expected)
    text, red = verse(bsb, "JHN", 1, 1)
    assert text == "In the beginning was the Word, and the Word was with God, and the Word was God.", text
    assert red is None
    text, red = verse(bsb, "JHN", 1, 38)
    assert text.startswith("Jesus turned and saw them following. “What do you want?” He asked. They said"), text
    start, length = json.loads(red)[0]
    assert text[start:start + length] == "“What do you want?”", text[start:start + length]
    text, _ = verse(bsb, "JHN", 3, 16)
    assert text.startswith("For God so loved the world"), text
    text, red = verse(bsb, "REV", 2, 5)
    assert red and json.loads(red)[0][0] == 0, (text, red)  # red letters run across the verse boundary
    text, _ = verse(bsb, "PSA", 23, 1)
    assert text == "The LORD is my shepherd; I shall not want.", text
    text, red = verse(kjv, "JHN", 11, 35)
    assert text == "Jesus wept.", text
    text, _ = verse(kjv, "PSA", 1, 2)
    assert text.startswith("But his delight is in the law of the LORD"), text
    assert "|" not in text and "strong" not in text
    layout = json.loads(bsb.execute("SELECT layout FROM chapters WHERE book = 19 AND chapter = 23").fetchone()[0])
    kinds = [b["k"] for b in layout["b"]]
    assert kinds[:3] == ["s1", "r", "d"], kinds
    assert "q1" in kinds and "q2" in kinds, kinds
    hits = bsb.execute("SELECT count(*) FROM verses_fts WHERE verses_fts MATCH 'shepherd'").fetchone()[0]
    assert hits > 50, hits
    leaks = bsb.execute("SELECT count(*) FROM verses WHERE text LIKE '%\\%' ESCAPE '|' OR text LIKE '%|%'").fetchone()[0]
    assert leaks == 0, f"{leaks} verses leak USFM markup"
    leaks = kjv.execute("SELECT count(*) FROM verses WHERE text LIKE '%\\%' ESCAPE '|' OR text LIKE '%|%'").fetchone()[0]
    assert leaks == 0, f"{leaks} KJV verses leak USFM markup"
    asv = sqlite3.connect(paths["ASV"])
    # The ASV omits 16 verses (e.g. Matthew 17:21) and explains each in a footnote.
    assert asv.execute("SELECT count(*) FROM verses").fetchone()[0] == 31086
    text, red = verse(asv, "JHN", 11, 35)
    assert text == "Jesus wept." and red is None, (text, red)
    text, red = verse(asv, "JHN", 14, 6)
    spans = json.loads(red)
    said = "".join(text[s:s + l] for s, l in spans)
    assert said.startswith("I am the way"), (text, spans)
    assert not text[:spans[0][0]].strip().endswith("way"), (text, spans)
    text, red = verse(asv, "MAT", 5, 3)
    assert red and json.loads(red)[0][0] == 0, (text, red)
    layout = json.loads(asv.execute("SELECT layout FROM chapters WHERE book = 43 AND chapter = 3").fetchone()[0])
    assert layout["b"][0]["k"] == "s1", layout["b"][0]
    chapters = asv.execute("SELECT sum(chapters) FROM books").fetchone()[0]
    assert chapters == 1189, chapters
    leaks = asv.execute("SELECT count(*) FROM verses WHERE text LIKE '%\\%' ESCAPE '|' OR text LIKE '%|%'").fetchone()[0]
    assert leaks == 0, f"{leaks} ASV verses leak USFM markup"
    for t in TRANSLATIONS:
        if "locale" not in t:
            continue
        db = sqlite3.connect(paths[t["id"]])
        books = db.execute("SELECT count(*), sum(chapters) FROM books").fetchone()
        assert books == (66, 1189), (t["id"], books)
        n = db.execute("SELECT count(*) FROM verses").fetchone()[0]
        # Versifications differ: Segond numbers psalm titles as verse 1 and Malachi 4 as 3:19-24,
        # so the French count runs higher. Mapping those onto the KJV keys is Phase 2's job.
        assert 31_000 <= n <= 31_200, (t["id"], n)
        text, _ = verse(db, "JHN", 3, 16)
        assert text.startswith(t["john_3_16"]), (t["id"], text)
        leaks = db.execute("SELECT count(*) FROM verses WHERE text LIKE '%\\%' ESCAPE '|' OR text LIKE '%|%' "
                           "OR text LIKE '%<%'").fetchone()[0]
        assert leaks == 0, f"{leaks} {t['id']} verses leak markup"
        names = [r[0] for r in db.execute("SELECT name FROM books ORDER BY book")]
        assert not any(name in BOOKS for name in names), (t["id"], names)   # a name, not a USFM code
    # Numbering: the English Bibles keep the KJV's (a verse they omit is a gap, not a shift) ...
    for tid in ("ASV", "BSB"):
        rows = sqlite3.connect(paths[tid]).execute("SELECT count(*) FROM kjv_map").fetchone()[0]
        assert rows == 0, f"{tid} renumbered {rows} verses"

    # ... and the others map onto it at the places everyone who has compared them knows.
    def maps(tid, book, chapter, v, expected, last=None):
        db = sqlite3.connect(paths[tid])
        vid = (BOOKS.index(book) + 1) * 1_000_000 + chapter * 1_000 + v
        row = db.execute("SELECT kjv, kjv_last FROM kjv_map WHERE id = ?", (vid,)).fetchone() or (vid, vid)
        got = (row[0] // 1_000 % 1_000, row[0] % 1_000)
        assert got == expected, (tid, book, chapter, v, got, expected)
        if last:
            assert (row[1] // 1_000 % 1_000, row[1] % 1_000) == last, (tid, book, chapter, v, row, last)
    maps("LSG", "EXO", 7, 26, (8, 1))          # Hebrew chapter break
    maps("LSG", "PSA", 51, 3, (51, 1))         # two title verses
    maps("LSG", "PSA", 51, 12, (51, 10))       # "O Dieu! crée en moi un cœur pur"
    maps("LSG", "JOL", 2, 28, (2, 28))         # this edition keeps the KJV's chapters in Joel
    maps("LSG", "MAL", 4, 1, (4, 1))           # ... and in Malachi, unlike some printed Segonds
    maps("LSG", "1SA", 24, 1, (23, 29))        # En-Guédi
    maps("LSG", "2CH", 13, 23, (14, 1))        # "Abija se coucha avec ses pères" (SWORD has this wrong)
    maps("RVR1909", "NUM", 13, 1, (12, 16))    # an empty 12:16 whose words open chapter 13
    maps("RVR1909", "JOB", 39, 1, (38, 39))    # "¿Cazarás tú la presa para el león?"
    maps("RVR1909", "JOB", 39, 30, (39, 27), last=(40, 5))   # the source lost eight verse markers
    maps("RVR1909", "JON", 2, 1, (1, 17))
    maps("CUVS", "DEU", 13, 12, (13, 12), last=(13, 13))     # "\\v 12-13"
    maps("KRV", "3JN", 1, 15, (1, 14))
    for t in TRANSLATIONS:
        if "locale" not in t:
            continue
        db = sqlite3.connect(paths[t["id"]])
        # Every KJV verse a reader might have marked should land somewhere in this translation.
        covered = set()
        mapped = dict((i, (k, last)) for i, k, last in db.execute("SELECT id, kjv, kjv_last FROM kjv_map"))
        kjv_ids = [r[0] for r in sqlite3.connect(paths["KJV"]).execute("SELECT id FROM verses")]
        order = {vid: n for n, vid in enumerate(kjv_ids)}
        for (vid,) in db.execute("SELECT id FROM verses"):
            k, last = mapped.get(vid, (vid, vid))
            if k in order:
                covered.update(kjv_ids[order[k]:order.get(last, order[k]) + 1])
        uncovered = len(kjv_ids) - len(covered)
        assert uncovered < 40, f"{t['id']}: {uncovered} KJV verses have no verse in this translation"
    print("check: ok")


def main():
    paths = {t["id"]: build(t) for t in TRANSLATIONS}
    if "--check" in sys.argv:
        check(paths)


if __name__ == "__main__":
    main()
