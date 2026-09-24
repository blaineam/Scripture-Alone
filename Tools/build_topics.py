#!/usr/bin/env python3
"""Builds the Topics directory: the curated life themes and Nave's Topical Bible.

    python3 Tools/build_topics.py --fetch    # (re)download Nave's Topical Bible into Data/source/topics/
    python3 Tools/build_topics.py            # rebuild everything below
    python3 Tools/build_topics.py --check    # rebuild, then assert known values

Life themes — what someone may be going through, and passages that speak to it
  Data/topics/life-themes.json               the themes, in English, with their references
  Data/topics/translations/<lang>.json       each theme's name, description and search words in the
                                             other eight app languages

  Every reference is checked against the bundled BSB and KJV (which share the KJV versification the
  app stores), so a theme can never point at a verse a translation doesn't have. References only: the
  text is always drawn from the reader's own translation when a theme is shown.

  Writes:
  ScriptureAloneCore/Sources/ScriptureAloneCore/Resources/LifeThemes.json
      {"version": 1, "groups": [{"id", "name"}],
       "themes": [{"id", "group", "name", "description", "synonyms": [...],
                   "refs": ["50004006-50004007", ...], "nave": ["Care", ...]}]}
      English only, refs in the app's storage form (KJV verse keys). Read by `LifeThemeCatalog`, and
      copied into the Android app at build time (android/app/build.gradle.kts, syncBundledData).
  ScriptureAloneCore/Sources/ScriptureAloneCore/Resources/Localizable.xcstrings
      Group names, theme names, descriptions and search words (one comma-separated string per
      theme) in every language, keyed by their English. Entries this script owns carry a comment
      beginning "Life theme" and are rewritten on every run; everything else is left alone.
  android/app/src/main/res/values*/life_themes.xml
      The same, as string arrays in theme order, for the Android app. Generated — Levi fills only
      strings.xml, so these don't pass through it.
  docs/topics.md
      The themes with their passages, for people reviewing the choices.

Nave's Topical Bible — the A–Z directory for English readers
  Data/source/topics/Nave.zip                CrossWire's "Nave" SWORD module (zLD, TEI), public
                                             domain, from CCEL's edition of Orville J. Nave's
                                             Topical Bible (1896). 1.3 MB; pinned by SHA-256.

  Writes ScriptureAlone/Resources/Study/Topics.sqlite:
      meta     key, value — source, license, attribution
      topics   id INTEGER PRIMARY KEY, name TEXT, body BLOB
               `name` is title-cased ("FEAR OF GOD" -> "Fear of God"); ids follow the source's own
               alphabetical order. `body` is the topic's entries as compact JSON, raw DEFLATE
               (zlib wbits=-15, what Apple's `.zlib` and java.util.zip.Inflater(nowrap) read):
                 [[label, level, [start, end, start, end, ...], [see-also topic ids]], ...]
               `level` is 0 for a heading line, 1 for a line nested under the one before it; the
               references are KJV verse keys (book * 1_000_000 + chapter * 1_000 + verse), a whole
               chapter resolved to its verses.
"""

import difflib
import hashlib
import html
import json
import os
import re
import sqlite3
import struct
import sys
import tempfile
import urllib.request
import zipfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THEMES = os.path.join(ROOT, "Data", "topics", "life-themes.json")
TRANSLATIONS_DIR = os.path.join(ROOT, "Data", "topics", "translations")
BIBLES = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles")
CORE_RESOURCES = os.path.join(ROOT, "ScriptureAloneCore", "Sources", "ScriptureAloneCore", "Resources")
THEMES_OUT = os.path.join(CORE_RESOURCES, "LifeThemes.json")
CATALOG = os.path.join(CORE_RESOURCES, "Localizable.xcstrings")
ANDROID_RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
DOC_OUT = os.path.join(ROOT, "docs", "topics.md")
NAVE_ZIP = os.path.join(ROOT, "Data", "source", "topics", "Nave.zip")
NAVE_URL = "https://www.crosswire.org/ftpmirror/pub/sword/packages/rawzip/Nave.zip"
NAVE_SHA256 = "52d9b7cde04c2abb5187ae804bcb97d93c7344a1358539f50ebc178ac0c945f0"
TOPICS_OUT = os.path.join(ROOT, "ScriptureAlone", "Resources", "Study", "Topics.sqlite")

# The app's languages besides English, and the Android resource folder each lives in.
LANGUAGES = {"de": "values-de", "es": "values-es", "fr": "values-fr", "it": "values-it", "ja": "values-ja",
             "ko": "values-ko", "pt-BR": "values-b+pt+BR", "zh-Hans": "values-zh-rCN"}
MIN_REFS, MAX_REFS = 8, 20

# Same canonical order as ScriptureAloneCore's BookID and Tools/build_bibles.py.
BOOKS = [
    "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI",
    "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER",
    "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC", "NAM", "HAB", "ZEP",
    "HAG", "ZEC", "MAL", "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL",
    "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE",
    "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
]
OSIS = [
    "Gen", "Exod", "Lev", "Num", "Deut", "Josh", "Judg", "Ruth", "1Sam", "2Sam", "1Kgs", "2Kgs",
    "1Chr", "2Chr", "Ezra", "Neh", "Esth", "Job", "Ps", "Prov", "Eccl", "Song", "Isa", "Jer",
    "Lam", "Ezek", "Dan", "Hos", "Joel", "Amos", "Obad", "Jonah", "Mic", "Nah", "Hab", "Zeph",
    "Hag", "Zech", "Mal", "Matt", "Mark", "Luke", "John", "Acts", "Rom", "1Cor", "2Cor", "Gal",
    "Eph", "Phil", "Col", "1Thess", "2Thess", "1Tim", "2Tim", "Titus", "Phlm", "Heb", "Jas", "1Pet",
    "2Pet", "1John", "2John", "3John", "Jude", "Rev",
]
# English names for docs/topics.md.
BOOK_NAMES = [
    "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth", "1 Samuel",
    "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther", "Job",
    "Psalm", "Proverbs", "Ecclesiastes", "Song of Solomon", "Isaiah", "Jeremiah", "Lamentations",
    "Ezekiel", "Daniel", "Hosea", "Joel", "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk",
    "Zephaniah", "Haggai", "Zechariah", "Malachi", "Matthew", "Mark", "Luke", "John", "Acts", "Romans",
    "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians", "Philippians", "Colossians",
    "1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon", "Hebrews",
    "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation",
]


def fail(message):
    sys.exit(f"build_topics: {message}")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def key(book, chapter, verse):
    return book * 1_000_000 + chapter * 1_000 + verse


def verse_counts():
    """{(book, chapter): last verse} from the KJV, the versification every key is in."""
    db = sqlite3.connect(f"file:{os.path.join(BIBLES, 'KJV.sqlite')}?mode=ro", uri=True)
    counts = {}
    for (vid,) in db.execute("SELECT id FROM verses"):
        chapter = (vid // 1_000_000, vid // 1_000 % 1_000)
        counts[chapter] = max(counts.get(chapter, 0), vid % 1_000)
    db.close()
    return counts


# -- life themes ------------------------------------------------------------------

def parse_ref(text, where):
    """"PHP 4:6-7" -> (start key, end key). One chapter per reference keeps a theme's passages short."""
    m = re.fullmatch(r"([1-3A-Z]{3}) (\d+):(\d+)(?:-(\d+))?", text)
    if not m or m[1] not in BOOKS:
        fail(f"{where}: expected 'CODE chapter:verse[-verse]', got {text!r}")
    book, chapter, first = BOOKS.index(m[1]) + 1, int(m[2]), int(m[3])
    last = int(m[4] or first)
    if last < first:
        fail(f"{where}: {text} runs backwards")
    return key(book, chapter, first), key(book, chapter, last)


def check_refs(themes):
    """Every verse of every reference must exist in both bundled English Bibles."""
    for tid in ("BSB", "KJV"):
        db = sqlite3.connect(f"file:{os.path.join(BIBLES, tid + '.sqlite')}?mode=ro", uri=True)
        for theme in themes:
            for label, (start, end) in zip(theme["refs_text"], theme["keys"]):
                found = db.execute("SELECT COUNT(*) FROM verses WHERE id BETWEEN ? AND ?", (start, end)).fetchone()[0]
                if found != end - start + 1:
                    fail(f"{theme['id']}: {label} has {found} of {end - start + 1} verses in {tid}")
        db.close()


def load_themes():
    with open(THEMES, encoding="utf-8") as f:
        source = json.load(f)
    groups = source["groups"]
    group_ids = [g["id"] for g in groups]
    if len(set(group_ids)) != len(group_ids):
        fail("a group id appears twice")
    themes, seen = [], set()
    for theme in source["themes"]:
        tid = theme["id"]
        if not re.fullmatch(r"[a-z][a-z-]*", tid) or tid in seen:
            fail(f"theme id {tid!r} is malformed or repeated")
        seen.add(tid)
        if theme["group"] not in group_ids:
            fail(f"{tid}: unknown group {theme['group']!r}")
        for field in ("name", "description"):
            if not theme.get(field, "").strip():
                fail(f"{tid}: no {field}")
        if not theme.get("synonyms"):
            fail(f"{tid}: no search words")
        refs = theme["refs"]
        if not MIN_REFS <= len(refs) <= MAX_REFS:
            fail(f"{tid}: {len(refs)} passages; a theme has {MIN_REFS}–{MAX_REFS}")
        if len(set(refs)) != len(refs):
            fail(f"{tid}: lists a passage twice")
        keys = [parse_ref(r, tid) for r in refs]
        themes.append(dict(theme, refs_text=refs, keys=keys))
    # Themes listed by group, in the groups' order, so the directory reads as the file does.
    themes.sort(key=lambda t: group_ids.index(t["group"]))
    return groups, themes


def load_translations(groups, themes):
    result = {}
    for lang in LANGUAGES:
        path = os.path.join(TRANSLATIONS_DIR, f"{lang}.json")
        if not os.path.exists(path):
            fail(f"missing {os.path.relpath(path, ROOT)}")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        for g in groups:
            if not data.get("groups", {}).get(g["id"], "").strip():
                fail(f"{lang}: group {g['id']} is not translated")
        for t in themes:
            entry = data.get("themes", {}).get(t["id"])
            if not entry or not entry.get("name", "").strip() or not entry.get("description", "").strip() \
                    or not entry.get("synonyms"):
                fail(f"{lang}: theme {t['id']} is not fully translated")
            if any("," in s for s in entry["synonyms"]):
                fail(f"{lang}: {t['id']} has a comma inside a search word")
        result[lang] = data
    return result


def synonyms_line(words):
    """Search words as one catalog string. Commas separate them; nothing else may."""
    return ", ".join(w.strip() for w in words)


def write_themes_json(groups, themes, nave_names):
    for theme in themes:
        for name in theme.get("nave", []):
            if name not in nave_names:
                fail(f"{theme['id']}: Nave's has no topic {name!r}")
    payload = {
        "version": 1,
        "groups": [{"id": g["id"], "name": g["name"]} for g in groups],
        "themes": [{
            "id": t["id"],
            "group": t["group"],
            "name": t["name"],
            "description": t["description"],
            "synonyms": t["synonyms"],
            "refs": [f"{a}-{b}" for a, b in t["keys"]],
            "nave": [nave_names[n] for n in t.get("nave", [])],
        } for t in themes],
    }
    # One group or theme per line: small, and a change to one theme is a one-line diff.
    line = lambda value: json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    text = (f'{{"version":{payload["version"]},\n"groups":[\n'
            + ",\n".join(line(g) for g in payload["groups"]) + '\n],\n"themes":[\n'
            + ",\n".join(line(t) for t in payload["themes"]) + "\n]}\n")
    json.loads(text)
    write_text(THEMES_OUT, text)


COMMENT_PREFIX = "Life theme"


def catalog_entries(groups, themes, translations):
    """{English key: (comment, {lang: value})} for everything a theme puts in the catalog."""
    entries = {}

    def add(english, comment, values):
        if english in entries and entries[english][1] != values:
            fail(f"catalog key {english!r} is used twice with different translations")
        entries[english] = (comment, values)

    for g in groups:
        add(g["name"], f"{COMMENT_PREFIX} group: a heading in the Topics directory.",
            {lang: translations[lang]["groups"][g["id"]] for lang in LANGUAGES})
    for t in themes:
        tr = {lang: translations[lang]["themes"][t["id"]] for lang in LANGUAGES}
        add(t["name"], f"{COMMENT_PREFIX} name: a topic someone may be going through, shown as a heading in the Topics directory.",
            {lang: tr[lang]["name"] for lang in LANGUAGES})
        add(t["description"], f"{COMMENT_PREFIX} description: one line under “{t['name']}” in the Topics directory.",
            {lang: tr[lang]["description"] for lang in LANGUAGES})
        add(synonyms_line(t["synonyms"]),
            f"{COMMENT_PREFIX} search words for “{t['name']}”: never shown. Words someone might type when going "
            "through this, separated by commas — not a translation word for word.",
            {lang: synonyms_line(tr[lang]["synonyms"]) for lang in LANGUAGES})
    return entries


def update_catalog(entries):
    """Rewrites the entries this script owns and leaves the rest of the catalog as Xcode wrote it."""
    with open(CATALOG, encoding="utf-8") as f:
        original = f.read()
    catalog = json.loads(original)
    strings = {k: v for k, v in catalog["strings"].items()
               if not v.get("comment", "").startswith(COMMENT_PREFIX)}
    for english, (comment, values) in entries.items():
        if english in strings:
            fail(f"catalog already has {english!r} for something else")
        strings[english] = {
            "comment": comment,
            "extractionState": "manual",
            "localizations": {lang: {"stringUnit": {"state": "translated", "value": value}}
                              for lang, value in sorted(values.items())},
        }
    catalog["strings"] = dict(sorted(strings.items()))
    text = json.dumps(catalog, indent=2, separators=(",", " : "), ensure_ascii=False)
    # Xcode writes the catalog without a final newline; match it so a save in Xcode is not a diff.
    if original.endswith("\n"):
        text += "\n"
    write_text(CATALOG, text)


def android_escape(text):
    text = text.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace("'", "\\'").replace('"', '\\"')
    return "\\@" + text[1:] if text.startswith("@") else ("\\?" + text[1:] if text.startswith("?") else text)


def write_android(groups, themes, translations):
    def arrays(group_names, names, descriptions, synonyms):
        lines = [
            '<?xml version="1.0" encoding="utf-8"?>',
            "<!-- Generated by Tools/build_topics.py from Data/topics/ — edit those, not this. The life themes'",
            "     groups, names, descriptions and search words, in the order of LifeThemes.json. -->",
            "<resources>",
        ]
        for name, items in (("life_theme_groups", group_names), ("life_theme_names", names),
                            ("life_theme_descriptions", descriptions), ("life_theme_synonyms", synonyms)):
            lines.append(f'    <string-array name="{name}">')
            lines += [f"        <item>{android_escape(item)}</item>" for item in items]
            lines.append("    </string-array>")
        lines.append("</resources>")
        return "\n".join(lines) + "\n"

    write_text(os.path.join(ANDROID_RES, "values", "life_themes.xml"), arrays(
        [g["name"] for g in groups], [t["name"] for t in themes], [t["description"] for t in themes],
        [synonyms_line(t["synonyms"]) for t in themes]))
    for lang, folder in LANGUAGES.items():
        data = translations[lang]
        write_text(os.path.join(ANDROID_RES, folder, "life_themes.xml"), arrays(
            [data["groups"][g["id"]] for g in groups],
            [data["themes"][t["id"]]["name"] for t in themes],
            [data["themes"][t["id"]]["description"] for t in themes],
            [synonyms_line(data["themes"][t["id"]]["synonyms"]) for t in themes]))


def display_ref(start, end):
    book, chapter, first, last = start // 1_000_000, start // 1_000 % 1_000, start % 1_000, end % 1_000
    return f"{BOOK_NAMES[book - 1]} {chapter}:{first}" + (f"–{last}" if last != first else "")


def write_doc(groups, themes):
    lines = [
        "# Life themes",
        "",
        "The curated themes in the Topics directory: what someone may be going through, and passages",
        "that speak to it. Generated by `Tools/build_topics.py` from `Data/topics/life-themes.json` —",
        "edit that, not this. References only; the app shows each passage in the reader's own",
        "translation. The search words below are the English ones; each language has its own",
        "(`Data/topics/translations/`).",
        "",
        f"{len(themes)} themes, {sum(len(t['keys']) for t in themes)} passages.",
        "",
    ]
    for g in groups:
        lines += [f"## {g['name']}", ""]
        for t in (t for t in themes if t["group"] == g["id"]):
            lines += [f"### {t['name']}", "", f"{t['description']}  ", f"*Search words:* {', '.join(t['synonyms'])}", ""]
            lines += [f"- {display_ref(a, b)}" for a, b in t["keys"]]
            if t.get("nave"):
                lines += ["", f"*In Nave's Topical Bible:* {', '.join(t['nave'])}"]
            lines.append("")
    write_text(DOC_OUT, "\n".join(lines).rstrip("\n") + "\n")


# -- Nave's Topical Bible --------------------------------------------------------

def fetch_nave():
    os.makedirs(os.path.dirname(NAVE_ZIP), exist_ok=True)
    request = urllib.request.Request(NAVE_URL, headers={"User-Agent": "ScriptureAlone-build/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response, open(NAVE_ZIP, "wb") as f:
        f.write(response.read())
    digest = sha256(NAVE_ZIP)
    print(f"Nave.zip: {os.path.getsize(NAVE_ZIP) / 1e6:.1f} MB, sha256 {digest}")
    if digest != NAVE_SHA256:
        print("  (differs from the pinned NAVE_SHA256: review the change, then update the pin)")


def read_zld(archive):
    """The entries of a SWORD zLD lexicon, in its own (alphabetical) order: [(key, body)].

    dict.idx: (offset, size) into dict.dat per entry. dict.dat: the key, a newline, then (block,
    index in block). dict.zdx: (offset, size) into dict.zdt per block. A block is zlib data: a
    count, then (offset, size) per entry, then the entries — all little-endian uint32.
    """
    base = "modules/lexdict/zld/nave/dict"
    idx, dat, zdx, zdt = (archive.read(base + ext) for ext in (".idx", ".dat", ".zdx", ".zdt"))
    blocks = {}

    def block(number):
        if number not in blocks:
            offset, size = struct.unpack_from("<II", zdx, number * 8)
            raw = zlib.decompress(zdt[offset:offset + size])
            count = struct.unpack_from("<I", raw, 0)[0]
            spans = [struct.unpack_from("<II", raw, 4 + i * 8) for i in range(count)]
            blocks[number] = [raw[o:o + s] for o, s in spans]
        return blocks[number]

    entries = []
    for i in range(len(idx) // 8):
        offset, size = struct.unpack_from("<II", idx, i * 8)
        record = dat[offset:offset + size]
        newline = record.find(b"\n")
        name = record[:newline].rstrip(b"\r").decode("utf-8")
        rest = record[newline + 1:]
        if len(rest) < 8:
            continue
        number, index = struct.unpack_from("<II", rest, 0)
        entries.append((name, block(number)[index].rstrip(b"\0").decode("utf-8")))
    return entries


SMALL_WORDS = {"a", "an", "and", "as", "at", "by", "for", "from", "in", "into", "of", "on", "or", "the", "to", "with"}
ROMAN = re.compile(r"^(?:[IVX]+)$")


def title_case(name):
    """"FEAR OF GOD" -> "Fear of God"; "AGE, OLD" -> "Age, Old"; "JEROBOAM II" keeps its numeral."""
    words = name.replace("'", "’").split(" ")
    out = []
    for i, word in enumerate(words):
        lower = word.lower()
        if ROMAN.match(word) and i > 0:
            out.append(word)
        elif i > 0 and lower in SMALL_WORDS and not words[i - 1].endswith(","):
            out.append(lower)
        else:
            # Each part of a hyphenated or parenthesised word gets its capital: "Self-Control".
            out.append(re.sub(r"(^|[-(/])([a-z])", lambda m: m[1] + m[2].upper(), lower))
    return " ".join(out)


def osis_range(ref, counts):
    """An osisRef ("Exod.6.16-Exod.6.20", "1Chr.24", "Gen.1.1-Gen.2.3") as KJV keys, or None."""
    def point(text, end):
        parts = text.split(".")
        if parts[0] not in OSIS or not 2 <= len(parts) <= 3:
            return None
        book, chapter = OSIS.index(parts[0]) + 1, int(parts[1])
        last = counts.get((book, chapter))
        if last is None:
            return None
        verse = int(parts[2]) if len(parts) == 3 else (last if end else 1)
        return key(book, chapter, min(verse, last)) if 1 <= verse else None

    first, _, second = ref.partition("-")
    start = point(first, end=False)
    stop = point(second or first, end=True)
    if start is None or stop is None or stop < start:
        return None
    return start, stop


REF = re.compile(r'<ref osisRef="([^"]+)">.*?</ref>', re.S)
# A link's target stops at a comma the topic's own name has ("See <ref target="Nave:ABSTINENCE">
# ABSTINENCE</ref>, TOTAL"), so the capitals after it belong to the link.
SEE = re.compile(r'<ref target="Nave:([^"]+)">.*?</ref>((?:,\s*[A-Z][A-Z\'\- ]*[A-Z](?![a-z]))*)', re.S)


def clean_label(text):
    text = REF.sub(" ", text)
    text = SEE.sub(" ", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text).replace("→", " ")
    text = re.sub(r"\s+", " ", text).strip()
    # What a removed reference list leaves behind: runs of separators, empty brackets.
    text = re.sub(r"\(\s*[,;:]*\s*\)", "", text)
    text = re.sub(r"(\s*[,;:]\s*)+(?=[,;:]|$)", "", text)
    text = re.sub(r"^[\s,;:.]+|[\s,;:]+$", "", text)
    text = re.sub(r"\s+([,;:.)])", r"\1", text)
    text = re.sub(r"\s+", " ", text).strip()
    # Some headings are in capitals ("REMEDY FOR"); they read as the rest do.
    if len(text) > 1 and not any(c.islower() for c in text):
        text = text[0] + text[1:].lower()
    return text


def build_nave(counts):
    if not os.path.exists(NAVE_ZIP):
        fail(f"missing {os.path.relpath(NAVE_ZIP, ROOT)} — run with --fetch")
    if sha256(NAVE_ZIP) != NAVE_SHA256:
        fail("Nave.zip does not match NAVE_SHA256")
    with zipfile.ZipFile(NAVE_ZIP) as archive:
        raw = read_zld(archive)

    # The module lists one topic twice ("SIN"); its entries are merged under one name.
    order, bodies = [], {}
    for name, body in raw:
        if name not in bodies:
            order.append(name)
            bodies[name] = []
        bodies[name].append(body)
    ids = {name: i + 1 for i, name in enumerate(order)}
    upper = {name.upper(): name for name in order}
    stats = {"refs": 0, "dropped_refs": 0, "links": 0, "dropped_links": 0}

    def resolve(target, continuation):
        target = html.unescape(target).strip().upper()
        tail = re.sub(r"\s+", " ", continuation).strip()
        for candidate in ([target + tail] if tail else []) + [target]:
            if candidate in upper:
                return ids[upper[candidate]]
        # A link to "JESUS" means "JESUS, THE CHRIST", "WICKED" means "WICKED (PEOPLE)": the one topic
        # whose name goes on from it. Then singular for plural and back.
        extended = sorted((n for n in upper if re.match(re.escape(target) + r"(,| \(| AND )", n)), key=len)
        if len(extended) == 1 or (extended and target + "S" not in upper):
            return ids[upper[extended[0]]]
        for candidate in (target + "S", target + "ES", target[:-1] if target.endswith("S") else None):
            if candidate and candidate in upper:
                return ids[upper[candidate]]
        # The source has a few misspelt links ("AFFILICTION"); take a close match, or drop it.
        close = difflib.get_close_matches(target, list(upper), n=1, cutoff=0.88)
        return ids[upper[close[0]]] if close else None

    def entry(fragment, level):
        ranges = []
        for ref in REF.findall(fragment):
            r = osis_range(ref, counts)
            if r is None:
                stats["dropped_refs"] += 1
                continue
            stats["refs"] += 1
            # "1Co 7:32, 33" is one passage to a reader: a verse that follows on from the one
            # before, in the same chapter, extends it.
            if ranges and r[0] == ranges[-1] + 1 and r[0] // 1_000 == ranges[-1] // 1_000:
                ranges[-1] = r[1]
            else:
                ranges.extend(r)
        see = []
        for target, continuation in SEE.findall(fragment):
            topic = resolve(target, continuation)
            if topic is None:
                stats["dropped_links"] += 1
            elif topic not in see:
                stats["links"] += 1
                see.append(topic)
        label = clean_label(fragment)
        # "See X" is the link itself: no words of its own. One whose link didn't resolve says nothing.
        if re.match(r"(?i)(also )?see\b", label):
            if not see and not ranges:
                return None
            label = ""
        if not label and not ranges and not see:
            return None
        return [label, level, ranges, see]

    rows = []
    for name in order:
        entries = []
        for body in bodies[name]:
            definition = re.search(r"<def>(.*)</def>", body, re.S)
            text = definition[1] if definition else body
            for line in re.split(r"<lb/>", text):
                head, _, rest = line.partition("<list>")
                parent = entry(head, 0)
                if parent:
                    entries.append(parent)
                for item in re.findall(r"<item>(.*?)</item>", rest, re.S):
                    child = entry(item, 1 if parent else 0)
                    if child:
                        entries.append(child)
        if not entries:
            continue
        compact = json.dumps(entries, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        deflate = zlib.compressobj(9, zlib.DEFLATED, -15)
        rows.append((ids[name], title_case(name), deflate.compress(compact) + deflate.flush()))

    os.makedirs(os.path.dirname(TOPICS_OUT), exist_ok=True)
    fd, tmp = tempfile.mkstemp(suffix=".sqlite", dir=os.path.dirname(TOPICS_OUT))
    os.close(fd)
    os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript("""
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE topics (id INTEGER PRIMARY KEY, name TEXT NOT NULL, body BLOB NOT NULL);
    """)
    db.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("id", "nave"),
        ("name", "Nave’s Topical Bible"),
        ("author", "Orville J. Nave"),
        ("year", "1896"),
        ("license", "Public domain"),
        ("url", "https://ccel.org/ccel/nave/bible"),
        ("source", NAVE_URL),
        ("attribution", "Nave’s Topical Bible by Orville J. Nave (1896), public domain; "
                        "text from the CCEL edition via CrossWire’s SWORD module."),
    ])
    db.executemany("INSERT INTO topics VALUES (?, ?, ?)", rows)
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, TOPICS_OUT)
    print(f"Topics.sqlite: {len(rows)} topics, {stats['refs']} references "
          f"({stats['dropped_refs']} unreadable dropped), {stats['links']} see-also links "
          f"({stats['dropped_links']} unresolved dropped), {os.path.getsize(TOPICS_OUT) / 1e6:.2f} MB")
    return {title_case(name): title_case(name) for name in order} | {name: title_case(name) for name in order}


# -- output ---------------------------------------------------------------------

def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def check():
    db = sqlite3.connect(f"file:{TOPICS_OUT}?mode=ro", uri=True)
    count = db.execute("SELECT COUNT(*) FROM topics").fetchone()[0]
    assert count > 5_000, count

    def topic(name):
        row = db.execute("SELECT id, body FROM topics WHERE name = ?", (name,)).fetchone()
        assert row, name
        return row[0], json.loads(zlib.decompress(row[1], -15))

    anxiety_id, anxiety = topic("Anxiety")
    care_id, _ = topic("Care")
    assert anxiety == [["", 0, [], [care_id]]], anxiety
    _, aaron = topic("Aaron")
    assert aaron[0][0] == "Lineage of" and aaron[0][2][:2] == [2_006_016, 2_006_020], aaron[0]
    _, prayer = topic("Prayer")
    assert any(e[2] and e[2][0] <= 40_006_009 <= e[2][1] for e in prayer), "Prayer lacks Matthew 6:9"
    assert db.execute("SELECT value FROM meta WHERE key = 'license'").fetchone()[0] == "Public domain"
    db.close()

    with open(THEMES_OUT, encoding="utf-8") as f:
        themes = json.load(f)["themes"]
    anxiety = next(t for t in themes if t["id"] == "anxiety")
    assert anxiety["refs"][0] == "50004006-50004007" and anxiety["nave"] == ["Care"], anxiety
    assert 60 <= len(themes) <= 80, len(themes)
    print("check: ok")


def main():
    if "--fetch" in sys.argv:
        fetch_nave()
    counts = verse_counts()
    nave_names = build_nave(counts)
    groups, themes = load_themes()
    check_refs(themes)
    for theme in themes:
        for start, end in theme["keys"]:
            if end % 1_000 > counts[(end // 1_000_000, end // 1_000 % 1_000)]:
                fail(f"{theme['id']}: {display_ref(start, end)} runs past the end of its chapter")
    translations = load_translations(groups, themes)
    write_themes_json(groups, themes, nave_names)
    update_catalog(catalog_entries(groups, themes, translations))
    write_android(groups, themes, translations)
    write_doc(groups, themes)
    print(f"LifeThemes.json: {len(themes)} themes in {len(groups)} groups, "
          f"{sum(len(t['keys']) for t in themes)} passages")
    if "--check" in sys.argv:
        check()


if __name__ == "__main__":
    main()
