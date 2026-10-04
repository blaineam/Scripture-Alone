#!/usr/bin/env python3
"""Builds the bundled user guide: one PDF per app language.

    python3 Tools/build_manual.py            # every language
    python3 Tools/build_manual.py en ja      # just these
    python3 Tools/build_manual.py --check    # fail if a PDF is missing or older than its sources

Sources live in docs/manual/: `template.html` (layout and print styles), `content/<locale>.html`
(the words, one file per language — English is the source the others are translated from) and
`images/<locale>/` (screenshots, downscaled from screenshots/ by hand; the English set predates
the NASB 2020 so the guide never carries licensed text). The PDFs are written to
ScriptureAlone/Resources/Manual/, where the iOS app bundles them and Android's sync task copies them.

Rendering is Chrome's own print-to-PDF, headless, so the page boxes, page numbers and fonts match
what a reader would get printing the HTML.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs" / "manual"
OUT = ROOT / "ScriptureAlone" / "Resources" / "Manual"
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

# Content file locale → the PDF's language code (what the apps look up).
LOCALES = {
    "en": "en", "zh-Hans": "zh-Hans", "ja": "ja", "de-DE": "de", "fr-FR": "fr",
    "es-ES": "es", "ko": "ko", "pt-BR": "pt-BR", "it": "it",
}


def pdf_path(locale: str) -> Path:
    return OUT / f"UserGuide-{LOCALES[locale]}.pdf"


def sources(locale: str) -> list[Path]:
    return [MANUAL / "template.html", MANUAL / "content" / f"{locale}.html",
            *sorted((MANUAL / "images" / locale).glob("*.png"))]


def build(locale: str) -> None:
    content = (MANUAL / "content" / f"{locale}.html").read_text()
    title = re.search(r"<!--\s*title:\s*(.*?)\s*-->", content)
    images = (MANUAL / "images" / locale).as_uri()
    html = (MANUAL / "template.html").read_text()
    html = (html.replace("{{lang}}", LOCALES[locale])
                .replace("{{title}}", title.group(1) if title else "Scripture Alone")
                .replace("{{content}}", content.replace('src="IMG/', f'src="{images}/')))
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        page = Path(tmp) / f"{locale}.html"
        page.write_text(html)
        target = pdf_path(locale)
        # Its own profile, so a Chrome already running never holds the lock; a mock keychain, or a new
        # profile waits forever on a macOS keychain prompt. Headless Chrome can also linger after
        # writing the PDF, so it is stopped once the file has been written and stopped growing.
        target.unlink(missing_ok=True)
        chrome = subprocess.Popen([CHROME, "--headless", "--disable-gpu", "--no-pdf-header-footer",
                                   f"--user-data-dir={Path(tmp) / 'profile'}", "--no-first-run",
                                   "--no-default-browser-check", "--use-mock-keychain",
                                   "--allow-file-access-from-files", "--virtual-time-budget=5000",
                                   f"--print-to-pdf={target}", page.as_uri()],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        size, deadline = -1, time.monotonic() + 120
        while time.monotonic() < deadline:
            if chrome.poll() is not None and not target.exists():
                sys.exit(f"Chrome exited ({chrome.returncode}) without writing {target.name}")
            if target.exists() and target.stat().st_size > 0:
                if target.stat().st_size == size and target.read_bytes().rstrip().endswith(b"%%EOF"):
                    break
                size = target.stat().st_size
            time.sleep(0.5)
        else:
            chrome.kill()
            sys.exit(f"timed out writing {target.name}")
        if chrome.poll() is None:
            chrome.terminate()
            try:
                chrome.wait(10)
            except subprocess.TimeoutExpired:
                chrome.kill()
    print(f"{target.relative_to(ROOT)}  {target.stat().st_size / 1e6:.1f} MB")


def stale(locale: str) -> bool:
    target = pdf_path(locale)
    if not target.exists():
        return True
    return any(s.stat().st_mtime > target.stat().st_mtime for s in sources(locale))


def main(args: list[str]) -> int:
    if "--check" in args:
        bad = [l for l in LOCALES if stale(l)]
        for l in bad:
            print(f"stale or missing: {pdf_path(l).relative_to(ROOT)}")
        return 1 if bad else 0
    wanted = [a for a in args if not a.startswith("-")] or list(LOCALES)
    for locale in wanted:
        if locale not in LOCALES:
            sys.exit(f"unknown locale {locale}; one of {', '.join(LOCALES)}")
        if not (MANUAL / "content" / f"{locale}.html").exists():
            print(f"skipping {locale}: no content/{locale}.html")
            continue
        build(locale)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
