#!/usr/bin/env python3
"""Generate the book-name tables for both apps from one source:

    ScriptureAloneCore/Sources/ScriptureAloneCore/BookNamesTable.swift            (iOS, watch, widgets)
    android/shared/src/main/kotlin/.../data/canon/BookNamesTable.kt              (Android, Wear OS)

    python3 Tools/build_book_names.py

Book names for each big-8 language (docs/localization.md), and the abbreviations a reader of that
language types. Names come from the language's own Bible (the `books` table Tools/build_bibles.py
writes), so the interface calls a book what the text on screen does; abbreviations are each
language's standard short forms. Generated into source rather than shipped as a resource so the
widgets and the watch — separate bundles — have it too. One generator, two outputs: the Swift and
Kotlin tables are the same rows, so the two apps name and parse books identically.

Corrections to the sources are listed in NAME_FIXES, with the reason.
"""

import os
import sqlite3

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BIBLES = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles")
OUT = os.path.join(ROOT, "ScriptureAloneCore", "Sources", "ScriptureAloneCore", "BookNamesTable.swift")
KOTLIN_OUT = os.path.join(ROOT, "android", "shared", "src", "main", "kotlin", "com", "blainemiller",
                          "scripturealone", "data", "canon", "BookNamesTable.kt")

LANGUAGES = [
    ("zh-Hans", "CUVS"), ("ja", "BUNGO"), ("de", "LUT1912"), ("fr", "LSG"),
    ("es", "RVR1909"), ("ko", "KRV"), ("pt", "BLIVRE"), ("it", "RIV1927"),
]

# Typos in a source's book names. (language, book ordinal) -> name.
NAME_FIXES = {
    ("de", 13): "1. Chronik",   # deu1912 prints "1. Chonik"
    ("de", 14): "2. Chronik",
}

# Standard abbreviations, in canonical order (66 each).
ABBREVIATIONS = {
    "zh-Hans": "创 出 利 民 申 书 士 得 撒上 撒下 王上 王下 代上 代下 拉 尼 斯 伯 诗 箴 传 歌 赛 耶 哀 结 但 何 珥 摩 俄 拿 弥 鸿 哈 番 该 亚 玛 "
               "太 可 路 约 徒 罗 林前 林后 加 弗 腓 西 帖前 帖后 提前 提后 多 门 来 雅 彼前 彼后 约壹 约贰 约叁 犹 启",
    "ja": "創 出 レビ 民 申 ヨシュ 士 ルツ サム上 サム下 王上 王下 代上 代下 エズ ネヘ エス ヨブ 詩 箴 伝 雅 イザ エレ 哀 エゼ ダニ ホセ ヨエ アモ "
          "オバ ヨナ ミカ ナホ ハバ ゼパ ハガ ゼカ マラ マタ マコ ルカ ヨハ 使 ロマ 1コリ 2コリ ガラ エペ ピリ コロ 1テサ 2テサ 1テモ 2テモ テト "
          "ピレ ヘブ ヤコ 1ペテ 2ペテ 1ヨハ 2ヨハ 3ヨハ ユダ 黙",
    "de": "1Mo 2Mo 3Mo 4Mo 5Mo Jos Ri Rut 1Sam 2Sam 1Kön 2Kön 1Chr 2Chr Esr Neh Est Hiob Ps Spr Pred Hl Jes Jer Kla Hes Dan "
          "Hos Joel Am Obd Jona Mi Nah Hab Zef Hag Sach Mal Mt Mk Lk Joh Apg Röm 1Kor 2Kor Gal Eph Phil Kol 1Thess 2Thess "
          "1Tim 2Tim Tit Phlm Hebr Jak 1Petr 2Petr 1Joh 2Joh 3Joh Jud Offb",
    "fr": "Gn Ex Lv Nb Dt Jos Jg Rt 1S 2S 1R 2R 1Ch 2Ch Esd Né Est Jb Ps Pr Ec Ct Es Jr Lm Ez Dn Os Jl Am Ab Jon Mi Na Ha So "
          "Ag Za Ml Mt Mc Lc Jn Ac Rm 1Co 2Co Ga Ep Ph Col 1Th 2Th 1Tm 2Tm Tt Phm Hé Jc 1P 2P 1Jn 2Jn 3Jn Jd Ap",
    "es": "Gn Éx Lv Nm Dt Jos Jue Rt 1S 2S 1R 2R 1Cr 2Cr Esd Neh Est Job Sal Pr Ec Cnt Is Jer Lm Ez Dn Os Jl Am Abd Jon Mi "
          "Nah Hab Sof Hag Zac Mal Mt Mr Lc Jn Hch Ro 1Co 2Co Gá Ef Fil Col 1Ts 2Ts 1Ti 2Ti Tit Flm He Stg 1P 2P 1Jn 2Jn 3Jn Jud Ap",
    "ko": "창 출 레 민 신 수 삿 룻 삼상 삼하 왕상 왕하 대상 대하 스 느 에 욥 시 잠 전 아 사 렘 애 겔 단 호 욜 암 옵 욘 미 나 합 습 학 슥 말 "
          "마 막 눅 요 행 롬 고전 고후 갈 엡 빌 골 살전 살후 딤전 딤후 딛 몬 히 약 벧전 벧후 요일 요이 요삼 유 계",
    "pt": "Gn Ex Lv Nm Dt Js Jz Rt 1Sm 2Sm 1Rs 2Rs 1Cr 2Cr Ed Ne Et Jó Sl Pv Ec Ct Is Jr Lm Ez Dn Os Jl Am Ob Jn Mq Na Hc Sf "
          "Ag Zc Ml Mt Mc Lc Jo At Rm 1Co 2Co Gl Ef Fp Cl 1Ts 2Ts 1Tm 2Tm Tt Fm Hb Tg 1Pe 2Pe 1Jo 2Jo 3Jo Jd Ap",
    "it": "Gn Es Lv Nm Dt Gs Gdc Rt 1Sam 2Sam 1Re 2Re 1Cr 2Cr Esd Ne Est Gb Sal Pr Ec Ct Is Ger Lam Ez Dn Os Gl Am Abd Gio "
          "Mic Na Ab Sof Ag Zc Ml Mt Mc Lc Gv At Rm 1Cor 2Cor Gal Ef Fil Col 1Ts 2Ts 1Tm 2Tm Tt Fm Eb Gc 1Pt 2Pt 1Gv 2Gv 3Gv Gd Ap",
}

# Other spellings a reader types: the modern Japanese names (the 文語訳's are archaic — 民數紀略 is
# 民数記 today), names without the Reina-Valera's "San", common variants.
EXTRA_ALIASES = {
    "ja": {i + 1: [n] for i, n in enumerate(
        "創世記 出エジプト記 レビ記 民数記 申命記 ヨシュア記 士師記 ルツ記 サムエル記上 サムエル記下 列王紀上 列王紀下 歴代志上 歴代志下 "
        "エズラ記 ネヘミヤ記 エステル記 ヨブ記 詩篇 箴言 伝道の書 雅歌 イザヤ書 エレミヤ書 哀歌 エゼキエル書 ダニエル書 ホセア書 ヨエル書 "
        "アモス書 オバデヤ書 ヨナ書 ミカ書 ナホム書 ハバクク書 ゼパニヤ書 ハガイ書 ゼカリヤ書 マラキ書 マタイによる福音書 マルコによる福音書 "
        "ルカによる福音書 ヨハネによる福音書 使徒行伝 ローマ人への手紙 コリント人への第一の手紙 コリント人への第二の手紙 ガラテヤ人への手紙 "
        "エペソ人への手紙 ピリピ人への手紙 コロサイ人への手紙 テサロニケ人への第一の手紙 テサロニケ人への第二の手紙 テモテへの第一の手紙 "
        "テモテへの第二の手紙 テトスへの手紙 ピレモンへの手紙 ヘブル人への手紙 ヤコブの手紙 ペテロの第一の手紙 ペテロの第二の手紙 "
        "ヨハネの第一の手紙 ヨハネの第二の手紙 ヨハネの第三の手紙 ユダの手紙 ヨハネの黙示録".split())},
    "es": {40: ["Mateo"], 41: ["Marcos"], 42: ["Lucas"], 43: ["Juan"]},
}
EXTRA_ALIASES["ja"].update({40: EXTRA_ALIASES["ja"][40] + ["マタイ"], 41: EXTRA_ALIASES["ja"][41] + ["マルコ"],
                            42: EXTRA_ALIASES["ja"][42] + ["ルカ"], 43: EXTRA_ALIASES["ja"][43] + ["ヨハネ"]})


def swift_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def kotlin_string(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def load():
    """(language, [(name, abbreviation, aliases)] × 66) for each language, in LANGUAGES order."""
    tables = []
    for lang, tid in LANGUAGES:
        db = sqlite3.connect(os.path.join(BIBLES, f"{tid}.sqlite"))
        names = [r[0] for r in db.execute("SELECT name FROM books ORDER BY book")]
        assert len(names) == 66, (lang, len(names))
        names = [NAME_FIXES.get((lang, i + 1), n) for i, n in enumerate(names)]
        abbreviations = ABBREVIATIONS[lang].split()
        assert len(abbreviations) == 66, (lang, len(abbreviations))
        extras = EXTRA_ALIASES.get(lang, {})
        tables.append((lang, [(names[i], abbreviations[i], [abbreviations[i]] + extras.get(i + 1, []))
                              for i in range(66)]))
    return tables


def write(path, source):
    with open(path, "w", encoding="utf-8") as f:
        f.write(source)
    print(f"wrote {os.path.relpath(path, ROOT)}")


def main():
    tables = load()
    blocks = []
    for lang, table in tables:
        rows = [f"        ({swift_string(name)}, {swift_string(abbreviation)}, "
                f"[{', '.join(swift_string(a) for a in aliases)}]),"
                for name, abbreviation, aliases in table]
        blocks.append(f"    {swift_string(lang)}: [\n" + "\n".join(rows) + "\n    ],")
    source = (
        "// Generated by Tools/build_book_names.py — do not edit by hand.\n"
        "// Names from each language's Bible; standard abbreviations; see the script for corrections.\n\n"
        "extension BookNames {\n"
        "    /// (name, abbreviation, aliases) for each of the 66 books, per language.\n"
        "    static let table: [String: [(String, String, [String])]] = [\n"
        + "\n".join(blocks) + "\n    ]\n}\n"
    )
    write(OUT, source)

    # Kotlin: one function per language, so no single initializer approaches the JVM's method limit.
    functions = []
    for lang, table in tables:
        rows = [f"    BookNameRow({kotlin_string(name)}, {kotlin_string(abbreviation)}, "
                f"listOf({', '.join(kotlin_string(a) for a in aliases)})),"
                for name, abbreviation, aliases in table]
        ident = lang.replace("-", "")
        functions.append(f"private fun {ident}(): List<BookNameRow> = listOf(\n" + "\n".join(rows) + "\n)\n")
    entries = ", ".join(f"{kotlin_string(lang)} to {lang.replace('-', '')}()" for lang, _ in tables)
    kotlin = (
        "// Generated by Tools/build_book_names.py — do not edit by hand.\n"
        "// Names from each language's Bible; standard abbreviations; see the script for corrections.\n"
        "// The same rows as ScriptureAloneCore's BookNamesTable.swift.\n\n"
        "package com.blainemiller.scripturealone.data.canon\n\n"
        "/** (name, abbreviation, aliases) for each of the 66 books, per language. */\n"
        f"internal val BOOK_NAMES_TABLE: Map<String, List<BookNameRow>> by lazy {{ mapOf({entries}) }}\n\n"
        + "\n".join(functions)
    )
    write(KOTLIN_OUT, kotlin)


if __name__ == "__main__":
    main()
