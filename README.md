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
- **Maps, timeline and charts** — for the chapter you're reading: its era on a timeline of
  the biblical periods, a map of every place it names (tap one for every verse that mentions
  it), and charts — the kings of Israel and Judah, Paul's journeys drawn on the map, the twelve
  tribes, the feasts of Israel. The map is drawn on-device from bundled Natural Earth data, so
  it works with no connection. On iPad and Mac it opens in its own window to keep beside the
  text. Places are from [OpenBible.info](https://www.openbible.info/geo/) (CC BY 4.0); see
  [docs/context-sources.md](docs/context-sources.md).

## On the way

- **Share** — designed verse images made on-device, plus links that rebuild the image in the
  browser from the link itself (the verse rides in the URL fragment; no server stores it).
- **Listen** — text-to-speech with system voices, and optional hand-off to
  [Mi Speaks](https://wemiller.com/apps/mi-speaks/) for its Studio voices.
- **Study mode** — cross-references and classic Reformed commentary alongside the maps,
  timeline and charts, a toggle away.
- **Camera notes** — snap the sermon slide; on-device text recognition titles the note and
  links the passages it mentions (the reference detector is already in `ScriptureAloneCore`).
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
| `Tools/build_context.py` | Builds `Resources/Study/Context.sqlite` + `Basemap.bin` from OpenBible.info places, Natural Earth and `Data/context/` |
| `Data/context/` | Authored eras, events, chapter→era map, charts and map labels |

## Build

```bash
xcodegen generate
python3 Tools/build_bibles.py --check
python3 Tools/build_context.py --check   # fetches pinned sources once into Data/cache/
cd ScriptureAloneCore && swift test
```

## License

Code: GNU AGPL‑3.0‑or‑later with the additional permissions in
[LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md). Bible texts: public domain. Place data:
OpenBible.info, CC BY 4.0. Base map: Natural Earth, public domain. See
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
