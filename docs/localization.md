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
Louis Segond numbers psalm titles as verse 1 and Malachi 4 as 3:19–24 (31,170 verses), and the
Korean text follows NRSV numbering. These need a mapping to the shared keys before a reader's
marks line up across translations (Phase 2).

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
