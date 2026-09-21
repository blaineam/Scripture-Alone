# Android parity ledger

The Android app is a native Kotlin / Jetpack Compose port aiming at **full parity with the iOS
1.0.x app before it ships**. This ledger is the source of truth for what exists, what is planned and
how each Apple-only piece is replaced. Update the Status column in the same commit that changes it.

## Constraints

- **No monthly hosting cost.** The iOS app runs no server; neither does this one. Anything that
  syncs uses storage the *reader* already has (their Google account), never a service the developer
  pays for. Distribution is Google Play's one-time registration.
- **Visually comparable.** Not stock Material. The reader, selection bar, panels and study surfaces
  should read as the same app on both platforms — the same themes, accents, typography scale and
  layout rhythm — expressed in Compose rather than imitated pixel for pixel.
- **No cross-platform sync.** An Android reader's highlights and notes do not reach an iPhone, and
  vice versa. Files are the exception: a keepsake, a `.sabible` package and a share link are the same
  format on both and interoperate.
- **Same data, byte for byte.** Every bundled database is copied from `ScriptureAlone/Resources` at
  build time (`android/app/build.gradle.kts`, `syncBundledData`). Nothing is committed twice.

## Architecture

| Concern | Choice | Why |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 as a base with a custom design system | Idiomatic Android; the custom layer carries the iOS look |
| Bundled SQLite | `androidx.sqlite:sqlite-bundled` | **Android's own SQLite has no FTS5** — verified, `no such module: fts5` — and every Bible database searches with it |
| User data | Plain SQLite over the bundled driver (`data/userdata/`) | Highlights, notes, favorites — the SwiftData models as three tables. Chosen over Room: three small tables need no ORM, the bundled driver is already in the app for FTS5, and the store runs unchanged on the JVM through JDBC (`UserDatabase`), with no KSP in the build |
| Settings | DataStore (Preferences) | The UserDefaults `reader.*` keys |
| Crypto | JCA (AES-GCM, HMAC, PBKDF2) + Tink (Ed25519, HKDF) + Android Keystore | API 29 has no platform Ed25519; Keystore replaces the Secure Enclave |
| Background work | WorkManager | Family-share refresh, catalogue downloads |
| Build | Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, compileSdk 36, minSdk 29 (Wear 30) — Haven's known-good set; warnings are errors. Modules: `:app`, `:wear`, and `:shared` (plain Kotlin: canon, verse keys, Verse of the Day, `VerseSnapshot`, the Data Layer vocabulary) |
| Store access | `data/sql/SqlSource` over the bundled driver in the app, JDBC in tests | So each store is proven on the JVM against the real database file |

Shared invariants, which must never diverge from `ScriptureAloneCore`:
- Verse key = `book × 1,000,000 + chapter × 1,000 + verse`; verse 0 is a heading / superscription.
- Layout span and footnote offsets are **Unicode scalar** counts, not UTF-16. Kotlin strings are
  UTF-16, so every offset is converted before it touches an `AnnotatedString`.
- `verses.red` is JSON `[[start, length]]` in scalars.
- Commentary and interlinear bodies are **raw DEFLATE** (`Inflater(nowrap = true)`); commentary
  pieces are separated by U+0000.
- `crossrefs.refs` is packed little-endian 10-byte records: `to_start u32, to_end u32, votes u16`.

## The hard problems, resolved

Features with no free, direct Android equivalent, and what replaces each.

| iOS | Android | Notes |
|---|---|---|
| iCloud sync of highlights / notes / favorites (SwiftData + CloudKit) | **Google Drive app-data folder** | Free, per-user, in the reader's own Drive quota, invisible in their Drive UI. Needs a Google Cloud OAuth client — free, one-time, **owner action**. |
| Live family sharing (zone-wide `CKShare`, silent push) | **A snapshot file in the owner's Drive, shared read-only** with family members' Google accounts through Drive permissions | Serverless and free. No push without a server, so members refresh on open and on a periodic WorkManager job rather than instantly. |
| iCloud key-value storage (reading position, flags) | Folded into the Drive app-data sync | Same small state, same account |
| iCloud Keychain sync of ESV / API.Bible keys | **Google Block Store** | Free, end-to-end encrypted, restores to the reader's other devices |
| Secure Enclave sealing of the `.sabible` content key | **Android Keystore** (StrongBox where present) AES key wraps the HKDF-derived content key | The package format itself is portable — same file, same AES-GCM / Ed25519 / HKDF / HMAC |
| Apple system typefaces (New York, SF, Charter, Iowan, Georgia, Palatino, Avenir Next) | **Bundled open-licensed equivalents**, all SIL OFL 1.1 | Source Serif 4, Inter, Charis SIL (a Charter derivative), Literata, Gelasio (Georgia-metric), Domitian (URW Palladio, i.e. Palatino; OFL of its AGPL/LPPL/OFL choice) and Nunito Sans, in that order. TeX Gyre Pagella was passed over because its GUST licence is neither OFL nor Apache. The share-link `f` tokens (`serif`, `sans`, `charter`, …) map to these. |
| Personal Voice | None — **platform difference** | Listen uses `android.speech.tts` with the device's voices (Google's neural voices are free and on-device) |
| Mi Speaks Studio voices | None — **platform difference** | Mi Speaks is an iOS app |
| Lock Screen accessory widgets | None on phones — **platform difference** | Home-screen Glance widgets cover both widget kinds |
| Apple Watch app + complications | **Wear OS app** reading the same `*-Watch.sqlite` editions; Tiles and complications for the verse of the day; Data Layer API for phone ↔ watch | Free, no server |
| Universal-link fragment matching (`#s=`) | Keep the share-link format; the web page offers **"Open in Scripture Alone"** via the custom scheme on Android | Android App Links can't match fragments, and a path match would hijack the product page |
| VisionKit live text scanner | **CameraX + ML Kit Text Recognition** (bundled Latin model) | On-device, free; live highlighting is feasible |

## Ledger

Status: ✅ done · 🔧 in progress · ⬜ planned · ➖ not applicable on Android

### Foundation
| Feature | Status | Notes |
|---|---|---|
| Bundled databases synced from iOS resources | ✅ | ASV ships as `ASV.sabible` only, as on iOS |
| Uncompressed assets, copied to `noBackupFilesDir`, version-stamped | ✅ | |
| SQLite with FTS5 | ✅ | `MATCH 'shepherd'` = 62 on BSB, matching the Mac |
| Verse key encoding | ✅ | `data/VerseRef.kt` |

### Reading
| Feature | Status | Notes |
|---|---|---|
| ASV (sealed), BSB, KJV | ✅ | All three render; every chapter of each parses (1,189) |
| Chapter layout: headings, paragraphs, poetry indents, Selah, stanza breaks, psalm titles | ✅ | `data/layout/`, `ui/reader/ChapterRenderer.kt`; indents and spacing from the Swift values |
| Words of Christ in red | ✅ | Scalar offsets converted to UTF-16; tested past a non-BMP character |
| Footnotes with popovers | ✅ | Tap a letter (18 dp reach) for a rounded popover beside it, in the reader's theme, with a soft painted shadow as on iOS |
| Divine name in small caps | ✅ | Source Serif 4 has true `smcp`/`c2sc`; italic falls back to 78% capitals |
| Supplied words in italics | ✅ | |
| Paragraph vs verse-by-verse | ✅ | |
| Seven typefaces | ✅ | `ReaderFontFamily` in `ui/reader/ReaderTypography.kt`, stored as `reader.fontFamily` with the Swift raw values (`newYork`, `charter`, …). New York → Source Serif 4, SF → Inter, Charter → Charis SIL, Iowan → Literata, Georgia → Gelasio, Palatino → Domitian, Avenir Next → Nunito Sans. The picker names the face actually drawn, with the iPhone face beneath ("For Charter"). Charis SIL ships unmodified (its licence reserves the name for unmodified copies); the rest are cut by `android/tools/subset_fonts.py` to Latin/Greek/Cyrillic, weights 400–700 (+2.3 MB compressed in the APK, 4.7 MB of files). Each licence is in `assets/licenses/` and shown in full under Appearance › Font Licences. The divine name uses real `smcp` where the face has it (Source Serif roman, Charis, Literata, Domitian roman) and reduced capitals where it doesn't — seen in Psalm 23 in Charis and Nunito Sans. Emulator-verified: every face in John 3, and the choice surviving a force-stop |
| Size 12–40 pt, line spacing 1.0–2.0, system font scale | ✅ | The Appearance sheet's sliders, as iOS: size in whole points between the small and large "A" (both also step 1 pt), spacing continuous between the line glyphs; `reader.fontSize`, `reader.lineSpacing`. The size is drawn in sp, so the system font size scales it as Dynamic Type does on iOS; the sheet says so when that scale isn't 1. Emulator-verified: a drag re-typesets the chapter live behind the sheet, and font scale 1.3 enlarges the reader with the note shown ("19 pt, then scaled by the system font size (×1.3)…") |
| Themes: Auto, Light, Sepia, Dark, Black | ✅ | Exact palette values from `ReaderStyle.swift` |
| Seven accent colors | ✅ | |
| Show toggles: red letters, verse numbers, headings, footnotes | ✅ | Persisted under the iOS keys; iOS's switch rows in the Appearance sheet's Show section |
| Auto-scroll (16/28/44/64 pt/s), continues into next chapter | ✅ | Speeds and names (Slow, Relaxed, Steady, Brisk) confirmed in `ReaderView.swift`, persisted as `reader.autoScrollSpeed` (default 28). In the bottom bar's middle pill beside Listen, as on iPhone: tap starts/pauses, hold for the speed menu. Whole pixels per frame with the fraction carried (`data/listen/AutoScroll.kt`, tested); at a chapter's end it opens the next and keeps going (a chapter that fits on screen hands over at once, as on iOS); a drag stops it. Emulator-verified: ~28 pt/s measured, Psalm 117 → 118 while scrolling, drag stops |
| Chapter paging: buttons, swipe, "Next chapter →" | ✅ | Swipe only past the horizontal touch slop, so it never takes a vertical scroll |
| Reading position restored | ✅ | DataStore, iOS key names (`position`, `translation`, `reader.*`); the top verse is saved as the reader scrolls and scrolled back to on launch; John 1 fallback |
| Copyright line in the chapter footer | ✅ | |

### Navigation and search
| Feature | Status | Notes |
|---|---|---|
| Passage parser ("jn 3 16", ranges, lists, ordinals) | ✅ | Every Swift test case ported; canon generated from `Canon.swift` |
| Reference detection in free text | ✅ | Swift tests ported, incl. the sermon slide |
| Go To sheet: search, recents, book and chapter grids | ✅ | `ui/navigation/GoToSheet.kt`; a reference with a verse scrolls to it. The reader recedes behind it (scaled, rounded, on black) with light status icons, as behind an iOS sheet |
| Recent chapters (12) and searches (12) | ✅ | A search is kept only when a result is opened; long-press to Remove; Clear |
| FTS5 search: all words, last word prefix, quoted phrase, 300 limit | ✅ | `data/search/`; `ftsQuery` ported line for line and tested against the shipped BSB and KJV; matched words bolded |
| Sealed-package search | ⬜ | The ASV says search is coming and offers to switch to BSB/KJV — it never searches another translation under the ASV's name |
| Online translation search | ⬜ | |

### Highlights, notes, favorites
| Feature | Status | Notes |
|---|---|---|
| Selection bar | ✅ | `ui/reader/SelectionBar.kt`: tap a verse to select or deselect it, long-press to extend the selection to it; selected verses get iOS's thick dotted accent underline. Same controls, order and labels as `SelectionBar.swift` (reference, Listen, Clear; five colours, Remove Highlight, divider, Favorite, Add Note, Copy, Original Language for one verse, Share). Listen reads the selection aloud (`ui/listen/`); Original Language opens Study; Study and Compare Translations… likewise (`ReaderScreen` parameters). Share offers Share Image… (the verse-image designer), Share Text, Copy Link, and Share Link… for Android's share sheet |
| Highlights, five colors | ✅ | iOS names and palette (yellow F7D154, green 8CD48A, blue 7FB8F0, pink F29BB8, purple B9A2EC at 42% / 34% dark), full-line-height fills with the gap between same-coloured verses filled, newest wins; the eraser removes. Drawn over the text, so marking never re-typesets. Survive relaunch (verified) |
| Notes with multiple anchors, inline markers, Notes panel | ✅ | `ui/notes/`: the panel as a sheet (All Notes / This Chapter / Favorites, search by words or a passage, New Note, long-press Delete) and the editor (title, passages with add-by-typing and Add Selection, body, created/edited, Share Note, Delete Note with confirmation). `text.bubble.fill` marker after an anchor's last verse; tapping it opens the popover with Open Note. `Note` maps 1:1 onto `KeepsakeNote` (tested). Not yet: camera slide capture and Export… in the panel |
| Favorites | ✅ | Heart in the selection bar (filled when every range is a favorite; toggles as iOS), Favorites scope in the Notes panel with each passage's text in the current translation, tap to open, long-press Delete |
| Export: PDF, Markdown, Markdown folder, plain text | 🔧 | Markdown and text ported (`NotesTextExport.kt`), with the publisher notice on every export. PDF (`android.graphics.pdf.PdfDocument`) and the sheet not yet built |
| Quotation-limit gate on copy and share | ✅ | Copy asks `permits(COPY)` and `mayQuote`, Share `mayQuote` (and `permits(SHARE)` for text and links), Share Image… `permits(VERSE_IMAGES)` and `mayQuote`; disabled, not hidden, with iOS's notice beneath. Links, as on iOS, only for texts that need no notice (`ShareSource.linksAllowed`). The bundled three are public domain, so the refusals are unit-tested rather than seen |

### Camera notes
| Feature | Status | Notes |
|---|---|---|
| Live scanner, camera, photo, file, paste | ⬜ | CameraX + ML Kit |
| On-device recognition with Bible book names | ⬜ | |
| Slide → note parsing | ✅ | `data/slides/SlideParser.kt`; every Swift case ported (18 tests). The camera/OCR capture is its own row |
| Review sheet, keep photo | ⬜ | |

### Listen
| Feature | Status | Notes |
|---|---|---|
| Read aloud verse by verse, spoken verse marked | ✅ | `ui/listen/ListenController.kt` over `TextToSpeech`, the chapter queued at once with iOS's pauses (0.12 s, 0.5 s after the announcement). Reads `verses.text` only: never headings, superscriptions (verse 0) or footnote letters; the words of Christ in the same voice — as iOS. From the bottom bar's Listen (from the verse at the top, "John, chapter 3." announced when starting at verse 1; then play/pause), the selection bar's Listen (the selection, then stop), and the Now Playing bar. The spoken verse gets iOS's accent tint and solid rule and is kept in view (brought to 18% down only once it leaves the screen). Continue to Next Chapter reads on and the reader follows; a selection, Revelation 22 or an End of Chapter timer stops, paused at the start. Queue, skip and end-of-pass rules in `data/listen/ListenQueue.kt` (tested). Rights: listening is local and always allowed, as on iOS; a voice that reads **over the network** is a hand-off and is refused for a translation without `allowExternalHandoff`, with a notice (iOS draws that line for Mi Speaks). **Differences:** the engine can't pause mid-utterance, so play restarts the verse; voices have codes, not names ("Voice IOB"). Emulator-verified: marker advancing 3:1 → 3:11, John 3 → 4 with the reader following, a selection 4:10–13 stopping after 13. Audio itself not heard (the emulator runs `-no-audio`; the engine's callbacks fire regardless) |
| Now Playing bar | ✅ | `ui/listen/NowPlayingBar.kt`: verse, voice · speed, previous / play-pause / next, speed menu, options (voice, Continue to Next Chapter, sleep timer; moon while a timer runs), Stop; notice row with dismiss. Above the selection bar when both are up |
| Voice and speed | ✅ | The installed voices for the text's language (English, as iOS's `textLanguage`), home region first, then quality; saved as `listen.voice` (the engine's voice name), speed 0.5–2× in iOS's seven steps as `listen.speed`, `listen.continue` — iOS keys. Changing either restarts the verse, as iOS does for system voices. Verified: picker lists the emulator's Google voices; SFG and 1.5× applied and persisted |
| Sleep timer | ✅ | Off, 15 Minutes, 30 Minutes, 1 Hour, End of Chapter; a timed one pauses with "Sleep timer ended." Counted on `elapsedRealtime` and checked at every verse, so it holds with the screen off. End of Chapter verified on the emulator (stopped at the chapter's end and cleared itself); the timed options are unit-tested, not waited out |
| Lock screen / headset controls, background audio | ✅ | `ui/listen/ListenPlaybackService.kt`: a media3 `MediaSessionService` (foreground type `mediaPlayback`) over a `SimpleBasePlayer` that mirrors the controller. Verified: notification media controls (verse title, artwork, previous/pause/next, pause from the shade), keeps reading with the screen off (service foreground, type 0x2), headset play/pause and next (`KEYCODE_MEDIA_*`, `HEADSETHOOK`), and an incoming call pausing then resuming. Found and fixed: the engine plays in its own process, so Android gave the headset buttons to nobody ("Media button session is null"); a looping silent track played while reading makes this app the one playing. Audio focus pauses rather than ducks (spoken word); unplugging headphones pauses. Android 13+ notification permission asked once, at the first Listen; reading starts either way. **Not seen:** the keyguard's own media card (the emulator has no lock screen set), and a car/Bluetooth device |
| Personal Voice | ➖ | |
| Mi Speaks Studio voices | ➖ | |

### Study
| Feature | Status | Notes |
|---|---|---|
| Study panel following the tapped verse, back trail | ✅ | `ui/study/`: a 45%/full-height bottom sheet on phones that leaves the text live behind it, a 380 dp side pane from 700 dp wide (checked on `haven_tablet`). Opened from the top bar's Study button and the selection bar's Original Language; follows the selected verse; cross-reference and commentary-link jumps leave a back trail. About Study Resources lists every source's licence |
| Cross references ranked by votes, preview | ✅ | Strongest six, then OT/NT in canonical order, Show All past 40, strength meter, text in the current translation (an online translation previews only what it has cached — never a request). Long-press Copy asks `TranslationRights` first |
| Commentary: Calvin, Gill, JFB, with links | ✅ | Source picker (persisted as `study.commentarySource`), chapter introduction, references in the text are links that jump. Bundled, not an on-demand pack as on iOS |
| Original languages: word, translit, parsing, Strong's, lexicon | ✅ | A fourth Study tab (iOS opens it as its own sheet). Always resolved against the BSB's text, with iOS's notice when another translation is open; STEPBible's required attribution lines always shown beneath the words |
| Context: overview, map, timeline, charts | ✅ | `data/context/ContextStore.kt` ported with every `ContextStoreTests.swift` case (plus chart-decoding guards). When (era band, events), Where (map, places, place detail), Charts (kings, journeys with routes, tribes, feasts), full timeline, large map with place search, Sources & Credits — pushed inside the panel rather than opened in a separate window |
| Map drawn on a Canvas from `Basemap.bin` | ✅ | `ui/study/BibleMap.kt`: the iOS projection, camera, coarse/fine switch, rivers by rank, collision-free priority labels, authored labels, routes with arrows; pan, pinch, +/−/fit, tap a place. `Basemap.bin` added to `syncBundledData` |
| Compare two translations | ✅ | `ui/translations/CompareSheet.kt`, from the translation menu: any two of bundled, imported and online; one-sided verses tinted |

### Translations
| Feature | Status | Notes |
|---|---|---|
| Translations screen | ✅ | `ui/translations/TranslationsSheet.kt`, from the translation menu's Manage Translations…: Included, Online, Added by You (remove), Add a Translation, and About This Translation with what its rights allow. Imported and online translations join the reader's switcher through `BundledTranslations` / `data/translations/TranslationLibrary.kt` |
| eBible.org catalogue (16-ID allowlist) | ✅ | Browse, search, download over `HttpURLConnection` with progress, import under the catalogue's identity (`importIdentity`, tested). Verified live on the emulator: WEB downloaded, 66 books / 31,098 verses, the Luke 17:36 and Acts gaps reported, then read in the reader. Found and fixed: the live CSV starts with a BOM, which made the catalogue fail on Android |
| Import USFM zip / DRM-free ePub, with DRM refusal | 🔧 | Storage Access Framework picker, copy to cache, import, coverage report; DRM refusal is the engine's (`BibleImportError.ProtectedByDRM`), shown in an alert. The picker itself was not exercised on the emulator (no file on it); everything after the copy is the path the catalogue verified |
| Online ESV and API.Bible with the reader's key | 🔧 | `ESVClient`/`APIBibleClient` ported (same endpoints, params, errors) over an `HttpTransport` seam; `OnlineChapterLoader` fetches → `OnlineChapterCache` (500-verse ceiling) → reads back as a `Chapter` the reader renders. 15 tests through a fake transport with the captured responses. Key entry, Keystore-sealed storage (verified: only ciphertext on disk, nothing in logcat), and the ESV entry appearing and leaving with the key, verified on the emulator. **Never run against the live services** (no key) |
| Keys synced across the reader's devices | ⬜ | Block Store |
| Translation rights gate | 🔧 | `data/rights/`: the same rule as iOS (licence line or a package's signed policy, expiry) — tested. `TranslationInfo.rights` carries it; the selection bar and Study's cross-reference Copy ask it; online text is licensed (500-verse quotation, no hand-off); an import's unknown licence behaves as licensed. Export and hand-off gates come with those features |
| `.sabible` reader: signature, per-chapter AES-GCM | ✅ | All 1,189 ASV chapters decrypt to exactly `ASV.sqlite` (31,086 verses, 0 mismatches); tamper, rebinding and wrong-key tests. Tink for Ed25519. |
| `.sabible` sealed search index | ⬜ | Header's index entries are signature-covered but not yet bounds-checked or read |
| Content key wrapped by Android Keystore | ⬜ | Derived from the published seed and held in memory for now |

### Notes import, keepsakes, family
| Feature | Status | Notes |
|---|---|---|
| Life Bible / Tecarta import | 🔧 | `data/notesimport/LifeBibleImport.kt` — 11 tests incl. a real export. Import UI not yet built |
| Paste / CSV notes import | 🔧 | `data/notesimport/PastedNotesImport.kt` — 13 tests. Import UI not yet built |
| Keepsake Bible, opens files made on iOS | 🔧 | `data/keepsake/`: format, ZIP, PBKDF2 600k + AES-GCM. Opens iPhone-made keepsakes (fixtures in `test/resources/keepsake`), writes byte-identical plain files, and iOS opens its protected ones. Create/open UI not yet built |
| Live family sharing | ⬜ | Drive shared file |
| Sync across the reader's Android devices | ⬜ | Drive app-data folder |

### Sharing
| Feature | Status | Notes |
|---|---|---|
| Verse image designer: 8 templates, 3 aspects | ✅ | `ui/share/`: `ShareDesigner.swift` as a sheet — live preview, the eight templates (colours from `ShareStyle.swift`), Square/Story/Wide, typeface menu (the seven faces), Left/Centered, words of Christ in red (off when the passage has none), verse numbers (several verses), wordmark; Save to Photos (MediaStore, `Pictures/Scripture Alone`, no permission), Share Link, Copy Link; Done and Share Image in the header. Choices persist under `share.*` (iOS's `ShareSettingsKey`). One renderer (`ShareCardRenderer`, `android.graphics` + `StaticLayout`) draws the preview, the PNG and a link's card, with `ShareCard.swift`'s metrics and `ShareCardFitter`'s search (largest size that fits, then whole verses trimmed, 12 at most — unit-tested). The PNG is rendered fresh at 2× (2160 px on the long side) and handed to the share sheet through a `FileProvider`. Gated on `permits(VERSE_IMAGES)` and `mayQuote`. Emulator-verified: Parchment/Dawn/Night/Ink with Square/Story/Wide, Domitian, Left; the share sheet with the image preview; the PNG pulled from the cache and from Photos (2160 × 2160) |
| Share links, same format as iOS | ✅ | `data/share/ShareLink.kt`: payload, passage composer, decoder; encodes byte-for-byte as Swift does (tested). Links from the selection bar and the designer carry the designer's `tp`, `f` and `a` (and drop `red` when red letters are off), as iOS's do. An opened link shows the sender's card — its template, face and aspect, from the link's own text, verse numbers found from `k` as the web page finds them; unknown tokens fall back to Parchment / Source Serif / Square — with **Make Image…**, which opens the designer on the passage in the link's style (iOS opens the designer directly). Emulator-verified: Night/Story/Palatino, Linen/Wide/Charter, Olive/Square/Avenir, Minimal/Story/Sans and unknown tokens; a link arriving while the designer is open closes it |
| Deep links `scripturealone://` | ✅ | Intent filter for the custom scheme (`singleTop`, so a link reaches the open reader). `open?ref=` goes to the passage and selects it, across a chapter break too; `#s=` also shows the card. The https share page is deliberately not claimed as an App Link |

### Widgets and Wear OS
| Feature | Status | Notes |
|---|---|---|
| Verse of the Day widget | ✅ | `ui/widget/VerseOfDayWidget.kt` (Glance). Same pick as iOS for every date (`DailyVerseCatalog`, pinned tests), redrawn at local midnight by a non-waking alarm (`WidgetClock`) and when the clock or zone changes. Small / medium / large layouts as the iOS families; the reader's translation where `DailyVerses.json` has it (labelled with what is actually shown — the ASV otherwise), red letters honouring `reader.redLetters`, the page palette in light and dark. Tap opens `scripturealone://open?ref=`. Emulator-verified: all three sizes, dark mode, following a switch to KJV, tap-to-open. Red-letter spans are unit-tested but not seen on screen (the day's passage had none) |
| Favorites & Notes widget with "Next" | ✅ | `ui/widget/FavoritesWidget.kt`, reading the real store (`data/userdata/`) through `UserDataWidgetSource` — a file watch on the database's WAL, so any write is seen — into the same `VerseSnapshot` as iOS (`:shared`, Swift tests ported). New verse every three hours, "Next" nudges; long-press → reconfigure picks Everything / Favorites / Highlights / Notes. Emulator-verified: rotation, Next, reconfigure, empty state, a favorite made in the reader appearing, tap opening and selecting the passage |
| Lock Screen widgets | ➖ | |
| Widget text face | ➖ | The launcher draws widget text and won't load an app's font resources, so widgets use the system serif (Noto Serif) where iOS uses New York — not the reader's Source Serif 4 |
| Wear OS app: VOTD, favorites, notes, reader, translations | 🔧 | `android/wear` (applicationId = the phone's). Reads the same `*-Watch.sqlite` editions, synced from `ScriptureAloneWatch/Resources` at build (tested against all three files). Home, verse (red letters, Speak via `TextToSpeech`, notes on it), books → chapters → chapter (highlight tints, focus verse), favorites, notes, note, translation picker — each verified on the `scripture_wear` emulator (Wear OS 6). Favorites/notes are **read-only**, from the phone's snapshot (no shared store on Android); no favoriting on the watch yet. Speak not heard (emulator runs without audio) |
| Phone ↔ watch (Data Layer) | 🔧 | Phone publishes the translation (with the switch time, `WatchLinkKeys` semantics) and the snapshot as data items (`WearPublisher`); the watch applies them (`PhoneLink`) with iOS's newest-choice-wins rule (`TranslationChoice`, tested). Each side verified alone (the watch reads a snapshot file placed as the Data Layer would; picking a translation on the watch moves tile and complication). **Not verified end to end**: phone and watch emulators may not run together. Imported-translation transfer (a `WatchEdition` file) waits for import on Android |
| Wear OS Tile and complications | ✅ | Tile: VOTD with "Read", fresh until local midnight. Complication: SHORT_TEXT ("PHIL" over "4:6–7"), LONG_TEXT, MONOCHROMATIC_IMAGE, as a week-long timeline of one entry per local day. Both follow the watch's translation (the Apple Watch's always use the ASV). Emulator-verified: tile shown and "Read" opens the verse; complication on an analog face, tap opens the verse, and it switched to BSB with the picker |

### Settings and polish
| Feature | Status | Notes |
|---|---|---|
| Launcher icon: the iOS icon as an adaptive icon, with a themed-icon silhouette | ✅ | Rendered from `AppIcon.icon` by `android/tools/render_launcher_icon.py` (Icon Composer's `ictool`); rerun it when the icon changes |
| Appearance sheet, About, About This Translation | ✅ | `ui/appearance/AppearanceSheet.kt`, from the AA button: `AppearanceView.swift` as a half-height sheet over the live reader (pulls to full height): themes, accent, text size and spacing sliders, layout, the seven faces, Show; then MillerKit's Feedback & Support (email rows), My Other Apps, About (version, privacy policy, app website, **Font Licences**) and About This Translation. Emulator-verified in light and dark. **Not here:** iOS's keepsake/export row (the Keepsake UI isn't built) and the Rate row (no Play listing yet) |
| Feedback and rating | 🔧 | Feedback: Report an Issue / Suggest a Feature / Ask a Question write to MillerKit's suite address with its diagnostics, from the Appearance sheet. Rating: Play in-app review, not yet |
| TalkBack labels, font scale, keyboard shortcuts | ⬜ | |
| English only | ⬜ | Matches iOS |

## Owner actions needed along the way

- **Google Cloud project + OAuth client** (free) — for Drive sync and family sharing.
- **Play Console app record** for `com.blainemiller.scripturealone`.
- **Upload key / Play App Signing**.
