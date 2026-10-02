"""Tests for Tools/lockman.py against invented text in Lockman's codes.

    python3 -m unittest discover -s Tools/tests

Every word below is made up. The NASB text must never be used as a fixture: the licence forbids
giving it to an AI system, and these tests are written and run by one.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

import lockman  # noqa: E402
from build_bibles import BOOKS  # noqa: E402

HEADER = "Header line about the codes <N.> <R[A-R]> <\\\\\\> ¶ $ = %\r\n"


def book(index, body):
    """One book: its name line, then body lines, numbered as book `index` (1-based)."""
    return f"<BN>BOOK{index}</BN>\r\n" + body.replace("@", f"{index:02d}")


def filler(index):
    return book(index, "<CN>CHAPTER 1</CN>\r\n<C>{{@::1}}1<T>Zorp.\r\n")


def parse(*bodies, markers=None):
    """Parses the given books first, then filler for the rest of the 66."""
    text = HEADER + "".join(book(i + 1, b) for i, b in enumerate(bodies))
    text += "".join(filler(i) for i in range(len(bodies) + 1, len(BOOKS) + 1))
    parser = lockman.LockmanParser(markers=markers)
    return parser.parse(text), parser


def chapter(books, code="GEN", number=1):
    return books[code].chapters[number]


class LockmanTests(unittest.TestCase):
    def test_header_is_skipped_and_books_numbered_in_order(self):
        books, parser = parse()
        self.assertEqual(len(books), 66)
        self.assertEqual(parser.stats["header lines skipped"], 1)
        self.assertEqual(books["REV"].verses[(1, 1)]["t"], "Zorp.")

    def test_prose_paragraphs_and_running_verses(self):
        books, _ = parse(
            "<CN>CHAPTER 1</CN>\r\n"
            "<C>{{@::1}}1<T>Alpha blee.\r\n"
            "<V>{{@::1}}2<T>Beta blee.\r\n"
            "<PM>{{@::1}}3<T>Gamma blee.\r\n")
        blocks = chapter(books)
        self.assertEqual([b["k"] for b in blocks], ["p", "p"])
        self.assertEqual([f["v"] for f in blocks[0]["f"]], [1, 2])
        self.assertTrue(all(f.get("n") == 1 for b in blocks for f in b["f"]))
        self.assertEqual(books["GEN"].verses[(1, 3)]["t"], "Gamma blee.")

    def test_styles_italic_small_caps_red(self):
        books, _ = parse(
            "<CN>CHAPTER 1</CN>\r\n"
            "<C>{{@::1}}1<T>The <RS>L<\\>ord</> {is} vop.</RS>\r\n")
        frag = chapter(books)[0]["f"][0]
        self.assertEqual(frag["t"], "The LORD is vop.")
        spans = {(s, l, st) for s, l, st in frag["s"]}
        self.assertIn((5, 3, "c"), spans)
        self.assertIn((9, 2, "i"), spans)
        self.assertEqual(books["GEN"].verses[(1, 1)]["s"], [[4, 12, "r"]])

    def test_new_testament_small_caps_are_quotations(self):
        bodies = [""] * 39 + ["<CN>CHAPTER 1</CN>\r\n<C>{{@::1}}1<T>It says, <\\>Vop the zib</>.\r\n"]
        bodies[:39] = [filler(i + 1)[filler(i + 1).index("\r\n") + 2:] for i in range(39)]
        books, _ = parse(*bodies)
        frag = books["MAT"].chapters[1][0]["f"][0]
        self.assertEqual(frag["t"], "It says, Vop the zib.")
        self.assertEqual(frag["s"], [[9, 11, "k"]])

    def test_footnote_drops_prefix_superior_and_note_styling(self):
        books, _ = parse(
            "<CN>CHAPTER 1</CN>\r\n"
            "<C>{{@::1}}1<T>Word<N1><$F<FN><FNC>1<FNV>1</FN>Lit {quib} <\\>zap</>$E> after.\r\n")
        frag = chapter(books)[0]["f"][0]
        self.assertEqual(frag["t"], "Word after.")
        self.assertEqual(frag["fn"], [[4, "Lit quib zap"]])
        self.assertNotIn("s", frag)  # the note's italics and small caps stay in the note

    def test_note_straight_after_the_verse_number(self):
        books, _ = parse("<CN>CHAPTER 1</CN>\r\n<C>{{@::1}}1<T><N1><$F<FN><FNC>1<FNV>1</FN>Or blip$E>Vorn.\r\n")
        frag = chapter(books)[0]["f"][0]
        self.assertEqual((frag["n"], frag["t"], frag["fn"]), (1, "Vorn.", [[0, "Or blip"]]))

    def test_poetry_lines_and_stanza_breaks(self):
        books, _ = parse(
            "<CN>CHAPTER 1</CN>\r\n"
            "<CC>{{@::1}}1<T><PN>Line one,<PO>line two.\r\n"
            "<P>{{@::1}}2<T><PN>Line three.\r\n"
            "<P><PM>{{@::1}}3<T><PN>New stanza.\r\n"
            "<A>{{@::1}}4<T>Prose again.\r\n")
        kinds = [b["k"] for b in chapter(books)]
        self.assertEqual(kinds, ["q1", "q1", "q1", "b", "q1", "m"])
        self.assertEqual(books["GEN"].verses[(1, 1)]["t"], "Line one, line two.")
        second = chapter(books)[1]["f"][0]
        self.assertNotIn("n", second)  # verse 1's second line carries no number

    def test_continuing_quotes(self):
        books, parser = parse(
            "<CN>CHAPTER 1</CN>\r\n"
            "<C>{{@::1}}1<T>He said, “Vop.\r\n"
            "<V>{{@::1}}2<T>+“Zib.”\r\n"
            "<P>{{@::1}}3<T><PN>-“Quo.”\r\n")
        blocks = chapter(books)
        self.assertEqual(blocks[0]["f"][1]["t"], "Zib.”")              # paragraph: + quote removed
        self.assertEqual(books["GEN"].verses[(1, 2)]["t"], "“Zib.”")   # verse text keeps it, sign gone
        self.assertEqual(blocks[1]["f"][0]["t"], "“Quo.”")              # - quote kept
        self.assertEqual(parser.stats["continuing quotes removed (+)"], 1)

    def test_dash_macron_and_asterisk_marker(self):
        books, parser = parse(
            "<CN>CHAPTER 1</CN>\r\n"
            "<C>{{@::1}}1<T>TEKE<LE>L--and a<LB>b. He *vopped.\r\n",
            markers={"*": "explained"})
        frag = chapter(books)[0]["f"][0]
        self.assertEqual(frag["t"], "TEKĒL—and āb. He vopped.")
        self.assertEqual(frag["fn"], [[17, "explained", "*"]])
        self.assertEqual(books["GEN"].verses[(1, 1)]["t"], "TEKĒL—and āb. He vopped.")
        self.assertEqual(parser.stats["* markers made footnotes"], 1)

    def test_headings_superscription_and_psalm_books(self):
        books, _ = parse(
            "<CN>CHAPTER 1</CN>\r\n<SH>Head {one}</SH>\r\n<C>{{@::1}}1<T>Vop.\r\n"
            "<SB>BOOK 2</SB>\r\n"
            "<SN>PSALM 2</SN>\r\n"
            "<SS>A song<NA><$F<FN><FNC>2<FNV>1</FN>Or tune$E> of Zib.</SS>\r\n"
            "<CP>{{@::2}}1<T><PN>Sing.\r\n"
            "<SF>ALEPH</SF>\r\n"
            "<P>{{@::2}}2<T><PN>Again.\r\n")
        one = chapter(books)
        self.assertEqual(one[0], {"k": "s1", "t": "Head one"})
        two = chapter(books, number=2)
        self.assertEqual(two[0], {"k": "ms", "t": "BOOK 2"})            # held over into psalm 2
        self.assertEqual(two[1]["k"], "d")
        self.assertEqual(two[1]["f"][0]["t"], "A song of Zib.")
        self.assertEqual(two[1]["f"][0]["fn"], [[6, "Or tune"]])
        self.assertNotIn((2, 0), books["GEN"].verses)                 # superscription is unnumbered
        self.assertEqual([b["k"] for b in two[2:]], ["q1", "qa", "q1"])

    def test_heading_inside_a_verse(self):
        books, _ = parse(
            "<CN>CHAPTER 1</CN>\r\n"
            "<C>{{@::1}}1<T>Before<SBI>Speaker</SBI>after.\r\n")
        kinds = [b["k"] for b in chapter(books)]
        self.assertEqual(kinds, ["p", "s2", "p"])
        self.assertEqual(books["GEN"].verses[(1, 1)]["t"], "Before after.")

    def test_unknown_code_fails_with_reference_only(self):
        with self.assertRaises(lockman.LockmanFormatError) as raised:
            parse("<CN>CHAPTER 1</CN>\r\n<C>{{@::1}}1<T>Secretword <ZZ>more.\r\n")
        message = str(raised.exception)
        self.assertIn("GEN 1:1", message)
        self.assertIn("<ZZ>", message)
        self.assertNotIn("Secretword", message)

    def test_marker_for_the_wrong_book_fails(self):
        with self.assertRaises(lockman.LockmanFormatError):
            parse("<CN>CHAPTER 1</CN>\r\n<C>{{02::1}}1<T>Vop.\r\n")

    def test_check_reports_references_not_text(self):
        books, _ = parse("<CN>CHAPTER 1</CN>\r\n<C>{{@::1}}1<T>Secretword + stray.\r\n")
        problems = lockman.check(books)
        self.assertEqual(problems["code characters left in the text"], ["GEN 1:1"])
        self.assertNotIn("Secretword", repr(problems))


if __name__ == "__main__":
    unittest.main()
