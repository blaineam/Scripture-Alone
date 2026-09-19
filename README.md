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
- **Favorites** — a heart in the selection bar; a Favorites tab in the Notes panel.
- **Widgets** — Verse of the Day (365 hand-picked passages across the canon, listed in
  [docs/daily-verses.md](docs/daily-verses.md)) and Favorites & Notes, on the iPhone Home and
  Lock Screens, iPad and the Mac desktop. The app writes a small JSON snapshot to its App Group
  for them; widgets never open the Bible databases or the SwiftData store.
- **Apple Watch** — Verse of the Day, favorites, read-only notes, a reader and Speak, with
  complications for the day's reference. Standalone: a compact 4.7 MB ASV ships in the watch
  app, and data syncs through the same private iCloud database.
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
- **Share with Family** — invite named family members to see your highlights, notes and
  favorites live and read-only, as you keep reading. Your data is mirrored into a CloudKit zone
  in your private iCloud database and shared with a zone-wide, invite-only `CKShare`; family
  read it from their shared database through the keepsake reader ("Reading Dad's Bible ·
  shared live, updated 5 min. ago"), cached for offline reading. Stop sharing any time; family
  keep the last copy and can save it as a keepsake. Design in [docs/heir-mode.md](docs/heir-mode.md).
- **Notes export** — all notes, the ones you've filtered, or one note, as a typeset PDF,
  Markdown (one file or a folder) or plain text, with the verses quoted in your translation.

## Privacy

- No ads, no trackers, no analytics, no account, and no server of ours holding your data — not
  now, not ever.
- Your highlights, notes, favorites and reading position sync only through your own iCloud
  (the app's private CloudKit database and iCloud key-value storage).
- Keepsakes are files you hand over yourself; nothing is sent anywhere.
- **Share with Family** only happens when you tap *Start Sharing and Invite…*. It copies your
  highlights, notes, favorites, and the name, dedication and translation you choose into a zone
  in *your* private iCloud database, shared read-only with the people you invite — nobody else
  can open it, even with the link. Your reading position, settings and slide photos are never
  shared. What family receive is stored in their iCloud and on their device, apart from their
  own notes. Stop Sharing deletes the shared copy from iCloud. Data travels only between your
  iCloud and theirs, on Apple's servers.
- Camera notes and verse images are processed on device.

## On the way

- **More translations** — CSB, ESV, NKJV and NASB licenses are being requested from their
  publishers. Licensed texts will never be committed to this repository.

## Layout

| Path | What |
|---|---|
| `ScriptureAlone/` | The app (SwiftUI, one multiplatform target: iOS 26+, macOS 26+) |
| `ScriptureAloneCore/` | Swift package: canon, passage parser, reference detector, Bible store, keepsake format, family-sharing mirror mapping and diff, Verse of the Day, widget snapshot |
| `ScriptureAlone/Shared/` | App Group bridge to the widgets, deep links, the Verse of the Day list |
| `ScriptureAloneWidgets/` | WidgetKit extension (iOS, macOS) |
| `ScriptureAloneWatch/`, `ScriptureAloneWatchWidgets/` | Apple Watch app and its complications |
| `Tools/build_bibles.py` | Compiles `Data/source/*.zip` (USFM) into `ScriptureAlone/Resources/Bibles/*.sqlite` |
| `Tools/build_companion_data.py` | Builds the widgets' `DailyVerses.json`, `docs/daily-verses.md` and the watch's compact ASV from `Data/daily-verses.tsv` |
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
python3 Tools/build_companion_data.py --check
cd ScriptureAloneCore && swift test
```

App Store screenshots come from real simulator captures of DEBUG-only scenes over an invented
demo library (`ScriptureAlone/App/ScreenshotScene.swift`, `ScriptureAlone/Shared/DemoLibrary.swift`),
framed in [Monkr](https://github.com/blaineam/Monkr) with a caption per scene:

```bash
./scripts/update-screenshots.sh --no-upload   # capture iPhone, iPad and Watch, then frame for review
```

Designs and captions live in `docs/appstore-screenshots/`; the rig is `Tools/capture_screenshots.sh`.

## License

Code: GNU AGPL‑3.0‑or‑later with the additional permissions in
[LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md). Bible texts and commentaries: public domain.
Cross references and place data: © OpenBible.info, CC BY 4.0. Base map: Natural Earth, public
domain ([details](docs/study-sources.md), [context](docs/context-sources.md)). See
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
