#!/usr/bin/env python3
"""Builds the User Guide: per app language, a PDF (the website's download) and a package the apps
download on demand and draw natively (guide.json + its screenshots, zipped).

    python3 Tools/build_manual.py              # every language: PDFs and packages
    python3 Tools/build_manual.py en ja        # just these
    python3 Tools/build_manual.py --packages   # packages only (no Chrome needed)
    python3 Tools/build_manual.py --check      # fail if an output is missing or older than its sources

Sources live in docs/manual/: `template.html` (layout and print styles), `content/<locale>.html`
(the words, one file per language — English is the source the others are translated from) and
`images/<locale>/` (screenshots, downscaled from screenshots/ by hand; the English set predates
the NASB 2020 so the guide never carries licensed text). Everything is written to dist/manual/ —
UserGuide-<code>.pdf, UserGuide-<code>.zip and UserGuide-index.json — which CI
(.github/workflows/user-guide.yml) publishes to the `user-guide` GitHub release. Nothing is bundled
in the apps: they fetch the zip for their language when the guide is first opened.

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
import hashlib
import json
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import manual_json  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs" / "manual"
OUT = ROOT / "dist" / "manual"
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
            *sorted((MANUAL / "images" / locale).rglob("*.png"))]


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


PLATFORMS = ("iphone", "ipad", "mac", "android")


def package_name(locale: str, platform: str) -> str:
    """The index key and file stem: `en` for iPhone (the name 1.1.2 reads on every Apple device),
    `ipad-en`, `mac-en`, `android-en` for the others."""
    code = LOCALES[locale]
    return code if platform == "iphone" else f"{platform}-{code}"


def zip_path(locale: str, platform: str = "iphone") -> Path:
    return OUT / f"UserGuide-{package_name(locale, platform)}.zip"


def package(locale: str, platform: str) -> None:
    """The guide as one platform's app reads it. Deterministic (fixed timestamps, sorted names), so
    an unchanged guide keeps its hash and the apps never download it twice."""
    doc = manual_json.convert((MANUAL / "content" / f"{locale}.html").read_text(), LOCALES[locale], platform)
    images = MANUAL / "images" / locale

    def image(name: str) -> Path:
        """An edition's own picture (images/<locale>/<platform>/name) before the shared one."""
        own = images / platform / name
        return own if own.exists() else images / name

    missing = [n for n in manual_json.images_used(doc) if not image(n).exists()]
    if missing:
        sys.exit(f"{locale}: images missing: {', '.join(sorted(missing))}")
    OUT.mkdir(parents=True, exist_ok=True)
    target = zip_path(locale, platform)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        def add(name: str, data: bytes, compress: bool = True):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
        add("guide.json", json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode())
        for name in sorted(manual_json.images_used(doc)):
            add(f"images/{name}", image(name).read_bytes(), compress=False)
    print(f"{target.relative_to(ROOT)}  {target.stat().st_size / 1e6:.1f} MB")


def write_index() -> None:
    """UserGuide-index.json: each package's SHA-256 and size, read by the apps to see whether the
    copy they hold is current."""
    index = {"schema": 1, "packages": {}}
    for locale in LOCALES:
        for platform in PLATFORMS:
            z = zip_path(locale, platform)
            if z.exists():
                index["packages"][package_name(locale, platform)] = {"sha256": hashlib.sha256(z.read_bytes()).hexdigest(),
                                                                     "size": z.stat().st_size}
    (OUT / "UserGuide-index.json").write_text(json.dumps(index, indent=2, sort_keys=True) + "\n")


def stale(locale: str) -> bool:
    for target in (pdf_path(locale), *(zip_path(locale, p) for p in PLATFORMS)):
        if not target.exists() or any(s.stat().st_mtime > target.stat().st_mtime for s in sources(locale)):
            return True
    return False


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
        if "--packages" not in args:
            build(locale)
        for platform in PLATFORMS:
            package(locale, platform)
    write_index()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
