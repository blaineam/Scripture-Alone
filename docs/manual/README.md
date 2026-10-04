# User Guide

The illustrated User Guide, in nine languages. The apps draw it natively (SwiftUI on iPhone, iPad
and Mac; Compose on Android) from a package they download the first time it's opened — Aa › User
Guide, or the one-time welcome card. The website offers the same guide as a PDF.

Per language, `Tools/build_manual.py` writes to `dist/manual/` (gitignored):

| File | What |
|---|---|
| `UserGuide-<code>.zip` | `guide.json` (by `Tools/manual_json.py`, whose docstring is the schema; decoded by `ScriptureAloneCore/Guide/UserGuide.swift` and Android's `ui/guide`) + the screenshots it uses. Deterministic, so an unchanged guide keeps its hash. |
| `UserGuide-index.json` | Each package's SHA-256 and size: the apps re-download only a changed guide. |
| `UserGuide-<code>.pdf` | The website's download (headless Chrome). |

| Path | What |
|---|---|
| `template.html` | Page size (6 × 9 in), print styles, the drawn "mock" screens and callouts |
| `content/en.html` | The English text — the source the other eight are translated from |
| `content/<locale>.html` | zh-Hans, ja, de-DE, fr-FR, es-ES, ko, pt-BR, it |
| `images/<locale>/` | Screenshots from `screenshots/`, framed in Apple's device bezels (Monkr's `static/devices/`) by `Tools/frame_manual_screens.swift` as transparent PNGs, then `pngquant --quality 65-90` |

```bash
python3 Tools/build_manual.py              # all nine: PDFs and packages
python3 Tools/build_manual.py en ja        # just these
python3 Tools/build_manual.py --packages   # packages only, no Chrome
python3 Tools/build_manual.py --check      # non-zero if any output is missing or older than its sources
```

Framing a screenshot (iPhone 17 Pro Max for the 6.9" captures, Watch Series 10 46 mm):

```bash
swiftc -O Tools/frame_manual_screens.swift -o /tmp/frame
D=~/Documents/scripts/monkr/static/devices
/tmp/frame $D/iphone-17-pro-max/silver.png shot.png 165 358 160 640 docs/manual/images/<locale>/01-reader.png
/tmp/frame $D/apple-watch-series-10-46mm/silver.png watch.png 72 192 112 420 docs/manual/images/<locale>/w02-verse.png
```

**CI builds and publishes them.** `.github/workflows/user-guide.yml` runs on any push to main that
touches `docs/manual/` or the builders: it builds everything on a macOS runner and uploads it to the
`user-guide` GitHub release (`https://github.com/blaineam/Scripture-Alone/releases/download/user-guide/<file>`),
packages first and the index last. Nothing is committed or bundled, so a guide change reaches the
apps without an app release. Edit the sources, push, and let CI publish; build locally only to
preview. A new block kind needs both renderers (and `UserGuide.supportedSchema` bumped if old apps
can't skip it).

**PDF-only and app-only passages.** Wrap what only the website's PDF may carry in
`data-only="pdf"` (block or inline), and its replacement for the apps in `data-only="app"`:
`manual_json.py` drops the first, the template's stylesheet hides the second. The apps carry no
links to third-party stores or files — the import section points to the website FAQ
(`…/faq/?lang=<code>#esv-csb`) instead.

## Keeping it right

- **Change English first**, then carry the change into the eight other files. Every button or menu
  name in `<span class="ui">` and in the mock screens uses the app's own translation from
  `ScriptureAlone/Resources/Localizable.xcstrings`, so the guide matches the screen.
- **Never use screenshots that show the NASB.** The English images come from the App Store set
  captured before the NASB 2020 (commit f3253c0); the Lockman agreement forbids AI handling of NASB
  text, and the guide doesn't need it. Re-capture English screens in the BSB or KJV if they need
  replacing.
- When a licensed translation (ESV, CSB, NKJV, LSB) ships in the app, update chapter 4 ("More
  translations offline") and chapter 5 (online keys) in every language.
- Third-party links (api.esv.org, api.bible, the Ligonier ePub, the CSB pew Bible PDF) are checked
  by hand; run through them before a release.
