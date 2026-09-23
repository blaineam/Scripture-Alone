#!/usr/bin/env python3
"""Translate the Study-mode context — places, timeline, charts, map labels — into the big-8 languages.

    python3 Tools/translate_context.py fr            # one language
    python3 Tools/translate_context.py all           # all eight
    python3 Tools/translate_context.py fr --places   # just the place names

Writes Data/context/translations/<lang>.json, which build_context.py folds into Context.sqlite.
The files are the reviewed source of truth: re-running only fills what is missing (--force redoes).

**Names come from the reader's own Bible.** A place or person is called what the Bible on screen
calls it — 耶路撒冷 in the 和合本, Jérusalem in Segond — so the model is shown the verses that mention
it, in that Bible, and must return the spelling those verses use. The answer is then checked: it
has to appear in one of those verses. Names that fail twice are kept but listed under
"unverified" for a person to read.

**Prose is translated faithfully**, in the register of a study Bible's notes, with references in
the language's own book names and dates in its own conventions. English Bible quotations are not
translated: `ntText` is replaced by the verse from the reader's Bible.

Uses Levi's AI channel (`node _shared/levi/levi.mjs ai`), so the provider is whatever Levi is
configured with.
"""

import concurrent.futures
import glob
import json
import os
import re
import sqlite3
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONTEXT = os.path.join(ROOT, "Data", "context")
OUT_DIR = os.path.join(CONTEXT, "translations")
BIBLES = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles")
STUDY = os.path.join(ROOT, "ScriptureAlone", "Resources", "Study", "Context.sqlite")
LEVI = os.path.join(os.path.dirname(ROOT), "_shared", "levi", "levi.mjs")

LANGUAGES = {
    "zh-Hans": ("CUVS", "Simplified Chinese", "和合本（新标点）"),
    "ja": ("BUNGO", "Japanese", "文語訳聖書 (use the 文語訳's own spellings for biblical names; modern Japanese for prose)"),
    "de": ("LUT1912", "German", "Lutherbibel 1912"),
    "fr": ("LSG", "French", "Louis Segond 1910"),
    "es": ("RVR1909", "Spanish", "Reina-Valera 1909"),
    "ko": ("KRV", "Korean", "개역한글"),
    "pt-BR": ("BLIVRE", "Brazilian Portuguese", "Bíblia Livre"),
    "it": ("RIV1927", "Italian", "Riveduta 1927"),
}

BOOKS = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG ISA JER "
         "LAM EZK DAN HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT ROM 1CO 2CO GAL "
         "EPH PHP COL 1TH 2TH 1TI 2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split()

# Fields in the authored JSON that are prose or names a reader sees.
PROSE_FIELDS = {"date", "dates", "debate", "meaning", "note", "reign", "season", "short", "sources", "sub",
                "subtitle", "summary", "text", "title", "years", "name"}
NAME_FIELDS = {"mother", "prophets"}   # people; checked against the Bible like places


# ---- the reader's Bible --------------------------------------------------------------------

class Bible:
    def __init__(self, tid):
        # Read from worker threads; read-only, so one shared connection is safe.
        self.db = sqlite3.connect(os.path.join(BIBLES, f"{tid}.sqlite"), check_same_thread=False)
        self.lock = __import__("threading").Lock()
        self.native = {}   # KJV key -> native key (the verse holding it)
        rows = self.db.execute("SELECT id, kjv, kjv_last FROM kjv_map").fetchall()
        for native, first, last in rows:
            if first // 1000 == last // 1000:
                for k in range(first, last + 1):
                    self.native[k] = max(native, self.native.get(k, 0))
            else:
                self.native[first] = max(native, self.native.get(first, 0))
        self.moved = {native for native, _, _ in rows}

    def verse(self, kjv_key):
        key = self.native.get(kjv_key, kjv_key)
        if key == kjv_key and key in self.moved:
            return None
        with self.lock:
            row = self.db.execute("SELECT text FROM verses WHERE id = ?", (key,)).fetchone()
        return row[0] if row else None

    def contains_anywhere(self, text):
        with self.lock:
            return self.db.execute("SELECT 1 FROM verses WHERE text LIKE ? LIMIT 1", (f"%{text}%",)).fetchone() is not None


def parse_ref(ref):
    """'1KI 6:1' / 'GEN 35:18' -> KJV key of the first verse, or None."""
    m = re.match(r"([1-3]?[A-Z]{2,3})\s+(\d+):(\d+)", ref or "")
    if not m or m.group(1) not in BOOKS:
        return None
    return (BOOKS.index(m.group(1)) + 1) * 1_000_000 + int(m.group(2)) * 1_000 + int(m.group(3))


# ---- AI -------------------------------------------------------------------------------------

def ask(prompt):
    """One call through Levi; returns the parsed JSON object the prompt asked for."""
    last = ""
    for attempt in range(4):
        try:
            out = subprocess.run(["node", LEVI, "ai", prompt], capture_output=True, text=True, timeout=420)
        except subprocess.TimeoutExpired:
            last = "timed out"   # a stuck call is retried like a malformed one
            continue
        text = out.stdout
        last = out.stderr[-400:]
        start, end = text.find("{"), text.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(text[start:end + 1])
            except json.JSONDecodeError:
                pass
    raise RuntimeError(f"no JSON from the model after 4 attempts: {last}")


def batched(items, size):
    for i in range(0, len(items), size):
        yield items[i:i + size]


# ---- what to translate ----------------------------------------------------------------------

def authored_strings():
    """(prose strings, people) from the authored JSON; people as {name: [kjv keys that mention them]}."""
    prose, people = set(), {}

    def walk(node, field=None, siblings=None):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "_about":
                    continue
                walk(value, key, node)
        elif isinstance(node, list):
            for value in node:
                walk(value, field, siblings)
        elif isinstance(node, str) and field:
            if field in NAME_FIELDS or (field == "name" and siblings and any(k in siblings for k in ("reign", "birth", "mother"))):
                ref = parse_ref((siblings or {}).get("ref") or (siblings or {}).get("birth") or "")
                people.setdefault(node, set())
                if ref:
                    people[node].add(ref)
            elif field in PROSE_FIELDS and re.search(r"[A-Za-z]{2}", node) and not re.fullmatch(r"#?[0-9A-Fa-f]{6}|[a-z]+", node):
                prose.add(node)

    for path in [os.path.join(CONTEXT, f) for f in ("eras.json", "chapters.json", "map_labels.json")] \
            + sorted(glob.glob(os.path.join(CONTEXT, "charts", "*.json"))):
        walk(json.load(open(path)))
    return sorted(prose), {name: sorted(refs) for name, refs in people.items()}


def places():
    db = sqlite3.connect(STUDY)
    rows = db.execute("SELECT id, obid, name, modern FROM places ORDER BY id").fetchall()
    verses = {}
    for verse, place in db.execute("SELECT verse, place FROM place_verses ORDER BY verse"):
        verses.setdefault(place, []).append(verse)
    return [(obid, name, modern, verses.get(pid, [])) for pid, obid, name, modern in rows]


# ---- translation ----------------------------------------------------------------------------

def fold(text):
    return text.lower().replace("’", "'").replace("‘", "'")


def name_in_bible(bible, name, keys):
    """Whether the Bible uses this name in the verses that mention it (anywhere, if none are known).
    Case and apostrophes aside — Segond prints "la vallée d'Ajalon" — and for a descriptive name only
    its proper part has to appear: "Mont Hermon" is "la montagne d'Hermon" in the text."""
    texts = [fold(t) for t in (bible.verse(k) for k in keys[:12]) if t]
    whole = fold(name)
    if texts:
        if any(whole in t for t in texts):
            return True
        proper = [w for w in re.findall(r"[\w'’-]+", name) if w[:1].isupper() and len(w) > 2 and fold(w) not in GENERIC]
        return bool(proper) and all(any(fold(w) in t for t in texts) for w in proper)
    return bible.contains_anywhere(name)


# Descriptive words around a proper name ("Vallée de …", "Mont …"), which the text words differently.
GENERIC = {fold(w) for w in """Vallée Mont Montagne Porte Fleuve Route Chemin Mer Désert Plaine Source Torrent Pays Tour
    Tal Berg Tor Meer Wüste Land Bach Quelle Straße Valle Monte Puerta Río Mar Desierto Tierra Arroyo Camino
    Vale Porta Deserto Terra Rio Ribeiro Valle Fiume Deserto Porta Mare Strada""".split()}


def translate_names(lang, bible, items, kind):
    """items: [(key, english, [kjv keys])] -> ({key: name}, [unverified keys])."""
    _, language, bible_name = LANGUAGES[lang]
    result, unverified = {}, []

    def one_batch(batch, second_try=False):
        lines = []
        for key, english, keys in batch:
            shown = [t for t in (bible.verse(k) for k in keys[: (4 if second_try else 2)]) if t]
            lines.append({"key": key, "english": english, "verses": shown})
        prompt = (
            f"You are localizing a Bible study app into {language}. The reader's Bible is the {bible_name}.\n"
            f"For each {kind} below, give its name EXACTLY as that Bible spells it. The verses shown are from that "
            f"Bible and mention it: copy the spelling from them (the grammatical base form, not a particle or "
            f"case ending attached to it). If no verse is shown, use the spelling that Bible uses elsewhere; if it "
            f"never names it, give the standard {language} Christian spelling.\n"
            'Return ONLY a JSON object {"<key>": "<name>", ...} for every key.\n\n'
            + json.dumps(lines, ensure_ascii=False))
        return ask(prompt)

    def check(key, name, keys):
        return name_in_bible(bible, name, keys)

    pending = list(items)
    for second_try in (False, True):
        failed = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for batch, answer in zip(list(batched(pending, 40)),
                                     pool.map(lambda b: one_batch(b, second_try), list(batched(pending, 40)))):
                for key, english, keys in batch:
                    name = (answer.get(key) or "").strip()
                    if name and check(key, name, keys):
                        result[key] = name
                    else:
                        failed.append((key, english, keys))
                        if name:
                            result[key] = name
        pending = failed
        if not pending:
            break
    unverified = [key for key, _, _ in pending]
    return result, unverified


def translate_prose(lang, strings, labels=False):
    _, language, bible_name = LANGUAGES[lang]
    result = {}

    def one_batch(batch):
        prompt = (
            f"Translate these notes from a Bible study app into {language}, faithfully — the register of a study "
            f"Bible's notes; add nothing, soften nothing, strengthen nothing. Biblical names use the spellings of the "
            f"{bible_name}. Bible references use {language} book names. Dates follow {language} conventions "
            f"(BC/AD, 'c.' = circa). Book and author titles in citations stay as published. Keep short labels short.\n"
            + ("These are the short themes shown above a Verse of the Day (\"The first promise of a Savior\"): "
               "a few words each, natural in the language, not literal.\n" if labels else "")
            + 'Return ONLY a JSON object mapping each index (as a string) to its translation.\n\n'
            + json.dumps({str(i): s for i, s in enumerate(batch)}, ensure_ascii=False))
        answer = ask(prompt)
        return {batch[int(i)]: text for i, text in answer.items() if i.isdigit() and int(i) < len(batch)}

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for part in pool.map(one_batch, list(batched(strings, 30))):
            result.update(part)
    return result


def main():
    args = sys.argv[1:]
    if "--themes" in args:
        return translate_themes([a for a in args if a in LANGUAGES] or list(LANGUAGES))
    if "--recheck" in args:
        return recheck([a for a in args if a in LANGUAGES] or list(LANGUAGES))
    force = "--force" in args
    only_places = "--places" in args
    langs = list(LANGUAGES) if "all" in args else [a for a in args if a in LANGUAGES]
    if not langs:
        sys.exit(__doc__)
    os.makedirs(OUT_DIR, exist_ok=True)
    prose, people = authored_strings()
    place_rows = places()
    for lang in langs:
        path = os.path.join(OUT_DIR, f"{lang}.json")
        data = json.load(open(path)) if os.path.exists(path) and not force else {}
        data.setdefault("places", {})
        data.setdefault("modern", {})
        data.setdefault("people", {})
        data.setdefault("strings", {})
        data.setdefault("unverified", [])
        bible = Bible(LANGUAGES[lang][0])

        def save():
            # After every stage, so a failure later never costs the work already done.
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)
                f.write("\n")

        todo = [(obid, name, keys) for obid, name, _, keys in place_rows if obid not in data["places"]]
        if todo:
            names, unverified = translate_names(lang, bible, todo, "place")
            data["places"].update(names)
            data["unverified"] = sorted(set(data["unverified"]) | set(unverified))
            print(f"{lang}: {len(names)} place names, {len(unverified)} unverified", flush=True)
            save()
        if not only_places:
            todo = [(n, n, keys) for n, keys in people.items() if n not in data["people"]]
            if todo:
                names, unverified = translate_names(lang, bible, todo, "person")
                data["people"].update(names)
                data["unverified"] = sorted(set(data["unverified"]) | {f"person:{k}" for k in unverified})
                print(f"{lang}: {len(names)} people, {len(unverified)} unverified", flush=True)
                save()
            modern = sorted({m for _, _, m, _ in place_rows if m and m not in data["modern"]})
            if modern:
                _, language, _ = LANGUAGES[lang]
                for batch in batched(modern, 60):
                    answer = ask(f"Give the standard {language} name (exonym or transliteration as a {language} atlas "
                                 "would print it) for each modern place. Return ONLY a JSON object "
                                 '{"<english>": "<name>"}.\n\n' + json.dumps(batch, ensure_ascii=False))
                    data["modern"].update({k: v for k, v in answer.items() if k in batch})
                print(f"{lang}: {len(data['modern'])} modern names", flush=True)
                save()
            todo = [s for s in prose if s not in data["strings"]]
            if todo:
                data["strings"].update(translate_prose(lang, todo))
                print(f"{lang}: {len(data['strings'])} prose strings", flush=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write("\n")


def translate_themes(langs):
    """Verse of the Day themes (Data/daily-verses.tsv) — short labels, stored under "themes"."""
    themes = []
    for line in open(os.path.join(ROOT, "Data", "daily-verses.tsv"), encoding="utf-8"):
        if line.strip() and not line.startswith("#") and "\t" in line:
            themes.append(line.rstrip("\n").split("\t", 1)[1].strip())
    themes = sorted(set(themes))
    for lang in langs:
        path = os.path.join(OUT_DIR, f"{lang}.json")
        data = json.load(open(path)) if os.path.exists(path) else {}
        data.setdefault("themes", {})
        todo = [t for t in themes if t not in data["themes"]]
        if todo:
            data["themes"].update(translate_prose(lang, todo, labels=True))
        print(f"{lang}: {len(data['themes'])} themes")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write("\n")


def recheck(langs):
    """Re-verifies stored names against the Bibles (after the check itself changes) — no AI calls."""
    place_keys = {obid: keys for obid, _, _, keys in places()}
    _, people = authored_strings()
    for lang in langs:
        path = os.path.join(OUT_DIR, f"{lang}.json")
        if not os.path.exists(path):
            continue
        data = json.load(open(path))
        bible = Bible(LANGUAGES[lang][0])
        still = []
        for key in data.get("unverified", []):
            if key.startswith("person:"):
                name = key[7:]
                if not name_in_bible(bible, data["people"].get(name, ""), people.get(name, [])):
                    still.append(key)
            elif not name_in_bible(bible, data["places"].get(key, ""), place_keys.get(key, [])):
                still.append(key)
        print(f"{lang}: {len(data.get('unverified', []))} → {len(still)} unverified")
        data["unverified"] = still
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write("\n")


if __name__ == "__main__":
    main()
