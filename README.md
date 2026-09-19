# Scripture Alone

A free, private, offline Bible for iPhone, iPad and Mac. Zero ads, zero trackers, no
account — and it all works offline, anywhere in the world. Your highlights and notes sync
only through your own iCloud.

Open source under the [AGPL‑3.0](LICENSE) with an [app‑store exception](LICENSE-EXCEPTIONS.md).
Page: [wemiller.com/apps/scripture-alone](https://wemiller.com/apps/scripture-alone/)

## What's here

- **Offline text** — the American Standard Version (default), Berean Standard Bible and King
  James Version are bundled as SQLite databases with full-text search. All three are public
  domain. The ASV's words of Christ are aligned from the KJV it revised; its section headings
  come from the BSB.
- **Reader** — paragraph or verse-by-verse layout, poetry indentation, words of Christ in red,
  footnotes, seven typefaces, five themes (Auto, Light, Sepia, Dark, Black), Dynamic Type,
  auto-scroll that carries on into the next chapter.
- **Quick navigation** — type `jn 3 16`, `rom 8:28-39`, `1co13` or a word to search; or browse
  books and chapters. ⌘L on Mac and iPad keyboards.
- **Highlights and notes** — tap verses to select; highlight in five colors; attach a note to one
  or more verse ranges (a sermon on Romans 8:1–17). Notes show inline beside the verse and in a
  searchable Notes panel.
- **Sync** — SwiftData over the private CloudKit database; reading position through iCloud
  key-value storage.
- **Legacy Bible** — make a keepsake of your highlights and notes for family: one file, given by
  AirDrop, Messages, a USB drive or with your will, optionally passphrase-protected. Family open
  it and read your Bible as you marked it, read-only, kept apart from their own notes. The format
  is an open ZIP of JSON ([docs/heir-mode.md](docs/heir-mode.md)).
- **Notes export** — all notes, the ones you've filtered, or one note, as a typeset PDF,
  Markdown (one file or a folder) or plain text, with the verses quoted in your translation.

## On the way

- **Share** — designed verse images made on-device, plus links that rebuild the image in the
  browser from the link itself (the verse rides in the URL fragment; no server stores it).
- **Listen** — text-to-speech with system voices, and optional hand-off to
  [Mi Speaks](https://wemiller.com/apps/mi-speaks/) for its Studio voices.
- **Study mode** — cross-references, maps and timelines of the biblical periods, charts, and
  classic Reformed commentary, a toggle away.
- **Camera notes** — snap the sermon slide; on-device text recognition titles the note and
  links the passages it mentions (the reference detector is already in `ScriptureAloneCore`).
- **Live family sharing** — SwiftData can't share through CloudKit yet; see
  [docs/heir-mode.md](docs/heir-mode.md) for the options.
- **More translations** — CSB, ESV, NKJV and NASB licenses are being requested from their
  publishers. Licensed texts will never be committed to this repository.

## Layout

| Path | What |
|---|---|
| `ScriptureAlone/` | The app (SwiftUI, one multiplatform target: iOS 26+, macOS 26+) |
| `ScriptureAloneCore/` | Swift package: canon, passage parser, reference detector, Bible store, keepsake format |
| `Tools/build_bibles.py` | Compiles `Data/source/*.zip` (USFM) into `ScriptureAlone/Resources/Bibles/*.sqlite` |
| `Data/source/` | Source texts: BSB from berean.bible, ASV and KJV from eBible.org |

## Build

```bash
xcodegen generate
python3 Tools/build_bibles.py --check
cd ScriptureAloneCore && swift test
```

## License

Code: GNU AGPL‑3.0‑or‑later with the additional permissions in
[LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md). Bible texts: public domain. See
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
