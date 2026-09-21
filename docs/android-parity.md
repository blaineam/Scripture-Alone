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
| User data | Room | Highlights, notes, favorites — the SwiftData models, relational |
| Settings | DataStore (Preferences) | The UserDefaults `reader.*` keys |
| Crypto | JCA (AES-GCM, HMAC, PBKDF2) + Tink (Ed25519, HKDF) + Android Keystore | API 29 has no platform Ed25519; Keystore replaces the Secure Enclave |
| Background work | WorkManager | Family-share refresh, catalogue downloads |
| Build | Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, compileSdk 36, minSdk 29 — Haven's known-good set; warnings are errors |
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
| Apple system typefaces (New York, SF, Charter, Iowan, Georgia, Palatino, Avenir Next) | **Bundled open-licensed equivalents** | Candidates: Source Serif 4 / Literata, Inter, Charis SIL (a Charter derivative), Crimson Pro, Gelasio (Georgia-metric), TeX Gyre Pagella, Nunito Sans. The share-link `f` tokens map to these. |
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
| Seven typefaces | 🔧 | Source Serif 4 (OFL) is the default; the other six not yet |
| Size 12–40 pt, line spacing 1.0–2.0, system font scale | 🔧 | Both persisted (`reader.fontSize`, `reader.lineSpacing`) and applied, each with a stepper (1 pt, 0.1×) in the interim appearance menu until the Appearance sheet's sliders exist |
| Themes: Auto, Light, Sepia, Dark, Black | ✅ | Exact palette values from `ReaderStyle.swift` |
| Seven accent colors | ✅ | |
| Show toggles: red letters, verse numbers, headings, footnotes | ✅ | Persisted under the iOS keys; in the interim appearance menu until the Appearance sheet |
| Auto-scroll (16/28/44/64 pt/s), continues into next chapter | ⬜ | |
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
| Selection bar | ⬜ | |
| Highlights, five colors | ⬜ | Room |
| Notes with multiple anchors, inline markers, Notes panel | ⬜ | |
| Favorites | ⬜ | |
| Export: PDF, Markdown, Markdown folder, plain text | 🔧 | Markdown and text ported (`NotesTextExport.kt`), with the publisher notice on every export. PDF (`android.graphics.pdf.PdfDocument`) and the sheet not yet built |
| Quotation-limit gate on copy and share | 🔧 | `TranslationRights.mayQuote` ported (500 for licensed text); copy/share UI not yet gated |

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
| Read aloud verse by verse, spoken verse marked | ⬜ | `TextToSpeech` |
| Voice and speed | ⬜ | |
| Sleep timer | ⬜ | |
| Lock screen / headset controls, background audio | ⬜ | `MediaSession` + foreground service |
| Personal Voice | ➖ | |
| Mi Speaks Studio voices | ➖ | |

### Study
| Feature | Status | Notes |
|---|---|---|
| Study panel following the tapped verse, back trail | ⬜ | Side pane on tablets, bottom sheet on phones |
| Cross references ranked by votes, preview | 🔧 | Store done, from bundled `CrossReferences.sqlite`, matching the pack exactly; UI not yet |
| Commentary: Calvin, Gill, JFB, with links | 🔧 | Store done; all 3,192 bodies inflate; UI not yet |
| Original languages: word, translit, parsing, Strong's, lexicon | 🔧 | Store done; all 443,625 words checked against the BSB text; UI not yet |
| Context: overview, map, timeline, charts | ⬜ | |
| Map drawn on a Canvas from `Basemap.bin` | 🔧 | `data/context/Basemap.kt` reads the shipped `Basemap.bin` (tested). Canvas drawing and `ContextStore` not yet ported |
| Compare two translations | ⬜ | |

### Translations
| Feature | Status | Notes |
|---|---|---|
| Translations screen | ⬜ | |
| eBible.org catalogue (16-ID allowlist) | 🔧 | `data/catalog/`: CSV parse, curation (same 16 allowed / 13 excluded IDs), language matching — 25 tests. Download UI not yet built |
| Import USFM zip / DRM-free ePub, with DRM refusal | 🔧 | `data/importer/`: all 57 Swift tests ported; stores byte-identical to the Swift engine's on the ASV/BSB/KJV USFM zips. File picker and import UI not yet built |
| Online ESV and API.Bible with the reader's key | 🔧 | `data/online/`: HTML parsing and the chapter cache with Crossway's 500-verse ceiling, LRU eviction and VACUUM on clear (24 tests, incl. real captured responses). Networking and key entry not yet built |
| Keys synced across the reader's devices | ⬜ | Block Store |
| Translation rights gate | 🔧 | `data/rights/`: the same rule as iOS (licence line or a package's signed policy, expiry) — tested; not yet asked by the UI |
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
| Verse image designer: 8 templates, 3 aspects | ⬜ | |
| Share links, same format as iOS | 🔧 | `data/share/ShareLink.kt`: payload, passage composer, decoder; encodes byte-for-byte as Swift does (tested). Share sheet and card UI not yet built |
| Deep links `scripturealone://` | 🔧 | `AppLink.parse` handles `open?ref=` and `#s=` links; intent filter and navigation not yet wired |

### Widgets and Wear OS
| Feature | Status | Notes |
|---|---|---|
| Verse of the Day widget | 🔧 | `data/daily/`: same pick as iOS for every date (pinned tests); `DailyVerses.json` synced at build. Glance widget not yet built |
| Favorites & Notes widget with "Next" | ⬜ | Glance |
| Lock Screen widgets | ➖ | |
| Wear OS app: VOTD, favorites, notes, reader, translations | ⬜ | Same `*-Watch.sqlite` files |
| Wear OS Tile and complications | ⬜ | |

### Settings and polish
| Feature | Status | Notes |
|---|---|---|
| Launcher icon: the iOS icon as an adaptive icon, with a themed-icon silhouette | ✅ | Rendered from `AppIcon.icon` by `android/tools/render_launcher_icon.py` (Icon Composer's `ictool`); rerun it when the icon changes |
| Appearance sheet, About, About This Translation | ⬜ | |
| Feedback and rating | ⬜ | Play in-app review |
| TalkBack labels, font scale, keyboard shortcuts | ⬜ | |
| English only | ⬜ | Matches iOS |

## Owner actions needed along the way

- **Google Cloud project + OAuth client** (free) — for Drive sync and family sharing.
- **Play Console app record** for `com.blainemiller.scripturealone`.
- **Upload key / Play App Signing**.
