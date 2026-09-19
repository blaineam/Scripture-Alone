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
- **Listen** — read aloud from the top of the screen or a selection with any installed system voice
  (Premium, Enhanced and Personal Voice included), the spoken verse marked and kept in view, on
  into the next chapter, with speed, sleep timer, lock-screen controls and background audio. On
  iPhone and iPad, [Mi Speaks](https://wemiller.com/apps/mi-speaks/) subscribers can have its
  Studio voices record the chapter instead (see `ScriptureAlone/Listen/MiSpeaksClient.swift`).
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
- **Maps, timeline and charts** — for the chapter you're reading: its era on a timeline of
  the biblical periods, a map of every place it names (tap one for every verse that mentions
  it), and charts — the kings of Israel and Judah, Paul's journeys drawn on the map, the twelve
  tribes, the feasts of Israel. The map is drawn on-device from bundled Natural Earth data, so
  it works with no connection. On iPad and Mac it opens in its own window to keep beside the
  text. Places are from [OpenBible.info](https://www.openbible.info/geo/) (CC BY 4.0); see
  [docs/context-sources.md](docs/context-sources.md).
- **Legacy Bible** — make a keepsake of your highlights and notes for family: one file, given by
  AirDrop, Messages, a USB drive or with your will, optionally passphrase-protected. Family open
  it and read your Bible as you marked it, read-only, kept apart from their own notes. The format
  is an open ZIP of JSON ([docs/heir-mode.md](docs/heir-mode.md)).
- **Notes export** — all notes, the ones you've filtered, or one note, as a typeset PDF,
  Markdown (one file or a folder) or plain text, with the verses quoted in your translation.

## On the way

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
| `Tools/build_study.py` | Compiles `Data/source/study/` (cross references, commentary) into `ScriptureAlone/Resources/Study/Study.sqlite` |
| `Tools/build_context.py` | Builds `Resources/Study/Context.sqlite` + `Basemap.bin` from OpenBible.info places, Natural Earth and `Data/context/` |
| `Data/context/` | Authored eras, events, chapter→era map, charts and map labels |

## Build

```bash
xcodegen generate
python3 Tools/build_bibles.py --check
python3 Tools/build_study.py --check
python3 Tools/build_context.py --check   # fetches pinned sources once into Data/cache/
cd ScriptureAloneCore && swift test
```

## License

Code: GNU AGPL‑3.0‑or‑later with the additional permissions in
[LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md). Bible texts and commentaries: public domain.
Cross references and place data: © OpenBible.info, CC BY 4.0. Base map: Natural Earth, public
domain ([details](docs/study-sources.md), [context](docs/context-sources.md)). See
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
