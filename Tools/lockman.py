"""Reads The Lockman Foundation's coded text files (NASB 2020, NASB 1995) into build_bibles.Book.

    python3 Tools/build_bibles.py --licensed NASB2020 \\
        --lockman ~/secure/nasb/"NASB 2020(b+n-r-num)(08-12-26).txt" --out-dir ~/secure/nasb

Lockman delivers each edition as one UTF-8 file, one verse per line, in their "Short Codes for
LSB/NASB/LBLA/NBLA/AMP" set (05/08/2022), alongside a "Codes NC" Word document that defines them:

    <BN>…</BN>                 book name                <CN>CHAPTER #</CN>, <SN>PSALM #</SN>
    <V> <PM> <P> <A>           the kind of verse a line holds: prose, new paragraph, poetry, prose
    <C> <CC> <CP>              after poetry; chapter start in prose, poetry, a psalm
    {{bb::c}}v<T>              the verse marker (book 01–66, chapter, verse)
    <PN> <PO> <PR>             poetry line after the verse number, a further poetry line, back to prose
    <HL> <HLL> <LL> <LLL>      indented lines             </BR>  stanza break
    <SH>…</SH> <SHI>…</SHI>    subheads (italic, not Bible text); …I = inside a verse
    <SB>…</SB> <SBI>…</SBI>    "BOOK 1" in Psalms; the Song of Solomon's speaker headings
    <SS>…</SS>                 psalm superscription — Bible text, unnumbered
    <SF>…</SF>                 Hebrew letter heading (Psalm 119)
    { }                        italic: words supplied, not in the original
    <\\> </>                   small caps: LORD and GOD in the Old Testament (uppercased, style c);
                               Old Testament quotations in the New Testament (style k)
    <RS> </RS>                 words of Christ             <B> </B>  bold paragraph letter
    <N1>…<N9> <NA> <NB> <NC>   note superiors              <,>       superior comma
    <$F<FN><FNC>c<FNV>v</FN>…$E>   footnote (the <FN> chapter:verse prefix is dropped)
    <FA> <R[A-R]>              minimum-note flag, cross-reference letters (no references supplied)
    <LB> <LE>                  the letter before carries a macron: ā, Ē
    +“ +‘                      continuing quote: verse format only, removed in a paragraph setting
    -“ -‘                      optional continuing quote before poetry
    --                         em dash
    *                          a Greek historical present rendered with an English past tense

THE LICENCE FORBIDS GIVING THE TEXT TO ANY AI SYSTEM. Everything this module prints — progress,
warnings, errors — names a verse reference, a code or a count, never the words of the text, so its
output is safe to paste anywhere. Keep it that way: never put text into a message or an exception.
"""

import collections
import os
import re
import sqlite3
import unicodedata
import zipfile

from build_bibles import BOOKS, Book, Parser, append_span

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAMES_FROM = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles", "ASV.sqlite")

VERSE = re.compile(r"\{\{(\d+)::(\d+)\}\}(\d+)(?:<T>|\^)?")
TOKEN = re.compile(r"\{\{\d+::\d+\}\}\d+(?:<T>|\^)?|<\$F|\$E>|<[^<>\s]{1,6}>|[{}]")
MACRON = re.compile(r"(.)<L[BE]>")
CONTINUING = re.compile(r"^(\s*)((?:[+-][“‘])+)")
# Characters that only ever belong to codes. Left in the text, they mean a code was not understood.
STRAY = re.compile(r"[<>$\\{}=%@¶|^~_+]")

# Codes that only mark a footnote's superior letter, cross-reference letters with no references in
# this delivery, or the minimum-note flag: the note itself is what the reader gets.
SILENT = re.compile(r"<(?:N[0-9]|N[ABC]|,|FA|R[A-R]|B|/B|FNC|FNV|/PM|/V|/P|/A|/C|/CC|/CP)>")
LINE_KINDS = {"<V>", "<PM>", "<P>", "<A>", "<C>", "<CC>", "<CP>"}
HEADINGS = {"<SH>": "s1", "<SHI>": "s2", "<SB>": "s2", "<SBI>": "s2", "<SF>": "qa"}
LABELS = {"<BN>", "<CN>", "<SN>"}
PROSE = {"p", "m", "pmo", "li1", "li2"}


class LockmanFormatError(Exception):
    """Raised with a reference and a code name. Never with text."""


def read(path):
    """The coded text from a .txt file, or the one .txt inside a Lockman zip."""
    path = os.path.expanduser(path)
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            texts = [n for n in z.namelist() if n.lower().endswith(".txt")]
            if len(texts) != 1:
                raise LockmanFormatError(f"{path}: expected one .txt in the zip, found {len(texts)}")
            data = z.read(texts[0])
    else:
        with open(path, "rb") as handle:
            data = handle.read()
    return data.decode("utf-8-sig")


def book_names():
    with sqlite3.connect(NAMES_FROM) as db:
        return {code: name for code, name in db.execute("SELECT code, name FROM books")}


def macron(match):
    return unicodedata.normalize("NFC", match.group(1) + "̄")


class LockmanParser:
    def __init__(self, markers=None, names=None):
        # markers: {"*": "explanation"} — a character in the text that becomes a labelled footnote.
        self.markers = markers or {}
        self.names = names or {}
        self.books = {}
        self.book = None
        self.chapter = 0
        self.verse = 0
        self.poetry = False
        self.block = None
        self.fragment = None
        self.next_kind = "p"
        self.pending_number = False
        self.line_start = False
        self.italic = 0
        self.caps = 0
        self.red = False
        self.note = None          # list of strings while inside <$F … $E>
        self.note_prefix = False  # inside <FN> … </FN>
        self.collect = None       # (code, [strings]) for headings and labels
        self.d_block = False      # inside <SS> … </SS>
        self.verse_gap = False    # the verse continues in a new block: separate with a space
        self.held = []            # headings waiting for the next block: they belong where text resumes
        self.first_token = False
        self.stats = collections.Counter()
        self.warnings = collections.defaultdict(list)

    # -- references and problems ---------------------------------------------
    def ref(self):
        code = self.book.code if self.book else "(before the first book)"
        return f"{code} {self.chapter}:{self.verse}"

    def warn(self, kind):
        self.warnings[kind].append(self.ref())

    def fail(self, why):
        raise LockmanFormatError(f"{self.ref()}: {why}")

    # -- blocks ---------------------------------------------------------------
    def blocks(self):
        return self.book.chapters.setdefault(self.chapter, [])

    def new_block(self, kind):
        self.next_kind = kind
        self.block = None
        self.fragment = None
        if self.verse:
            self.verse_gap = True

    def flush_held(self):
        self.blocks().extend(self.held)
        self.held = []

    def stanza_break(self):
        self.flush_held()
        self.blocks().append({"k": "b"})
        self.new_block("q1")

    def ensure_fragment(self):
        if self.fragment is not None:
            return self.fragment
        if self.block is None:
            self.flush_held()
            self.block = {"k": "d" if self.d_block else self.next_kind, "f": []}
            self.blocks().append(self.block)
        self.fragment = {"v": self.verse, "t": ""}
        if self.pending_number and not self.d_block:
            self.fragment["n"] = 1
            self.pending_number = False
        self.block["f"].append(self.fragment)
        return self.fragment

    # -- driver ---------------------------------------------------------------
    def parse(self, text):
        lines = text.splitlines()
        started = False
        for number, line in enumerate(lines, start=1):
            if not started:
                if "<BN>" not in line:
                    self.stats["header lines skipped"] += 1
                    continue
                started = True
            self.line_number = number
            self.parse_line(line)
        if self.book is None:
            raise LockmanFormatError("no <BN> book name: is this a Lockman coded text file?")
        self.close_book()
        missing = [c for c in BOOKS if c not in self.books]
        if missing:
            raise LockmanFormatError(f"books missing: {missing}")
        return self.books

    def parse_line(self, line):
        line = MACRON.sub(macron, line.replace("--", "—").replace("\t", " "))
        if self.collect is None and self.note is None:
            self.line_start = True
        pos = 0
        self.first_token = True
        for m in TOKEN.finditer(line):
            if m.start() > pos:
                self.text(line[pos:m.start()])
                self.first_token = False
            self.code(m.group(0))
            self.first_token = False
            pos = m.end()
        if pos < len(line):
            self.text(line[pos:])
        if self.note is not None:
            self.warn("footnote runs past the end of its line")
        if self.collect is not None:
            self.warn(f"{self.collect[0]} runs past the end of its line")

    # -- codes ----------------------------------------------------------------
    def code(self, token):
        verse = VERSE.fullmatch(token)
        if verse:
            return self.verse_marker(*(int(g) for g in verse.groups()))

        # Inside a footnote only its own codes matter; its styling stays inside it.
        if self.note is not None:
            if token == "$E>":
                return self.close_note()
            if token == "<FN>":
                self.note_prefix = True
            elif token == "</FN>":
                self.note_prefix = False
            elif token == "<$F":
                self.fail("a footnote opens inside a footnote")
            return

        if token == "<$F":
            self.note, self.note_prefix = [], False
            return
        if token == "$E>":
            self.warn("$E> with no open footnote")
            return

        if self.collect is not None:
            opener = self.collect[0]
            if token == "</" + opener[1:]:
                return self.close_collect()
            if token in ("{", "}", "<\\>", "</>") or SILENT.fullmatch(token):
                return
            self.fail(f"{token} inside {opener}")

        if token in LABELS or token in HEADINGS:
            self.collect = (token, [])
            return
        if token == "<SS>":
            self.d_block = True
            self.block, self.fragment = None, None
            return
        if token == "</SS>":
            self.d_block = False
            self.new_block("q1" if self.poetry else "m")
            return

        if token == "{":
            self.italic += 1
        elif token == "}":
            if self.italic == 0:
                self.warn("} with no open {")
            self.italic = max(0, self.italic - 1)
        elif token == "<\\>":
            self.caps += 1
        elif token == "</>":
            if self.caps == 0:
                self.warn("</> with no open <\\>")
            self.caps = max(0, self.caps - 1)
        elif token == "<RS>":
            self.red = True
        elif token == "</RS>":
            self.red = False
        elif token in LINE_KINDS:
            self.line_kind(token)
        elif token in ("<PN>", "<PO>"):
            self.poetry = True
            self.new_block("q1")
            self.line_start = True
        elif token == "<PR>":
            self.poetry = False
            self.new_block("m")
        elif token in ("<HL>", "<LL>"):
            self.new_block("pmo" if token == "<HL>" else "li1")
        elif token in ("<HLL>", "<LLL>"):
            self.new_block("li1" if token == "<HLL>" else "li2")
        elif token == "</BR>":
            self.stanza_break()
        elif SILENT.fullmatch(token):
            self.stats[f"{token} codes dropped"] += 1
        else:
            self.fail(f"unknown code {token}")

    def line_kind(self, token):
        if token == "<PM>":
            # First on its line, <PM> opens a prose paragraph. After <P> (or inside a verse) in poetry
            # it is a stanza break; inside prose, a paragraph that starts mid-verse.
            if self.poetry and not self.first_token:
                self.stanza_break()
            else:
                self.poetry = False
                self.new_block("p")
            return
        if token in ("<P>", "<CC>", "<CP>"):
            self.poetry = True
            self.new_block("q1")
            return
        self.poetry = False
        if token == "<V>":
            # A prose verse runs on in the paragraph it follows.
            if self.block is not None and self.block["k"] in PROSE and not self.held:
                self.fragment = None
            else:
                self.new_block("m")
        else:  # <C> opens a chapter's first paragraph; <A> is prose after poetry, not a new paragraph
            self.new_block("p" if token == "<C>" else "m")

    def close_collect(self):
        opener, parts = self.collect
        self.collect = None
        text = re.sub(r"\s+", " ", "".join(parts)).strip()
        if opener == "<BN>":
            return self.open_book()
        if opener in ("<CN>", "<SN>"):
            number = re.search(r"\d+", text)
            if not number:
                self.fail(f"{opener} without a chapter number")
            return self.open_chapter(int(number.group(0)))
        kind = HEADINGS[opener]
        if opener == "<SB>" and re.fullmatch(r"BOOK \d+", text):
            kind = "ms"
        resume = self.block["k"] if self.block is not None and "f" in self.block and self.block["k"] != "d" else self.next_kind
        if text:
            self.held.append({"k": kind, "t": text})
            self.stats[f"{opener} headings"] += 1
        self.new_block(resume)

    def open_book(self):
        index = 0 if self.book is None else BOOKS.index(self.book.code) + 1
        if self.book is not None:
            self.close_book()
        if index >= len(BOOKS):
            self.fail("more than 66 books")
        self.book = Book(BOOKS[index])
        self.book.name = self.names.get(self.book.code, self.book.code)
        self.books[self.book.code] = self.book
        self.chapter, self.verse = 0, 0
        self.block, self.fragment = None, None

    def close_book(self):
        if self.held:
            self.warn("headings with no text after them at the end of a book")
            self.held = []
        if self.red:
            self.warn("red letters still open at the end of the book")
            self.red = False
        p = Parser("")
        p.book = self.book
        p.finish()

    def open_chapter(self, number):
        if self.book is None:
            self.fail("chapter before any book")
        if number != self.chapter + 1:
            self.fail(f"chapter label {number} does not follow chapter {self.chapter}")
        if self.italic or self.caps:
            self.warn("italic or small caps still open at the end of a chapter")
            self.italic = self.caps = 0
        self.chapter, self.verse = number, 0
        self.block, self.fragment = None, None
        self.next_kind = "p"
        self.poetry = False

    def verse_marker(self, book, chapter, verse):
        if self.note is not None or self.collect is not None:
            self.fail("verse marker inside a footnote or heading")
        expected = BOOKS.index(self.book.code) + 1
        if book != expected or chapter != self.chapter:
            raise LockmanFormatError(
                f"{self.ref()}: marker for book {book} chapter {chapter} inside book {expected} chapter {self.chapter}")
        if verse <= self.verse:
            self.fail(f"verse {verse} does not follow verse {self.verse}")
        self.verse = verse
        self.pending_number = True
        self.fragment = None
        self.verse_gap = False
        self.line_start = True
        self.stats["verses"] += 1

    # -- notes ----------------------------------------------------------------
    def close_note(self):
        body = re.sub(r"\s+", " ", "".join(self.note)).strip()
        self.note, self.note_prefix = None, False
        if not body:
            self.warn("empty footnote")
            return
        if self.collect is not None:
            # A note on a subhead: the subhead is not Bible text, and a heading block has no notes.
            self.stats["footnotes on subheads dropped"] += 1
            return
        if self.verse == 0 and not self.d_block:
            self.warn("footnote before the first verse, outside a superscription")
            return
        fragment = self.ensure_fragment()
        fragment.setdefault("fn", []).append([len(fragment["t"]), body])
        self.stats["footnotes"] += 1

    # -- text -----------------------------------------------------------------
    def text(self, raw):
        if self.note is not None:
            if not self.note_prefix:
                self.note.append(raw)
            return
        if self.collect is not None:
            self.collect[1].append(raw)
            return
        if not raw.strip():
            if self.fragment is not None and self.fragment["t"] and not self.fragment["t"].endswith(" "):
                self.append(" ", verse_text=" ")
            return
        if self.verse == 0 and not self.d_block:
            self.warn("text before the first verse, outside a heading")
            return

        layout_text = verse_text = raw
        if self.line_start:
            quotes = CONTINUING.match(raw)
            if quotes:
                lead, marks = quotes.group(1), quotes.group(2)
                kept = "".join(q for sign, q in zip(marks[::2], marks[1::2]) if sign == "-")
                layout_text = lead + kept + raw[quotes.end():]
                verse_text = lead + marks.replace("+", "").replace("-", "") + raw[quotes.end():]
                self.stats["continuing quotes removed (+)"] += marks.count("+")
                self.stats["continuing quotes kept (-)"] += marks.count("-")
            self.line_start = False
        self.append(layout_text, verse_text)

    def append(self, layout_text, verse_text):
        fragment = self.ensure_fragment()
        layout_text = re.sub(r"\s+", " ", layout_text)
        verse_text = re.sub(r"\s+", " ", verse_text)
        if not fragment["t"] or fragment["t"].endswith(" "):
            layout_text = layout_text.lstrip()
        caps = None
        if self.caps:
            # Small caps mean two things. In the Old Testament they set the divine name: LORD, GOD.
            # Those are uppercased, so the reader's LORD/GOD pattern draws them and plain text (search,
            # speech, sharing) keeps "LORD" apart from "Lord" however Lockman cased the letters. In the
            # New Testament they mark Old Testament quotations, drawn in small caps as style "k".
            if BOOKS.index(self.book.code) < 39:
                layout_text, verse_text, caps = layout_text.upper(), verse_text.upper(), "c"
                self.stats["Old Testament small caps uppercased (runs)"] += 1
            else:
                caps = "k"
        styles = (["i"] if self.italic else []) + ([caps] if caps else []) + (["r"] if self.red else [])
        for piece, marker in self.split_markers(layout_text):
            start = len(fragment["t"])
            fragment["t"] += piece
            for style in styles:
                append_span(fragment.setdefault("s", []), start, len(piece), style)
            if marker:
                fragment.setdefault("fn", []).append([len(fragment["t"]), self.markers[marker], marker])
                self.stats[f"{marker} markers made footnotes"] += 1
        if self.verse:
            self.add_verse_text("".join(c for c in verse_text if c not in self.markers))

    def split_markers(self, text):
        """(text, marker-or-None) pieces: each marker character becomes a footnote at its place."""
        if not self.markers:
            return [(text, None)]
        pieces, current = [], ""
        for ch in text:
            if ch in self.markers:
                pieces.append((current, ch))
                current = ""
            else:
                current += ch
        pieces.append((current, None))
        return pieces

    def add_verse_text(self, text):
        entry = self.book.verses.setdefault((self.chapter, self.verse), {"t": "", "s": []})
        if self.verse_gap:
            if entry["t"] and not entry["t"].endswith(" "):
                entry["t"] += " "
            self.verse_gap = False
        if not entry["t"] or entry["t"].endswith(" "):
            text = text.lstrip()
        start = len(entry["t"])
        entry["t"] += text
        if self.red:
            append_span(entry["s"], start, len(text), "r")


def check(books, expected_missing=()):
    """Problems a reader would see, as references. Never text."""
    problems = collections.defaultdict(list)
    for code, book in books.items():
        for (chapter, verse), entry in sorted(book.verses.items()):
            ref = f"{code} {chapter}:{verse}"
            if not entry["t"].strip():
                problems["empty verse"].append(ref)
            if STRAY.search(entry["t"]):
                problems["code characters left in the text"].append(ref)
        for chapter, blocks in book.chapters.items():
            for block in blocks:
                for fragment in block.get("f", []):
                    if STRAY.search(fragment["t"]):
                        problems["code characters left in the layout"].append(f"{code} {chapter}:{fragment['v']}")
                if "t" in block and STRAY.search(block["t"]):
                    problems["code characters left in a heading"].append(f"{code} {chapter}")
    return problems


def load(path, markers=None, expected_missing=()):
    """Parses a Lockman coded file into {code: Book}, printing a reference-only report."""
    parser = LockmanParser(markers=markers, names=book_names())
    books = parser.parse(read(path))
    for name, count in sorted(parser.stats.items()):
        print(f"  lockman: {count:7d}  {name}")
    for kind, refs in sorted(parser.warnings.items()):
        print(f"  lockman warning: {kind} ({len(refs)}): {', '.join(refs[:12])}{' …' if len(refs) > 12 else ''}")
    problems = check(books, expected_missing)
    for kind, refs in sorted(problems.items()):
        print(f"  lockman PROBLEM: {kind} ({len(refs)}): {', '.join(refs[:12])}{' …' if len(refs) > 12 else ''}")
    if problems:
        raise LockmanFormatError("the coded text did not parse cleanly; see the PROBLEM lines above (references only)")
    return books
