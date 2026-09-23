#!/usr/bin/env python3
"""Adapt each translated App Store description to what that locale's reader actually gets.

    python3 Tools/adapt_store_locales.py            # every appstore-metadata.<locale>.md
    python3 Tools/adapt_store_locales.py fr-FR

Levi translates appstore-metadata.md faithfully — which is the problem: the English promises Calvin,
Gill and Jamieson-Fausset-Brown and a lexicon, and outside English those are hidden (they exist only
in English; docs/localization.md), while it never names the reader's own Bible. This rewrites the
description field of each locale file, through Levi's AI channel, so that it:
  - names the locale's Bible and says it downloads on first launch; ASV, BSB and KJV also available;
  - makes no claim about the commentary;
  - describes the Hebrew and Greek as word by word with parsing and Strong's numbers — no lexicon;
  - stays within the App Store's 4,000 characters, with no price words (Guideline 2.3.7).
Everything else in the file — other fields, the provenance header — is left as Levi wrote it.
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEVI = os.path.join(os.path.dirname(ROOT), "_shared", "levi", "levi.mjs")

LOCALES = {
    "zh-Hans": ("Simplified Chinese", "和合本（新标点）— the Chinese Union Version, simplified script"),
    "ja": ("Japanese", "文語訳聖書 — the Meiji Old Testament (1887) with the Taishō New Testament (1917)"),
    "de-DE": ("German", "Lutherbibel 1912"),
    "fr-FR": ("French", "Louis Segond 1910"),
    "es-ES": ("Spanish", "Reina-Valera 1909"),
    "ko": ("Korean", "개역한글 — the Korean Revised Version"),
    "pt-BR": ("Brazilian Portuguese", "Bíblia Livre (a Almeida revision, Textus Receptus)"),
    "it": ("Italian", "Riveduta 1927 (Luzzi)"),
}

FIELD = re.compile(r"(## description\n)(.*?)(\n## )", re.S)


def ask(prompt):
    for _ in range(4):
        try:
            out = subprocess.run(["node", LEVI, "ai", prompt], capture_output=True, text=True, timeout=420)
        except subprocess.TimeoutExpired:
            continue
        # Levi echoes its own progress lines ("▸ claude -p …") on stdout; they are not the answer.
        text = "\n".join(l for l in out.stdout.splitlines() if not l.startswith("▸")).strip()
        if text:
            # Levi prints the model's text; strip a code fence if the model added one.
            return re.sub(r"^```[a-z]*\n|\n```$", "", text).strip()
    raise RuntimeError("no answer from the model")


def adapt(locale):
    language, bible = LOCALES[locale]
    path = os.path.join(ROOT, f"appstore-metadata.{locale}.md")
    source = open(path, encoding="utf-8").read()
    match = FIELD.search(source)
    current = match.group(2).strip()
    prompt = (
        f"This is the App Store description of a Bible app, already in {language}. Rewrite it for a {language} "
        "reader so every claim is true for them, changing only what these facts require and keeping the rest of "
        "the wording, structure and paragraph headings:\n"
        f"1. Their Bible is the {bible}: the app downloads it on first launch and reads it offline from then on, "
        "with its own book names and verse numbering. The English ASV (included), BSB and KJV are also available. "
        "Say this in the paragraph about reading the Bible offline, and make the paragraph about languages say the "
        f"app is fully in {language} with that Bible.\n"
        "2. The classic commentary (Calvin, Gill, Jamieson-Fausset-Brown) is NOT available in this language: remove "
        "every mention of it. Study mode keeps ranked cross-references and each chapter's context (timeline, maps, "
        "charts).\n"
        "3. The Hebrew and Greek: word by word, with parsing and Strong's numbers — do NOT promise a lexicon or "
        "definitions.\n"
        "4. No price words (free, gratuit, gratis, 免费, 無料…). At most 3,900 characters.\n"
        "Return ONLY the new description text — no preamble, no quotes, no code fence.\n\n"
        + current
    )
    # Italian and the other long languages can come back over the limit; say how far over and ask
    # again rather than truncating someone's sentence.
    for attempt in range(3):
        adapted = ask(prompt)
        problems = []
        if len(adapted) > 3950:
            problems.append(f"it is {len(adapted)} characters; it must be under 3,900 — tighten wording, keep every fact")
        problems += [f"it still mentions {b}" for b in ("Calvin", "Gill", "Jamieson") if b in adapted]
        if not problems:
            break
        prompt = (f"Your rewrite had problems: {'; '.join(problems)}. Fix them and return ONLY the description.\n\n"
                  + adapted)
    else:
        raise SystemExit(f"{locale}: {'; '.join(problems)}")
    with open(path, "w", encoding="utf-8") as f:
        f.write(source[:match.start(2)] + adapted + "\n" + source[match.end(2):])
    print(f"{locale}: {len(current)} → {len(adapted)} characters")


def main():
    locales = [a for a in sys.argv[1:] if a in LOCALES] or [
        l for l in LOCALES if os.path.exists(os.path.join(ROOT, f"appstore-metadata.{l}.md"))]
    for locale in locales:
        adapt(locale)


if __name__ == "__main__":
    main()
