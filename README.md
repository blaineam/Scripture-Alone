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
- **Camera notes** — snap the sermon slide (or pick a photo; on Mac, an image file, the clipboard
  or Import from iPhone) and on-device text recognition starts the note: titled with the sermon
  title, linked to every passage on the slide, with the other lines as bullets. Review and edit
  before saving; later slides add to the same note. The photo is discarded unless you keep it.
- **Sync** — SwiftData over the private CloudKit database; reading position through iCloud
  key-value storage.
- **Share** — verse images designed on-device (eight templates, three shapes, any of the reader's
  typefaces; save to Photos or share), plain text, or a link that rebuilds the card in any browser
  from the link itself — the verse rides in the URL fragment, so no server stores it. Contract:
  [docs/share-links.md](docs/share-links.md).
- **Study mode** — a toggle in the toolbar. The Study panel follows the verse you tap: ranked
  cross references (OpenBible.info, CC BY 4.0), each with its text in the translation you're
  reading, one tap to jump there and back; and classic commentary from John Calvin, John Gill and
  Jamieson‑Fausset‑Brown (public domain), with the references inside it linked. The panel sits
  beside the text on iPad and Mac, and in a resizable sheet on iPhone. Sources and licenses:
  [docs/study-sources.md](docs/study-sources.md).

## On the way

- **Listen** — text-to-speech with system voices, and optional hand-off to
  [Mi Speaks](https://wemiller.com/apps/mi-speaks/) for its Studio voices.
- **Study context** — maps and timelines of the biblical periods, and charts, in Study mode's
  Context tab.
- **Heir mode** — share a read-only copy of your notes and highlights with family, or export a
  keepsake: a digital "Dad's Bible."
- **More translations** — CSB, ESV, NKJV and NASB licenses are being requested from their
  publishers. Licensed texts will never be committed to this repository.

## Layout

| Path | What |
|---|---|
| `ScriptureAlone/` | The app (SwiftUI, one multiplatform target: iOS 26+, macOS 26+) |
| `ScriptureAloneCore/` | Swift package: canon, passage parser, reference detector, Bible store |
| `Tools/build_bibles.py` | Compiles `Data/source/*.zip` (USFM) into `ScriptureAlone/Resources/Bibles/*.sqlite` |
| `Data/source/` | Source texts: BSB from berean.bible, ASV and KJV from eBible.org |
| `Tools/build_study.py` | Compiles `Data/source/study/` (cross references, commentary) into `ScriptureAlone/Resources/Study/Study.sqlite` |

## Build

```bash
xcodegen generate
python3 Tools/build_bibles.py --check
python3 Tools/build_study.py --check
cd ScriptureAloneCore && swift test
```

## License

Code: GNU AGPL‑3.0‑or‑later with the additional permissions in
[LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md). Bible texts and commentaries: public domain.
Cross references: © OpenBible.info, CC BY 4.0 ([details](docs/study-sources.md)). See
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
