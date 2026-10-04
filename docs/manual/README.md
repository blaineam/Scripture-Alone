# User Guide

The illustrated PDF guide bundled in the app (Aa › User Guide on iPhone, iPad and Mac; Settings on
Android). One PDF per app language, written to `ScriptureAlone/Resources/Manual/UserGuide-<code>.pdf`.

| Path | What |
|---|---|
| `template.html` | Page size (6 × 9 in), print styles, the drawn "mock" screens and callouts |
| `content/en.html` | The English text — the source the other eight are translated from |
| `content/<locale>.html` | zh-Hans, ja, de-DE, fr-FR, es-ES, ko, pt-BR, it |
| `images/<locale>/` | Screenshots from `screenshots/`, framed in Apple's device bezels (Monkr's `static/devices/`) by `Tools/frame_manual_screens.swift` as transparent PNGs, then `pngquant --quality 65-90` |

```bash
python3 Tools/build_manual.py           # all nine PDFs (headless Chrome)
python3 Tools/build_manual.py en ja     # just these
python3 Tools/build_manual.py --check   # non-zero if any PDF is missing or older than its sources
```

Framing a screenshot (iPhone 17 Pro Max for the 6.9" captures, Watch Series 10 46 mm):

```bash
swiftc -O Tools/frame_manual_screens.swift -o /tmp/frame
D=~/Documents/scripts/monkr/static/devices
/tmp/frame $D/iphone-17-pro-max/silver.png shot.png 165 358 160 640 docs/manual/images/<locale>/01-reader.png
/tmp/frame $D/apple-watch-series-10-46mm/silver.png watch.png 72 192 112 420 docs/manual/images/<locale>/w02-verse.png
```

**CI builds them.** `.github/workflows/user-guide.yml` runs on any push to main that touches
`docs/manual/` or the builder: it renders all nine PDFs on a macOS runner, commits them to
`ScriptureAlone/Resources/Manual/` (`[ci skip]`) and uploads them to the `user-guide` GitHub release,
which the website links to — `https://github.com/blaineam/Scripture-Alone/releases/download/user-guide/UserGuide-<code>.pdf`.
So edit the sources, push, and let CI produce the PDFs; build locally only to preview.

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
