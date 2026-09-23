#!/usr/bin/env python3
"""Convert a CrossWire SWORD Bible module into a USFM zip that build_bibles.py reads.

    pip install pysword
    python3 Tools/sword_to_usfm.py JapBungo    # Data/source/crosswire/JapBungo.zip -> Data/source/jpn-bungo_usfm.zip
    python3 Tools/sword_to_usfm.py KorRV       # Data/source/crosswire/KorRV.zip    -> Data/source/kor-rv_usfm.zip

Two of the big-8 locales have no suitable Bible on eBible.org — its only complete Japanese text
is marked as a draft, and its Korean is the archaic 1910 translation — so public domain texts come
from CrossWire instead (docs/localization.md).

What the modules carry, and what becomes of it:
- `<w gloss="かみ">神</w>` (JapBungo): furigana. The base text is kept, the reading dropped —
  the reader has no ruby layout, and an inline reading would read as doubled text.
- `<title>` before verse 1 (psalm superscriptions): `\\d`, as USFM marks them.
- `<hi type="x-small">` (Selah, セラ): kept as plain text.
- `<chapter>` / `<div>` milestones: structure only, dropped.
- A verse the text has merged into its neighbour (JapBungo's Exodus 7:25, 2 Samuel 19:25 and
  2 Chronicles 2:13 are printed with the verse before or after) is left out, not left blank.

Neither module marks paragraphs, so every verse is its own paragraph — the way Japanese and
Korean Bibles are commonly printed. Book names are the ones each translation itself uses; a SWORD
module stores none, so they are listed here.

The output must be a whole 66-book Protestant canon; anything else is an error.
"""

import html
import os
import re
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_DIR = os.path.join(ROOT, "Data", "source")

BOOKS = [
    "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI",
    "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER",
    "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC", "NAM", "HAB", "ZEP",
    "HAG", "ZEC", "MAL", "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL",
    "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE",
    "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
]

MODULES = {
    # 文語訳: the Meiji Old Testament (1887; this module follows the 1953 printing) with the Taishō
    # New Testament (1917). Chosen over the 口語訳 (1954/55), whose CrossWire module is missing whole
    # chapters and whose public-domain status is disputed (docs/localization.md). Book names as
    # this translation prints them — Japanese Wikisource's tables of contents.
    "JapBungo": {
        "out": "jpn-bungo_usfm.zip",
        "names": [
            "創世記", "出エジプト記", "レビ記", "民數紀略", "申命記", "ヨシュア記", "士師記", "ルツ記",
            "サムエル前書", "サムエル後書", "列王紀略上", "列王紀略下", "歴代志略上", "歴代志略下",
            "エズラ書", "ネヘミヤ記", "エステル書", "ヨブ記", "詩篇", "箴言", "傳道之書", "雅歌",
            "イザヤ書", "ヱレミヤ記", "エレミヤの哀歌", "エゼキエル書", "ダニエル書", "ホセア書",
            "ヨエル書", "アモス書", "オバデヤ書", "ヨナ書", "ミカ書", "ナホム書", "ハバクク書",
            "ゼパニヤ書", "ハガイ書", "ゼカリヤ書", "マラキ書", "マタイ傳福音書", "マルコ傳福音書",
            "ルカ傳福音書", "ヨハネ傳福音書", "使徒行傳", "ロマ人への書", "コリント人への前の書",
            "コリント人への後の書", "ガラテヤ人への書", "エペソ人への書", "ピリピ人への書",
            "コロサイ人への書", "テサロニケ人への前の書", "テサロニケ人への後の書", "テモテへの前の書",
            "テモテへの後の書", "テトスへの書", "ピレモンへの書", "ヘブル人への書", "ヤコブの書",
            "ペテロの前の書", "ペテロの後の書", "ヨハネの第一の書", "ヨハネの第二の書",
            "ヨハネの第三の書", "ユダの書", "ヨハネの默示録",
        ],
    },
    "KorRV": {
        "out": "kor-rv_usfm.zip",
        "names": [
            "창세기", "출애굽기", "레위기", "민수기", "신명기", "여호수아", "사사기", "룻기", "사무엘상",
            "사무엘하", "열왕기상", "열왕기하", "역대상", "역대하", "에스라", "느헤미야", "에스더", "욥기",
            "시편", "잠언", "전도서", "아가", "이사야", "예레미야", "예레미야애가", "에스겔", "다니엘",
            "호세아", "요엘", "아모스", "오바댜", "요나", "미가", "나훔", "하박국", "스바냐", "학개",
            "스가랴", "말라기", "마태복음", "마가복음", "누가복음", "요한복음", "사도행전", "로마서",
            "고린도전서", "고린도후서", "갈라디아서", "에베소서", "빌립보서", "골로새서", "데살로니가전서",
            "데살로니가후서", "디모데전서", "디모데후서", "디도서", "빌레몬서", "히브리서", "야고보서",
            "베드로전서", "베드로후서", "요한일서", "요한이서", "요한삼서", "유다서", "요한계시록",
        ],
    },
}

TITLE = re.compile(r"<title\b[^>]*>(.*?)</title>", re.S)
TAG = re.compile(r"<[^>]+>")


def plain(markup):
    """Text with every tag removed and furigana dropped (the reading is the `gloss` attribute,
    which vanishes with the tag; the base text between the tags stays).

    Backslashes are dropped too: JapBungo has two stray ones inside words (Genesis 26:8
    「アビメレク\\牖より」, Amos 9:11 「傾\\圯たる」), and in USFM a backslash starts a marker."""
    text = html.unescape(TAG.sub("", markup)).replace("\\", "")
    return re.sub(r"\s+", " ", text).strip()


def convert(module):
    from pysword.modules import SwordModules

    spec = MODULES[module]
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(os.path.join(SOURCE_DIR, "crosswire", f"{module}.zip")) as z:
            z.extractall(tmp)
        modules = SwordModules(tmp)
        modules.parse_modules()
        bible = modules.get_bible_from_module(module)
        books = bible.get_structure().get_books()
        canon = books.get("ot", []) + books.get("nt", [])
        if len(books.get("ot", [])) != 39 or len(books.get("nt", [])) != 27:
            raise SystemExit(f"{module}: not the 66-book canon "
                             f"({len(books.get('ot', []))} + {len(books.get('nt', []))})")

        out_path = os.path.join(SOURCE_DIR, spec["out"])
        verses = 0
        with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:
            for index, (code, book, name) in enumerate(zip(BOOKS, canon, spec["names"]), start=1):
                lines = [f"\\id {code} {module} (CrossWire SWORD module, converted)",
                         f"\\h {name}", f"\\toc1 {name}", f"\\toc2 {name}", f"\\mt1 {name}"]
                for chapter in range(1, book.num_chapters + 1):
                    lines.append(f"\\c {chapter}")
                    for verse in range(1, book.chapter_lengths[chapter - 1] + 1):
                        raw = bible.get(books=[book.osis_name.lower()], chapters=[chapter],
                                        verses=[verse], clean=False)
                        for title in TITLE.findall(raw):
                            lines.append(f"\\d {plain(title)}")
                        text = plain(TITLE.sub("", raw))
                        if not text:
                            continue   # a verse the versification has and this text doesn't
                        lines.append(f"\\p\n\\v {verse} {text}")
                        verses += 1
                # A fixed timestamp, so the same module always converts to the same bytes.
                entry = zipfile.ZipInfo(f"{index:02d}-{code}.usfm", date_time=(1980, 1, 1, 0, 0, 0))
                out.writestr(entry, "\n".join(lines) + "\n", compress_type=zipfile.ZIP_DEFLATED)
    print(f"{module}: {verses} verses -> {os.path.relpath(out_path, ROOT)}")


if __name__ == "__main__":
    for name in sys.argv[1:] or MODULES:
        convert(name)
