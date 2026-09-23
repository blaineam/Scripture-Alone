# Localization: the big 8

Goal: a reader whose device is set to Simplified Chinese, Japanese, German, French, Spanish,
Korean, Brazilian Portuguese or Italian sees **no English** — not in the interface, not in the
Bible, not in the study tools — on iPhone, iPad, Mac, Apple Watch, widgets, Android and Wear OS
alike (platform parity is part of the definition of done).

## Decisions (owner, 2026-09-23)

| Question | Decision |
|---|---|
| The Bible for each locale | The whole Bible, **66-book Protestant canon** (39 + 27), translated from the Hebrew and Greek — no Apocrypha, no drafts, no paraphrases. Table below. |
| How it reaches the reader | **Downloaded on first launch** (on-demand pack). It must never block: the ASV opens at once, a banner in the reader's language says their Bible is on its way, and the reader switches when it lands — with Try Again on failure. App Review rejected 1.0.0 b40 for a launch that waited on a pack. |
| Commentary (Calvin, Gill, JFB — English only) | **Hidden** outside English for now. Per-language commentaries (e.g. Calvin's own French) may come later. |
| Original Languages lexicon (English Strong's definitions) | **Words and parsing only** outside English: Hebrew/Greek, transliteration, parsing, Strong's number; definitions hidden. |
| Interface strings | **Levi** fills all 8; Bible-specific terms (book names, Keepsake, Study) reviewed by hand. |

## The Bibles

All complete (1,189 chapters), no deuterocanon, redistributable.

| Locale | Translation | License | Source |
|---|---|---|---|
| zh-Hans | Chinese Union Version, simplified (和合本, 1919) | Public domain | eBible `cmn-cu89s` |
| ja | 文語訳 (Meiji OT 1887, from the 1953 printing + Taishō NT 1917) | Public domain | CrossWire `JapBungo` |
| de | Luther 1912 | Public domain | eBible `deu1912` |
| fr | Louis Segond 1910 | Public domain | eBible `fraLSG` |
| es | Reina-Valera 1909 | Public domain | eBible `spaRV1909` |
| ko | Korean Revised Version (개역한글, 1952/61) | Public domain (from Wikisource) | CrossWire `KorRV` |
| pt-BR | Bíblia Livre (Almeida 1819, Textus Receptus, 2018) | CC BY 4.0 — attribution required | eBible `porbr2018` |
| it | Riveduta 1927 (Luzzi) | Public domain | eBible `ita1927` |

Rejected: eBible's only complete Japanese Bible, `jpnm` ("Freedom Bible"), is marked by its own
page as a translation **draft**; the only eBible Korean is the archaic 1910 text.

**Japanese: the 文語訳 (owner, 2026-09-23).** The standard modern text is the Colloquial Japanese 口語訳 (1954/55). CrossWire's
`JapKougo` module says its copyright expired on 2006-01-01 (Japan's 50-year term for works
published by an organization), but the module is **missing whole chapters** — Matthew 25–28,
John 19, Romans 10, Psalm 130, Proverbs 30 and more (473 verses) — so it cannot be the source.
Japanese Wikisource's 口語訳 pages are under a **deletion request on copyright grounds**
(削除依頼中), so the text's status is disputed there. So the app ships the 文語訳 instead:
unquestionably public domain, and literary Japanese of 1887/1917 rather than modern. The CrossWire
module is complete — 31,099 verses; Exodus 7:25, 2 Samuel 19:25 and 2 Chronicles 2:13 are printed
with a neighbouring verse, not missing — and its book names are the translation's own (ヱレミヤ記,
使徒行傳, …), from Japanese Wikisource's tables of contents.

**Versification.** Highlights, notes and cross-references are keyed by KJV-style verse numbers.
**Owner, 2026-09-23: each reader sees their own Bible's numbering** — a French pastor's
"Psaume 51, verset 12" is Psalm 51:12 on screen — while highlights, notes and cross-references
stay on the one KJV key space underneath. Each database carries a `kjv_map` table (only the verses
that differ) built by `build_bibles.py`:

- Same highest verse number as the KJV → identity (an omitted verse is a gap, not a shift).
- Psalm titles numbered as verses (Segond) → fold onto the KJV's verse 1.
- Anything else → aligned by verse length (Gale & Church), whole book, banded, with merges and
  splits priced above length noise and up to ten verses wide.

Result: LSG 1,415 verses renumbered (mostly Psalms; also Exodus 7–8, Leviticus 5–6, 1 Samuel
20–24, 1 Kings 4–5, Job 38–41, Isaiah 8–9 and 63–64, Hosea, Jonah, Micah, Nahum, Mark 9, …);
RVR1909 164; KRV 5; CUVS 4; BUNGO 1; the German, Portuguese and Italian none. Checked against
SWORD's independent Segond table (GPL, used only to check, not copied): 31,149 of 31,170 agree,
and reading the 21 that don't shows ours right in 2 Chronicles 13:23 (SWORD maps it to a verse the
KJV lacks) and the rest single-verse edges. `--check` pins the landmarks.

Source defects this surfaced: eBible's Reina-Valera lost eight verse markers in Job 39:30, which
holds the KJV's 39:27–40:5 (printed RV 39:30–35) — mapped as one verse over nine.

## Built

`Tools/build_bibles.py --check` builds all of these alongside the English three and asserts,
for each: 66 books, 1,189 chapters, a plausible verse count, John 3:16 as that translation reads
it, native book names and no leaked markup. `Tools/sword_to_usfm.py` converts the CrossWire
module. Building them also exposed a nondeterminism — the same USFM gave different red-letter
layouts from one run to the next (styles iterated as a set) — now fixed; the English databases'
styling coverage is character-for-character unchanged.

## English that has to go

- **Interface**: ~460 literals on iOS (app, watch, widgets); ~145 hard-coded in Compose on Android
  (strings.xml holds almost nothing).
- **Book names and the passage parser** (`Canon.swift`, `ReferenceParser.swift`): English only.
- **Search**: FTS5 `unicode61` cannot segment Chinese or Japanese — needs a trigram (or
  segmenting) index for CJK translations. See also the CJK sentence-splitting trap.
- **Verse of the Day** (`DailyVerses.json`): ASV/BSB/KJV text only, English themes.
- **Maps, timeline, charts** (`Context.sqlite`: places, events, eras, charts, labels): English.
- **Commentary / lexicon definitions**: hidden per the decisions above.
- **Store**: App Store + Play listings and screenshots in 8 locales. The website is already in 9.

## Phases

1. **Bibles** — sources fetched, CrossWire modules converted to USFM, `build_bibles.py` builds all
   8, `--check` asserts known verses and the 66-book canon; on-demand packs (iOS Background
   Assets, Android Play Asset Delivery); watch companion editions.
2. **Core** — localized book names and parser per locale; CJK search; locale → Bible selection at
   first launch with the non-blocking download.
3. **Interface** — string catalogs (iOS) and `strings.xml` (Android) extracted, Levi fill,
   spot-check.
4. **Study content** — Context.sqlite labels translated; commentary and lexicon definitions gated
   by language; Verse of the Day per locale.
5. **Store and site** — listings, screenshots, website copy.

## Release checklist (owner, 2026-09-23)

**How it ships:** tag-driven CI, as Haven does (`docs/RELEASING.md`). Actions ▸ cut-release
version `1.1.0` tags `v1.1.0`. Then `android.yml` publishes the phone + Wear OS bundles to Play
production, and `apple-store.yml` submits Xcode Cloud's build of that commit with every pending
asset pack on the same submission (≤ 10; it refuses 13 while 1.0.0 is still in review). `-rc.N`
tags go to testers only. The owner has to add the repo secrets listed in RELEASING.md's
"One-time setup" first; until then the lanes skip, and `rocket submit "Scripture Alone"` (which
also attaches the packs) is the by-hand path.

Ship when iOS is done, with Android at **full feature parity** in the same wave. 1.0.0 (build 71)
is in App Review; this goes out as the next version once it is approved — App Store Connect
allows one version in review at a time.

**Apple (App Store)**
- [ ] **1.1.0** with the localized app, watch and widgets — submitted the moment 1.0.0 is approved.
      Not a 1.0.0 resubmission: Apple allows at most **10 asset packs per review submission**
      (100 active per app), and before the first approval every pack rides with the version —
      1.0.0's own 5 plus these 8 would be 13.
- [ ] Asset packs, all `onDemand`, **in the same review submission as the version** (≤ 10 items per
      submission; identifiers without dots; never archive one — it is permanent):

      | Pack | File | Locale |
      |---|---|---|
      | `cuvs` | Bibles/CUVS.sqlite | zh-Hans |
      | `bungo` | Bibles/BUNGO.sqlite | ja |
      | `lut1912` | Bibles/LUT1912.sqlite | de |
      | `lsg` | Bibles/LSG.sqlite | fr |
      | `rvr1909` | Bibles/RVR1909.sqlite | es |
      | `krv` | Bibles/KRV.sqlite | ko |
      | `blivre` | Bibles/BLIVRE.sqlite | pt-BR |
      | `riv1927` | Bibles/RIV1927.sqlite | it |

      **Uploaded 2026-09-23: version 1 of all eight, READY_FOR_TESTING.** Still to do: add them to
      the review submission with the version.

      Plus new versions of `bsb` / `kjv` only if their databases change (e.g. a `kjv_map`
      table) — a pack update reaches app versions already installed, so keep it readable by them.
- [ ] Store listing (name, subtitle, promo, description, keywords, what's new) in all 8 locales —
      **no price words** (2.3.7).
- [ ] Screenshots and app previews **rendered in each locale** — iPhone, iPad, Apple Watch — with
      that locale's Bible on screen.

**Google Play**
- [ ] Same features on Android and Wear OS.
- [ ] Play Asset Delivery packs for the same 8 Bibles (`on-demand`).
- [x] **Closed** testing sent for review 2026-09-23: phone versionCode 2 on Alpha, Wear 1,000,004 on
      the Wear OS closed-testing track, with release notes in all nine languages. Phone build 2 may
      predate the phone→watch edition transfer (396d773); versionCode 3 carries it for certain.
- [ ] **Internal** testing: never set up — needs an email tester list (Groups aren't accepted).
- [x] Store listing in all 8 locales — sent for review 2026-09-23.
- [ ] Android screenshots per locale (listing currently shows the English set).

**Website** (owner: "the website also needs to support the locales")
- [ ] Scripture Alone page (already in all 9 languages): the Bible each locale gets, numbering,
      search — through the i18n dictionaries.
- [ ] Share-link card page (`share.js`): its messages ("This link needs a newer version…") in the
      8 languages, and verse cards in each locale's Bible.
- [ ] `security/` white paper: English-only **by an earlier decision** (a mistranslated security
      claim could say something untrue) — confirm with the owner before translating.

**Search** — done at build time: zh-Hans, ja and ko Bibles use FTS5's `trigram` tokenizer
(`meta.tokenizer`); the app must send those a substring query, and `LIKE` for queries under three
characters (二-character Chinese words are common).

## Progress (2026-09-23)

Done, iOS/Core (all `[ci skip]` on main; 1.0.0 b71 is in review):
- Phase 1 — the eight Bibles, built and checked; build made deterministic.
- Numbering — `kjv_map` in each database; `VerseNumbering` in Core; the app stores/looks up KJV
  keys and draws native numbers (highlights, notes, favorites, study, compare, share links, slides,
  listen, search, position, translation switches).
- Search — trigram for zh/ja/ko with LIKE under three characters; search hits carry their KJV key.
- Packs — `AssetPack` has the eight; manifests in Tools/asset-packs; first launch prefers the
  device language's Bible (non-blocking; verified: a fresh French install opens in Louis Segond).
- Book names — `BookNames` (generated table: names from each Bible, standard abbreviations);
  `BookID.name` follows the Bible being read; the parser reads every language (3章16節, 3장 16절,
  "Joh 3,16", full-width digits, per-language priority for ambiguous abbreviations).

Android (2026-09-23): core as above; then every user-visible string in `app/src/main/res/values/strings.xml`
(~835; Levi fills `values-*` — it reads `<string>` only, so counts are `…_one`/`…_other` pairs picked by
`text/AppText.kt`) and `wear/src/main/res/values/strings.xml` (Levi's android surface covers only the phone's
res dir); Listen's voice follows the Bible's `meta.language`; `ContextStore` reads the `translations` table
(missing table = English); commentary and lexicon English hidden outside English (`AppLanguage`); Verse of
the Day `themes`; per-app language via `generateLocaleConfig`.

Wear OS (2026-09-23), as the Apple Watch: the phone builds a locale Bible's watch edition (`WatchEditionBuilder`
in `:shared` — meta with `language`, books, chapters, verses, `kjv_map`) once its pack is on the phone and sends
it over the Data Layer (`/scripturealone/edition/<ID>`); the watch stores it in `files/editions`, lists it in the
picker and follows the phone's choice. It draws native verse numbers through `VerseNumbering` while everything
stored or routed stays KJV keys; book names follow the edition's `meta.language`; Speak uses it; Verse of the
Day (app, tile, complication) shows the device language's Bible from `DailyVerses.json`. Emulator-verified with
an adb-pushed LSG edition ("Lire Jean 3", "Psaumes 51:12" for KJV 51:10); the phone→watch transfer itself is
not yet verified on devices.

Next: interface strings (Levi) · Context.sqlite labels · commentary/lexicon gating · Verse of the
Day per locale · watch + widgets (editions carry `kjv_map`; `BookNames` from the snapshot; Wear OS done,
phone→watch transfer unverified on devices) ·
Android parity for all of the above · store listings + localized screenshots · packs uploaded and
in the submission · website per-locale Bible info.

## Added scope (owner, 2026-09-23) — ships in the same release

- **Spotlight**: "Notes in Spotlight" / "Favorites in Spotlight" toggles (off by default — notes
  can be personal); indexed as App Entities; a Siri/Spotlight result opens right to the note or
  favorited verse.
- **App Intents / Shortcuts** for the popular features: verse image, verse in a chosen translation,
  create/find/open notes, favorite/unfavorite/is-favorite, Verse of the Day, open passage, search,
  continue reading, listen. Phrases localized (AppShortcuts.xcstrings).
- **Deep links**: any verse from a standard reference — OSIS (`John.3.16`, `urn:osis:John.3.16`) or a
  plain reference in any of the nine languages — via `scripturealone://open?ref=…` and
  `scripturealone://passage/<OSIS>`. (iOS cannot claim the `urn:` scheme itself.)
- **Verse of the Day tap** opens the full app at that verse — the widget already does
  (verified 2026-09-23); the Verse of the Day shortcut must too.
- **Android parity** for all of the above: App Shortcuts, AppSearch-based indexing with the same
  toggles, the same deep-link forms.
