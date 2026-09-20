# Original-language data: what can actually be shipped

Research brief, checked against primary sources on **2026-09-19**. No code was written and nothing was
committed. Every licence below is quoted from the source's own words; where a licence says "non-commercial"
or "personal use" it is called out as unusable rather than glossed.

## Bottom line

The single feature the app exists for — *showing how close the English is to the original* — is already
fully backed by one public-domain file the project is entitled to use today: **`bsb_tables.tsv` from
bereanbible.com**. It is 85.5 MB of TSV, 754,648 rows, **437,587 word-level records** covering Genesis to
Revelation, and each record carries the Hebrew or Greek word, its transliteration, its full morphological
parsing, its Strong's number, *and* the Berean Standard Bible English that renders it — in BSB word order,
with a separate sort key giving original-language order. The BSB is already bundled, and the table
reconstructs the bundled BSB verse text **exactly for 30,937 of 31,086 verses (99%)** once its footnote
callers are stripped. Add **STEPBible's TBESH + TBESG** (CC BY 4.0) for glosses and lemma definitions —
they cover **100% of the 13,876 distinct Strong's numbers the table uses** — and both the tap-a-word
feature and a genuine interlinear line are done, for a measured **≈11–12 MB** added to the bundle. The
third feature, a real critical apparatus, is the one to cut: an apparatus of *printed editions* is free,
tiny and honest (SBLGNT's apparatus is 468 KB under CC BY 4.0), but a *manuscript* apparatus at NA28/ECM
quality is not obtainable on acceptable terms for the New Testament and **does not exist free at all for
the Old Testament**. Do not promise "the manuscript evidence"; promise "which printed editions, and which
Greek the KJV translators had".

---

## 1. Strong's-tagged English text

### ✅ BSB Translation Tables — the one to use

- **Data**: <https://bereanbible.com/bsb_tables.tsv> (85,525,373 bytes, 754,648 rows), linked as
  "BSB Translation Tables - tsv" from <https://berean.bible/downloads.htm>. An `.xlsx` twin exists;
  take the TSV.
- **Licence** (<https://berean.bible/terms.htm>, dated April 30, 2023): *"The Berean Bible and Majority
  Bible texts are officially dedicated to the public domain as of April 30, 2023. All uses are freely
  permitted."* And: *"By definition all public domain materials may be freely reproduced, integrated, and
  adapted for both free and commercial resources."* Attribution is *"appreciated but not required"*. One
  request to honour: *"For derivative works that vary from the official text, we respectfully request that
  the Berean name is not used."* — i.e. keep the BSB text verbatim if the app calls it BSB, which it
  already does. <https://berean.bible/licensing.htm> adds *"Licensing is not required for any use."*
- **Coverage**: both testaments. 300,669 Hebrew/Aramaic rows, 138,131 Greek rows; 437,587 of those carry a
  Strong's number (the rest are padding rows and punctuation slots).
- **Alignment quality**: genuine word-level alignment to an English text, in **both** directions. Rows are
  in BSB (English) order; columns `Heb Sort` and `Greek Sort` give the original-language order. Column 23
  layout: `Heb Sort, Greek Sort, BSB Sort, Verse, Language, WLC/Nestle Base (plain), WLC/Nestle Base
  (with edition brackets), Translit, Parsing (abbrev), Parsing (expanded), Str Heb, Str Grk, VerseId, Hdg,
  Crossref, Par, Space, begQ, BSB version, pnc, endQ, footnotes, End text`. `VerseId` is populated only on
  the first row of each verse. Genesis 1:1 shows the mechanism: Hebrew order 1,3,4,2,5,6,7 against English
  "In the beginning / God / - / created / the heavens / and / the earth".
- **Verified join quality** (measured against the bundled `BSB.sqlite`): concatenating the `BSB version`
  column per verse and normalising reproduces the bundled verse text for **30,937 / 31,086 verses = 99%**
  after removing the literal `vvv` footnote callers. The 149 mismatches are Psalm superscriptions (in the
  table, separate in the USFM), a handful of rows with embedded `<p class="indent2">`-style markup, and a
  small number of 3rd-printing textual differences. All are identifiable and can be dropped or patched at
  build time rather than silently mis-highlighting a word.
- **Format/size**: plain TSV; 20.1 MB of useful payload; **7.63 MB** when the interlinear record
  (original word, transliteration, Strong's, parsing id, English gloss) is DEFLATE-compressed per chapter
  across the 1,189 chapters, which is exactly the storage pattern `build_study.py` already uses.
- **App Store**: yes. Public domain, no conditions.

### ✅ eBible `eng-kjv2006` — the KJV with Strong's, and already a build input

- **Data**: <https://ebible.org/Scriptures/eng-kjv2006_usfm.zip> (2,461,781 bytes). USFM 3 with inline
  `\w In|strong="G1722"\w*` tags. Measured **349,308** Strong's tags (227,196 `H`, 122,112 `G`) across 66
  books.
- **Licence**: eBible's catalogue (<https://ebible.org/Scriptures/translations.csv>) lists
  `eng-kjv2006` with `Redistributable = True` and `Copyright = public domain`. The edition's own
  `copr.htm` reads: *"You may copy the King James Version of the Holy Bible freely."* It carries the
  standard UK caveat: *"Letters patent issued by King James with no expiration date means that to print
  this translation in the United Kingdom or import printed copies into the UK, you need permission…
  This royal decree has no effect outside of the UK, where this work is firmly in the Public Domain."*
  Data courtesy of the CrossWire Bible Society and eBible.org.
- **Alignment quality**: word-level but **crude and KJV-era**. Function words are frequently untagged, and
  some tags are concordance mappings rather than alignments — Genesis 1:1 tags the English "and" with
  `H0853`, which is אֵת, the direct-object marker. Good enough for "tap a word, see its Strong's entry";
  not good enough to drive an interlinear line.
- **App Store**: yes.

### ⚠️ CrossWire `kjv` SWORD module — same data, worse packaging

From `mods.d/kjv.conf` in <https://www.crosswire.org/ftpmirror/pub/sword/raw/mods.d.tar.gz>:
`Description=King James Version (1769) with Strongs Numbers and Morphology and CatchWords`,
`Feature=StrongsNumbers`, `DistributionLicense=GPL`, `TextSource=https://gitlab.com/crosswire-bible-society/kjv`.
The About note says *"The rights to the base text are held by the Crown of England."* GPL sits fine
alongside AGPL-3.0-or-later, but reading it means implementing a zText/OSIS module reader for no gain over
the eBible USFM. Skip.

### ✅ STEPBible TAGNT — New Testament only, but the best variant data of the set

- **Data**: two files in
  <https://github.com/STEPBible/STEPBible-Data/tree/master/Translators%20Amalgamated%20OT%2BNT>:
  `TAGNT Mat-Jhn … CC-BY.txt` (14,189,032 bytes) and `TAGNT Act-Rev … CC-BY.txt` (15,939,932 bytes);
  30.1 MB total.
- **Licence**: the repo README is headed *"STEPBible Data Repository **CC BY 4.0**"*, and each data file
  repeats: *"Data created by www.STEPBible.org based on work at Tyndale House Cambridge (CC BY 4.0) …
  This licence allows you to: * Include any part of this data in software or publications without
  requesting permission * Download the data and reformat it for your application, without changing the
  data … (You MAY make changes yourself, but you should include a note of changes that can be viewed by
  those who use your new data)"*. **Read this next sentence carefully**: *"Refer others to
  github.com/STEPBible as the source of the data. Please do not redistribute it yourself."* Taken
  literally that conflicts with CC BY 4.0's own "No additional restrictions" clause, and with the README's
  explicit permission to include any part in software. The sane reading — and the one the README's first
  bullet supports — is that they mean "don't mirror the raw files", not "don't compile the data into an
  app". Ship it, attribute "STEP Bible" linked to www.STEPBible.org as the README asks, and record the
  wording in `docs/` so the decision is on paper.
- **Alignment quality**: word-level, against an English gloss — *"English: Based on Berean Study Bible,
  with permission, as at 1-July-2019 and adapted for this work."* So it is an alignment to a
  BSB-derived gloss, not to the running text of any bundled translation. `bsb_tables.tsv` does that job
  better because its English column *is* the bundled BSB.
- **What makes it uniquely valuable**: per word it gives the list of editions containing that word
  (`NA28+NA27+Tyn+SBL+WH+Treg+TR+Byz`), a word-type code (`NKO`, `N(k)O`, `K(O)`, `N(O)`, `O`…), and
  variant notes (`v` = variant reading, `^` = extra text). Its own summary table: *"133608 words that are
  identical in virtually all manuscripts … 94% of the total words"*, *"4164 words found in Traditional but
  not Ancient manuscripts"*, *"896 words found in Ancient but not Traditional manuscripts"*. Mark 16:9 is
  tagged `KO` with `^` on every word — exactly the "how close is this to the manuscripts" signal the app
  wants.

### ⚠️ STEPBible TAHOT — Old Testament, no English

Four files, 70,208,423 bytes total. *"The Leningrad codex based on Westminster via OpenScriptures,
corrected from colour scans, with full morphological and semantic tags for all words, prefixes and
suffixes."* Same CC BY 4.0. It is lemma-and-morphology tagging of the Hebrew, not an alignment to any
bundled English edition, and it is 70 MB to `bsb_tables.tsv`'s 85 MB for *both* testaments *with* English.
Not needed.

### ❌ STEPBible TTESV — non-commercial, and the wrong text anyway

The file is literally named
`TTESV - Tyndale Translation tags for ESV - TyndaleHouse.com STEPBible.org CC BY-NC.txt` (4,434,578 bytes).
**CC BY-NC.** Non-commercial terms are not usable in an App Store app: the app is free, but distribution
runs through a paid developer programme on a commercial storefront, and no licence review should turn on
that argument. Separately, the ESV text itself is Crossway copyright and could never be bundled, so the
tags have nothing to attach to. Dead end on two counts.

### ⚠️ OpenScriptures HebrewBible/morphhb — Old Testament, no English

- **Data**: <https://github.com/openscriptures/morphhb> — `wlc/` (OSIS XML per book), plus `index.js`
  (9,850,045 bytes, the npm JSON build).
- **Licence** (README + `LICENSE.md`): *"Lemma and morphology data are licensed under a Creative Commons
  Attribution 4.0 International license. For attribution purposes, credit the Open Scriptures Hebrew Bible
  Project. The text of the WLC remains in the Public Domain."* `LICENSE.md` fixes the attribution string:
  *"You must attribute the work as follows: 'Original work of the Open Scriptures Hebrew Bible available at
  https://github.com/openscriptures/morphhb'"*, and adds *"for any purpose, even commercially"*.
- **What it is not**: lemma tagging of the original only. There is **no English text and no alignment**.
  Also note the README's warning: *"any uses of the OSHB should avoid NFC normalization."*
- **Greek equivalent**: OpenScriptures has no comparable Greek repository. The de-facto equivalents are
  `morphgnt/sblgnt` (SBLGNT text CC BY 4.0; *"the morphological parsing and lemmatization is made
  available under a CC-BY-SA License"* — and it carries **no Strong's numbers**, only lemma + parsing code)
  and `biblicalhumanities/Nestle1904` (**CC0**, *with* Strong's — see §3).
- **App Store**: yes, but it does not serve feature (a).

### ⚠️ unfoldingWord ULT / Door43 aligned texts — the best alignment, with a share-alike string

- **Data**: <https://git.door43.org/unfoldingWord/en_ult> (repo ~126 MB; one USFM per book). USFM 3
  alignment milestones, e.g. from `65-3JN.usfm`:
  `\zaln-s |x-strong="G42450" x-lemma="πρεσβύτερος" x-morph="Gr,NS,,,,NMSC" x-occurrence="1" x-occurrences="1" x-content="πρεσβύτερος"\*\w elder|…\w*\zaln-e\*`
- **Alignment quality**: the best of everything surveyed — true many-to-many phrase alignment, with the
  original word (`x-content`), lemma, Strong's and morphology all present, and nested spans where one Greek
  word covers several English words.
- **Licence** (`LICENSE.md`): *"This work is made available under the Creative Commons
  Attribution-ShareAlike 4.0 International License."* Plus a trademark clause that matters: *"unfoldingWord®
  is a registered trademark of unfoldingWord… you may copy and redistribute this unmodified work as long as
  you keep the unfoldingWord® trademark intact. If you modify a copy or translate this work, thereby
  creating a derivative work, you must remove the unfoldingWord® trademark."* And: *"You must also make
  your derivative work available under the same license (CC BY-SA)."*
- **The catch**: the ULT is *based on* the ASV (*"The unfoldingWord® Literal Text is based on The American
  Standard Version, which is in the public domain."*) but it is **not** the ASV. Shipping it means adding a
  fourth translation, not tagging the third. CC BY-SA is workable — the app's AGPL code is not a derivative
  of the text, and a re-encoded SQLite of the text is an adaptation that must stay CC BY-SA, which is fine
  for a public repo — but it is a standing obligation the other options don't carry.
- **Verdict**: hold in reserve. If the owner ever wants a *modern* literal text with best-in-class
  alignment, this is it. It is not the cheapest route to the three features.

---

## 2. The lexicon behind the numbers

### ✅ STEPBible TBESH + TBESG — use these

- **Files**: in <https://github.com/STEPBible/STEPBible-Data/tree/master/Lexicons>
  - `TBESH - Translators Brief lexicon of Extended Strongs for Hebrew - STEPBible.org CC BY.txt` — 3,288,045 bytes
  - `TBESG - Translators Brief lexicon of Extended Strongs for Greek - STEPBible.org CC BY.txt` — 4,736,912 bytes
- **Licence**: CC BY 4.0, same header block quoted in §1 (including the *"Please do not redistribute it
  yourself"* request).
- **What each row gives**: `eStrong, dStrong(+relation), uStrong, Hebrew/Greek form, transliteration,
  morph class, one-word gloss, full definition`. TBESG: *"The Brief lexicon is based on the Abbott-Smith
  definitions, and is edited to conform with the extended Strongs. For a few words where Abbott-Smith lacks
  a definition, one is supplied from MiddleLiddel (MD) or STEPBible scholars."* TBESH: *"Abridged BDB linked
  to extended Strongs"*; its glosses *"were created by Tyndale scholars"*.
- **Measured coverage and size** (against the 13,876 distinct plain Strong's numbers in `bsb_tables.tsv`,
  after zero-padding the keys to `H0430`/`G3056` form): **13,876 / 13,876 = 100%**.
  - gloss layer only (form, transliteration, morph class, one-word gloss): 0.77 MB raw → **0.23 MB** DEFLATE
  - including the full lemma entry: 6.33 MB raw → **1.62 MB** DEFLATE
- **Gotcha**: extended Strong's (`H0430G`, `G2264H`) must be collapsed to the base number to join against
  `bsb_tables.tsv`, which uses plain Strong's. Several extended entries share one base — keep them all and
  show them as sub-senses, which is a feature, not a problem.

### Strong's own dictionary — the digitisation is the question, not the work

James Strong's 1890 *Concise Dictionary* is public domain everywhere. The transcriptions are not all
equally documented.

- **Hebrew — ✅ `openscriptures/HebrewLexicon/HebrewStrong.xml`, 2,749,042 bytes.** `readme.md`: *"These
  files are released under the Creative Commons Attribution 4.0 International license. The actual text of
  Brown, Driver, Briggs and Strong's Hebrew dictionary remain in the public domain. For attribution
  purposes, credit the Open Scriptures Hebrew Bible Project."* Clean, structured (`<w pos xlit pron>`,
  `<source>`, `<meaning><def>`, `<usage>`). Use this one if Strong's own wording is wanted.
- **Greek — ⚠️ `openscriptures/strongs` has no licence file.** GitHub's API reports `license: None` for the
  repository. The only statement is a comment header inside
  `greek/strongs-greek-dictionary.js` (1,200,839 bytes): *"JSON version … Copyright 2009, Open Scriptures.
  CC-BY-SA. Derived from XML."* and, for the XML it derives from, *"The XML version of this work was
  prepared in 2006 by Ulrik Petersen … Ulrik Petersen welcomes bugfixes to the text."* The same underlying
  text ships as CrossWire's `strongsgreek` module with `DistributionLicense=Public Domain` and
  `TextSource=http://www.bf.org/`. So: the *work* is PD, the *JSON conversion* claims CC BY-SA, and the
  repo says nothing. `greek/StrongsGreekDictionaryXML_1.4.zip` (795,608 bytes) is the cleaner artefact.
  If Strong's Greek is wanted, prefer the XML and record the CC BY-SA claim; or skip it entirely, because
  TBESG already covers every Greek number with a better definition under a licence that is unambiguous.
- **`hebrew/StrongHebrewG.xml`** (6,436,400 bytes) is the OSIS packaging of the same Hebrew dictionary,
  edited by David Troidl.

### ✅ Dodson — small, CC0, Greek only, beta-code

- `biblicalhumanities/Dodson-Greek-Lexicon`: `dodson.csv` 539,071 bytes, `dodson.xml` 1,315,372 bytes.
- README: *"This lexicon, in all of its forms, is in the public domain."* The `LICENSE` file (6,554 bytes)
  is the full **CC0 1.0 Universal** text — *"the person associating CC0 with a Work … voluntarily elects to
  apply CC0 to the Work and publicly distribute the Work under its terms"*.
- CSV columns: `Strong's, Goodrick-Kohlenberger, Greek Word, English Definition (brief), English Definition
  (longer)`. Keyed by Strong's, which is convenient, but the Greek is beta-code (`a)/lfa`, `*)aarw/n, o(`)
  and needs conversion to Unicode.
- Useful as a cross-check or a fallback; TBESG is better and comes with the Hebrew half.

### ✅ Abbott-Smith — PD, but keyed by lemma

- `translatable-exegetical-tools/Abbott-Smith`: `abbott-smith.tei.xml` 5,698,817 bytes.
- README: *"The lexicon (abbott-smith.tei.xml), including the marked up version in this repository, is in
  the public domain."* Note the neighbouring caveat, which applies only to the scanned PDF, not the TEI:
  *"The PDF file with a text layer (manualgreeklexic00abborich.pdf) was obtained from
  http://archive.org/details/manualgreeklexic00abborich. Certain restrictions apply to the use of this
  file."* CrossWire's `abbottsmith.conf` agrees: `DistributionLicense=Public Domain`.
- Keyed by Greek lemma, not by Strong's; `gnt2asLookups.js` (223,305 bytes) bridges. **TBESG already is
  corrected Abbott-Smith keyed to Strong's**, so there is no reason to do this mapping by hand.

### ✅ BDB — free, but incomplete

`openscriptures/HebrewLexicon/BrownDriverBriggs.xml`, 2,911,253 bytes, CC BY 4.0 (same statement as
`HebrewStrong.xml`). The readme is honest about its state: *"BrownDriverBriggs.xml contains the current BDB
content. It remains a work in progress. Entries can be filled out over time, especially in the area of
completing the scripture references, and Hebrew words."* CrossWire's `bdbglosses_strongs` module packages
the glosses (`DistributionLicense=Public Domain`) and repeats *"The project is a work in progress."*
Shipping a lexicon with visible holes would be the Matthew Henry mistake again (see
`docs/study-sources.md`). TBESH's abridged BDB is complete; use that.

### ❌ Thayer's — no usable digitisation found

The 1889 *Greek-English Lexicon of the New Testament* is public domain, and scans are on the Internet
Archive (<https://archive.org/details/thayer-lexicon>, <https://archive.org/details/greekenglishlexi00grimuoft>).
What could not be found is a machine-readable, Strong's-keyed edition with any licence statement or
documented provenance. The files that circulate in Bible software have neither, and Thayer's is still sold
under licence as a commercial dictionary module (<https://www.bible-discovery.com/dictionary-license-thayer.php>).
Treat Thayer's as unavailable. TBESG fills its role.

### ⚠️ LSJ — free but wrong-sized

STEPBible's `TFLSJ 0-5624` (23,831,837 bytes) + `TFLSJ extra` (8,377,070 bytes), CC BY 4.0: *"Full LSJ
entries for all Bible words … formatted for easy reading."* 32 MB of scholarly lexicon for an app whose
point is "how close is the English". Not for this round.

---

## 3. Original-language text

**The important finding: this app does not need a separate Hebrew or Greek edition.** Column 7 of
`bsb_tables.tsv` *is* the original-language text — the WLC for the Old Testament and the Nestle base for
the New, word by word, already aligned to the English, already public domain. Everything below is
alternatives and cross-checks.

| Edition | Where | Licence, in its own words | Verdict |
|---|---|---|---|
| **Westminster Leningrad Codex** | `openscriptures/morphhb/wlc/` (OSIS XML) | *"The text of the WLC remains in the Public Domain."* Lemma/morph CC BY 4.0. | ✅ |
| **UXLC (tanach.us fork of WLC 4.20)** | <https://tanach.us/License.html> | *"All biblical Hebrew text, in any format, may be viewed or copied without restriction."* But also: *"All other files and the look-and-feel of the site are copyrighted by Tanach.us Inc. and require written permission for any purpose."* and *"The text presented at this site is not the Westminster Leningrad Codex (WLC); please do not represent it as such."* | ✅ text only; label it UXLC, not WLC |
| **SBLGNT** | <https://sblgnt.com/license/>, <https://github.com/LogosBible/SBLGNT> | The licence page serves the full **CC BY 4.0** text: *"the Licensor hereby grants You a worldwide, royalty-free, non-sublicensable, non-exclusive, irrevocable license … to reproduce and Share the Licensed Material, in whole or in part; and produce, reproduce, and Share Adapted Material."* Repo README: *"The SBLGNT is licensed under a Creative Commons Attribution 4.0 International License. Copyright 2010 by the Society of Biblical Literature and Logos Bible Software."* | ✅ — **but see the stale-conf trap below** |
| **Byzantine Majority (Robinson–Pierpont 2018)** | <https://github.com/byztxt/byzantine-majority-text> | `LICENSE.txt` is **The Unlicense**: *"This is free and unencumbered software released into the public domain. Anyone is free to copy, modify, publish, use, compile, sell, or distribute this software, either in source code form or as a compiled binary, for any purpose, commercial or non-commercial, and by any means."* | ✅ — take the GitHub repo, **not** the SWORD module |
| **Tischendorf 8th** | CrossWire `tisch` module | *"Tischendorf's 8th edition Greek New Testament with morphological tags … Edited by Ulrik Sandborg-Petersen. This text and its analysis are in the Public Domain. Copy freely."* `DistributionLicense=Public Domain`, `TextSource=http://morphgnt.org`, `Feature=StrongsNumbers`. | ✅ text (not the apparatus — see §4) |
| **Nestle 1904** | <https://github.com/biblicalhumanities/Nestle1904> | Per-directory licences. `morph/README.md` is **CC0 1.0**: *"To the extent possible under law, biblicalhumanities.org has waived all copyright and related or neighboring rights to the biblicalhumanities.org Nestle 1904 Morphology."* `Nestle1904.csv` 9,098,695 bytes with morphology, lemmatisation **and Strong's**. `glosses/README.md`: glosses came from the Berean Interlinear and *"This is now in the public domain"*. CrossWire `nestle1904`: `DistributionLicense=Public Domain`. | ✅ best free fallback if the BSB tables are ever dropped |
| **CNTR Statistical Restoration (SR)** | <https://github.com/Center-for-New-Testament-Restoration/SR> | `README.md`: *"Copyright © 2022-2023 by Alan Bunning. All rights reserved. Released under the Creative Commons Attribution 4.0 International License (CC BY 4.0)."* GitHub reports the repo licence as `CC-BY-4.0`. `SR.tsv` 7,970,687 bytes. | ✅ — but columns are `Verse, Modern, Koine, Lemma, **ESN**, Role, Morphology`; ESN is CNTR's own extended numbering, **not Strong's**, so a mapping would be needed |
| **unfoldingWord UHB / UGNT** | git.door43.org | Both: *"This work is made available under the Creative Commons Attribution-ShareAlike 4.0 International License"* + the trademark-removal clause quoted in §1. UHB *"is based on the Open Scriptures Hebrew Bible"*; UGNT *"is based on the Bunning Heuristic Prototype Greek New Testament, from https://greekcntr.org/, which is licensed as CC BY-SA 4.0."* | ⚠️ CC BY-SA; only worth it if the ULT is adopted too |
| **CrossWire `byz` (Byzantine Textform 2013)** | `mods.d/byz.conf` | `DistributionLicense=Creative Commons: BY-NC-SA 4.0`, `TextSource=https://sites.google.com/a/wmail.fi/greeknt/home/greeknt` | ❌ **NC** |
| **CrossWire `whnu` (Westcott–Hort + NA27/UBS4 variants)** | `mods.d/whnu.conf` | `DistributionLicense=Creative Commons: BY-NC-SA 4.0` | ❌ **NC** |
| **CrossWire `samaritan`** | `modules-conf.cache` | `DistributionLicense=Copyrighted; Free non-commercial distribution` | ❌ **NC** |

### The stale-conf trap — worth writing into the build script

CrossWire's `mods.d` is a **secondary** source and it lags. Two concrete cases found today:

- `sblgnt.conf` (Version=1.3.1) still says `DistributionLicense=Copyrighted; Free non-commercial
  distribution`, `Copyright=Copyright 2010 Logos Bible Software and the Society of Biblical Literature`.
  The primary source has since relicensed: the repo's own version history records
  *"v1.1 | 2022-12-19 | Update public version and license on github"*, and sblgnt.com now serves CC BY 4.0.
  Anyone who evaluated the SBLGNT from the SWORD conf would wrongly reject it.
- `byz.conf` says CC BY-NC-SA 4.0 while the upstream Robinson–Pierpont repository is under the Unlicense.
  The NC attaches to *that packaging*, not to the RP text.

Rule: quote the **primary** source, record the date, and note when a secondary packaging disagrees.

### "Free but non-commercial" is not usable — stated plainly

Every `DistributionLicense=Copyrighted; Free non-commercial distribution` and every `CC BY-NC` /
`CC BY-NC-SA` dataset above is out of scope, regardless of the app being free of charge. Distribution runs
through Apple's App Store under a paid developer programme; no licence decision should hang on whether a
free app on a commercial storefront counts as non-commercial use. This is also consistent with the project
so far: `LICENSE-EXCEPTIONS.md` states that the bundled content is *"in the **public domain**"*, and
`docs/study-sources.md` already rejected CCEL on exactly this kind of ground.

---

## 4. Textual variant notes

### ❌ NET Bible translator notes — not licensed, and the free edition does not contain them

- The NET's own permissions statement (<https://netbible.com/copyright/>) grants the text and **excludes
  the notes** in one parenthesis: *"The NET Bible® Scripture text **(without the NET Bible notes)** may be
  quoted in any form (written, visual, electronic, projection, or audio without written permission."* The
  free-distribution clause is similarly about the text: *"You may copy the NET Bible® and print it for
  others as long as you give it away, do not charge for it and comply with our guidelines for content
  control including current valid copyright and organizational acknowledgments. In this case, free means
  free."* Mobile apps are addressed only as a quotation case: *"When quotations from the NET Bible® are used
  in mobile apps, youtube channels, free apps, Internet apps or not-for-sale media … The abbreviation (NET)
  must be used at the end of the quotation."*
- **The eBible `engnet` edition was checked as asked, and it is text-only.** eBible's catalogue lists
  `engnet` with `Redistributable = True` and `Copyright = Copyright © 1996-2016 Biblical Studies Press,
  L. L. C.`, and its `copr.htm` says outright: *"For full NET Bible notes, please see netbible.org."*
  Measured in `engnet_usfm.zip` (2,912,943 bytes): **39** `\f ` footnote markers in the entire Bible — all
  of them clustered in Isaiah 43, evidently an editing leftover — against the NET's own advertised
  *"58,506 translators' notes"*, and **zero** `\x` cross-reference markers. It does carry **652,239**
  `strong="…"` word tags, so the eBible NET is a Strong's-tagged English text; but the notes, which are the
  only reason to want the NET here, are not in it and are not licensed.
- **Verdict: nothing to build on.** Do not ship the NET.

### ✅ SBLGNT apparatus — free, CC BY 4.0, and astonishingly small

- **Data**: 27 files at `https://www.sblgnt.com/download/<NN>-<Bk>-APP.txt` (e.g. `61-Mt-APP.txt`), linked
  from <https://sblgnt.com/download/>. Measured total **467,903 bytes / 4,879 lines**, ≈6,900 variation
  units. Also present as `data/sblgntapp` in the CC BY 4.0 `LogosBible/SBLGNT` repository, which settles
  the licence question: the repo carries one CC BY 4.0 `LICENSE` covering both `data/sblgnt` and
  `data/sblgntapp`.
- **Format**: `Mt 1:6\tδὲ WH Treg NIV ] + ὁ βασιλεὺς RP`, with a per-book header
  `Order of edition citation: WH Treg NIV RP`. Compresses to **103 KB**.
- **What it is and is not**: an apparatus of **printed editions** — WH (Westcott–Hort), Treg (Tregelles),
  NIV (the text behind the NIV, i.e. NA/UBS), RP (Robinson–Pierpont), NA. It cites **no manuscripts**. For
  this app that is arguably the *right* granularity: "the KJV's Greek had ὁ βασιλεὺς here and the modern
  editions don't" is a sentence a reader can act on; "𝔓⁴⁵ ℵ B vs. Byz" is not.
- **Note the honest framing SBL itself uses** (`About.md`): *"the SBLGNT differs from the standard text in
  more than 540 variation units—will help to remind readers of the Greek New Testament that the
  text-critical task is not finished."*

### ✅ STEPBible TAGNT — per-word edition witnesses, CC BY 4.0

Already described in §1. Between the editions column and the `v`/`^` variant-note flags this is a
word-level apparatus of editions for the whole New Testament, under a licence that permits bundling. Its
`{TR} ⧼RP⧽ (WH) 〈NE〉 [NA] ‹SBL›` bracket notation also appears in `bsb_tables.tsv` column 7 — but only
**402 Greek words** are bracketed there, so the BSB tables alone are *not* an apparatus. TAGNT is.

### ✅ byztxt CCAT files — an NA/ECM-divergence apparatus under the Unlicense

`source/CCAT/*.TXT` in `byztxt/byzantine-majority-text`, 1,374,605 bytes, accented beta-code with an
inline apparatus: `{N *)ASA/ > *)ASA/F }` marks where Nestle reads differently. `source/Strongs/*.BP5`
(2,745,008 bytes) is the same text with Strong's and Robinson morph codes. The README describes the CCAT
set as *"a full version in BETA format, with accents, breathings, diarheses, iota subscripts, and an
apparatus containing Byzantine variants and Nestle-Aland and Editio Critica Maior divergences."* Unlicense,
so no conditions at all. Beta-code conversion required.

### ❌ CNTR collation and Universal Apparatus — not obtainable as licensed data

CNTR's *texts* are open. From <https://greekcntr.org/resources/index.html>: the SR, BHP and KJTR are
*"copyright © by Alan Bunning released under the Creative Commons Attribution 4.0 International License
(CC BY 4.0). Attribution must be given to Alan Bunning and the Center for New Testament Restoration in any
derivative works, and any changes made must be indicated."* The transcriptions are CC BY-SA 4.0. But the
site-wide notice on every page reads: *"Copyright © 2013-2025 by Alan Bunning. All rights reserved. Copying
or distribution without the author's prior written consent is expressly prohibited."* No downloadable,
licensed release of the Collation or the Universal Apparatus was found. Building one from the CC BY-SA
manuscript transcriptions means writing a collation engine — a research project, not an app feature.

### ❌ Tischendorf's apparatus — text yes, apparatus no

What is free is the **text** (CrossWire `tisch`, Public Domain) and *"Tischendorf's Spurious Passages of
the Greek New Testament"* (CrossWire `spurious`, `DistributionLicense=Public Domain`,
`TextSource=http://www.bibletoday.com/htstb/spurious_text.htm`). The 1869–72 apparatus itself has no
machine-readable, verse-keyed, cleanly-licensed digitisation. Its Latin sigla and abbreviations would also
be useless to this app's reader.

### ⚠️ laparola `VarApp` — the only free manuscript apparatus found, and its licence is unverified

CrossWire's `varapp.conf` describes exactly the dataset one would want: *"This book gives the main variant
readings of the Greek New Testament… The text of the SBL Greek New Testament is always given first, and the
alternative readings are on the following lines. Every reading is followed by the witnesses, that is the
manuscripts that contain it. The order for the witnesses is: papyri, uncials, families, minuscules,
lectionaries, ancient versions, fathers, editions, and Italian translations."* It declares
`DistributionLicense=Creative Commons: CC0`, `InstallSize=994913`, `TextSource=http://www.laparola.net/greco/`.

**But laparola.net publishes no licence.** Its pages carry only *"Copyright luglio 2026 (July 2026)
LaParola"*, and `/copyright.php`, `/en/copyright` and `/greco/copyright.php` all 404. The CC0 claim exists
solely in a CrossWire conf — and §3 above shows CrossWire confs are not reliable on licences. **Do not ship
this on that basis.** If a manuscript-level apparatus is wanted badly enough, write to the author and get
the CC0 in writing; that is a one-email question with a potentially very good answer.

### ❌ The Old Testament: this is where the answer really is "nothing usable free"

There is **no** free, verse-keyed critical apparatus for the Hebrew Bible. The BHS/BHQ apparatus is
Deutsche Bibelgesellschaft copyright. The Hebrew Bible Critical Edition (HBCE) is not published as data.
Nothing in the CrossWire catalogue, the STEPBible repository, OpenScriptures or Door43 fills the gap.

What *is* free, and is already sitting in `Data/source/bsb_usfm.zip`: **the BSB's own footnotes.**
`bsb_tables.tsv` carries **4,854** of them, of which **1,135** cite manuscripts or versions — LXX, SP
(Samaritan Pentateuch), Syriac, Vulgate, MT, Qumran. Examples, verbatim:

- Genesis 4:8 — *"SP, LXX, Syriac, and Vulgate; Hebrew `Then Cain spoke to his brother Abel.`"*
- Genesis 10:4 — *"SP and some MT manuscripts (see also LXX and 1 Chronicles 1:7); most MT manuscripts `Dodanites`"*
- Genesis 5:24 — *"LXX `and he was not found, because God had taken him away`; cited in Hebrews 11:5"*

That is 359 KB raw, **91 KB** compressed, public domain, verse-keyed, in plain readable English. It is not a
critical apparatus and must not be labelled one, but it is the honest Old Testament answer, and the app can
present it as *"where the translators saw the manuscripts disagree"*.

---

## 5. Recommendation

### Smallest set that delivers (a) tap-a-word and (b) a genuine interlinear line

**Two sources. That is all.**

1. **`bsb_tables.tsv`** → the interlinear table. One file gives the Hebrew/Greek word, transliteration,
   parsing (abbreviated and expanded), Strong's number, the BSB English, and both sort orders, for both
   testaments.
2. **`TBESH` + `TBESG`** → the lexicon layer, 100% coverage of the numbers the table uses.

Optional third piece, for a defensible version of the variants feature, at almost no cost:

3. **SBLGNT apparatus** (27 `*-APP.txt` files) + **the BSB footnotes** already inside `bsb_tables.tsv`.

Build shape, following the existing pattern exactly: a new `Tools/build_original.py` producing
`ScriptureAlone/Resources/Study/Original.sqlite`, verse keys `book*1_000_000 + chapter*1_000 + verse`,
per-chapter raw-DEFLATE blobs for the word records (same trick as `commentary_text`), a `sources` table
mirroring `build_study.py`'s so **About Study Resources** picks it up, and SHA-256 pins for the two
downloads recorded in the script with snapshots kept under `Data/source/original/`.

### Measured size

| Part | Raw | In the bundle |
|---|---|---|
| Interlinear word records — 437,587 tagged words over 1,189 chapters (original word, transliteration, Strong's, parsing id, English gloss), DEFLATE per chapter | 20.1 MB | **7.6 MB** |
| Word index (row → verse, BSB order + original-language order) + 3,819-entry parsing-code table + SQLite pages and indexes | — | **≈1.5–2.5 MB** (estimate) |
| Lexicon — 13,876 Strong's numbers with form, transliteration, morph class, one-word gloss **and** full entry | 6.3 MB | **1.6 MB** |
| SBLGNT apparatus — 4,879 lines | 0.47 MB | **0.10 MB** |
| BSB footnotes — 4,854 notes | 0.36 MB | **0.09 MB** |
| **Total** | | **≈11–12 MB** |

For scale, the app currently bundles 45.4 MB of Bibles and 45.7 MB of `Study.sqlite`. This is a ~12%
increase for the feature the app is named for. If size ever bites, the *full lexicon entries* (1.6 MB → 0.23 MB
for glosses only) and Gill's commentary (already flagged in `docs/study-sources.md`) are the two levers.

### Exact files to fetch

| Purpose | URL | Bytes |
|---|---|---|
| Interlinear + Strong's + BSB alignment + BSB footnotes | <https://bereanbible.com/bsb_tables.tsv> | 85,525,373 |
| Hebrew lexicon | `STEPBible-Data/Lexicons/TBESH - Translators Brief lexicon of Extended Strongs for Hebrew - STEPBible.org CC BY.txt` | 3,288,045 |
| Greek lexicon | `STEPBible-Data/Lexicons/TBESG - Translators Brief lexicon of Extended Strongs for Greek - STEPBible.org CC BY.txt` | 4,736,912 |
| NT editions apparatus (optional) | `https://www.sblgnt.com/download/<NN>-<Bk>-APP.txt` × 27 | 467,903 |
| KJV Strong's tags, if the feature is extended beyond the BSB (optional) | <https://ebible.org/Scriptures/eng-kjv2006_usfm.zip> | 2,461,781 |
| NT per-word edition witnesses + variant flags (optional, richer than SBLGNT's) | `STEPBible-Data/Translators Amalgamated OT+NT/TAGNT {Mat-Jhn,Act-Rev} … CC-BY.txt` | 30,128,964 |

### The feature that is not worth building

**A manuscript-level critical apparatus.** Not because the app can't render it, but because the data does
not exist on acceptable terms:

- **New Testament** — the only free datasets are apparatuses of *printed editions* (SBLGNT: WH/Treg/NIV/RP;
  STEPBible TAGNT: NA28/NA27/THGNT/SBL/WH/Treg/TR/Byz; byztxt: NA and ECM divergences). The one free
  dataset with actual manuscript witnesses, laparola's `VarApp`, has a CC0 claim that exists only in a
  third-party config file while its own site asserts bare copyright. CNTR has the transcriptions but not a
  licensed apparatus.
- **Old Testament** — nothing at all, at any licence. BHS/BHQ is DBG copyright; HBCE is not data.

So: **do not ship "the manuscript evidence".** Ship the thing that is both free and more useful to this
app's reader — *"which printed editions contain this word, and which Greek the KJV translators were
working from"* — plus the BSB's own version footnotes for the Old Testament. That is an honest, complete,
public-domain answer to "how close is the English to the original", and it is 200 KB.

### One structural caveat the owner should decide on now

**Tap-a-word works on the BSB only, out of the box.** The three bundled translations are unequal here:

| Translation | Strong's alignment available | Route |
|---|---|---|
| **BSB** | ✅ full, word-level, verified 99% against the bundled text | `bsb_tables.tsv` |
| **KJV** | ✅ 349,308 tags, PD, but crude (function words untagged; `H0853` mapped to "and") | eBible `eng-kjv2006`, already a build input |
| **ASV** | ❌ no public-domain Strong's-tagged ASV exists | see below |

For the ASV, the options are: (1) leave it untagged and, when the user taps a word, show the BSB's
interlinear for that verse with a note; (2) propagate tags from the KJV — `build_bibles.py` already aligns
ASV to KJV for words of Christ, so the machinery exists, but it would be an alignment of an alignment and
should be labelled as approximate; (3) adopt unfoldingWord's ULT (CC BY-SA 4.0, ASV-derived, best-in-class
alignment) as a fourth translation — clean data, but a share-alike obligation and a new text to explain.
Option (1) is the smallest and the most honest.

---

## Recommended sources

| Source | What it is | Licence (own words) | Size | Enables |
|---|---|---|---|---|
| **BSB Translation Tables** <br> `bereanbible.com/bsb_tables.tsv` | 754,648 rows; 437,587 Hebrew/Greek words with transliteration, parsing, Strong's, and the BSB English that renders each one; both sort orders; 4,854 footnotes | *"The Berean Bible and Majority Bible texts are officially dedicated to the public domain as of April 30, 2023. All uses are freely permitted."* | 85.5 MB source → **7.6 MB** compressed payload | Tap-a-word Strong's; the interlinear line; OT version footnotes |
| **STEPBible TBESH** | Abridged BDB keyed to extended Strong's; form, transliteration, morph class, one-word gloss, full entry | CC BY 4.0 — *"Include any part of this data in software or publications without requesting permission"* | 3.29 MB | Hebrew glosses and definitions |
| **STEPBible TBESG** | *"based on the Abbott-Smith definitions, and … edited to conform with the extended Strongs"* | CC BY 4.0, same header | 4.74 MB → **1.6 MB** with TBESH | Greek glosses and definitions |
| **SBLGNT apparatus** <br> `sblgnt.com/download/*-APP.txt` | ≈6,900 variation units citing WH / Treg / NIV(NA) / RP | *"The SBLGNT is licensed under a Creative Commons Attribution 4.0 International License."* | 468 KB → **103 KB** | "Which printed editions read what" |
| **eBible `eng-kjv2006`** *(optional)* | KJV 1769 with 349,308 inline `strong=` tags | *"You may copy the King James Version of the Holy Bible freely."* `Redistributable = True`, `Copyright = public domain` | 2.46 MB zip | Tap-a-word on the KJV |
| **STEPBible TAGNT** *(optional)* | Per-word edition witness lists + `v`/`^` variant flags for the whole NT | CC BY 4.0, same header | 30.1 MB | A richer NT variants view than SBLGNT's |

Rejected, for the record: **TTESV** (CC BY-NC), **CrossWire `byz`** and **`whnu`** (CC BY-NC-SA 4.0),
**Samaritan Pentateuch module** ("Free non-commercial distribution"), **NET Bible translator notes** (text
licensed *"without the NET Bible notes"*), **CNTR Collation / Universal Apparatus** (*"All rights reserved.
Copying or distribution without the author's prior written consent is expressly prohibited."*),
**Thayer's** (no licensed digitisation), **BDB full text** (incomplete upstream), **laparola VarApp**
(CC0 claimed only by a third party).

---

## Open questions

1. **STEPBible's "please do not redistribute it yourself".** The README grants inclusion in software
   without permission, and the data is CC BY 4.0, whose no-additional-restrictions clause means the request
   cannot bind. But it is in every file header. Recommend: ship it, attribute *"STEP Bible"* linked to
   www.STEPBible.org as the README asks, note the compiled database was built from the repository, and
   email STEPBibleATgmail.com — the README asks for that anyway (*"We'd love to hear about your project"*).
   Get the answer in writing before 1.0 of the feature.
2. **laparola `VarApp`.** Is the CC0 in CrossWire's conf authoritative? One email to the site author
   decides whether the app can have a real manuscript apparatus for the New Testament at ~1 MB. Worth
   sending; do not ship until answered.
3. **The 149 verses where `bsb_tables.tsv` and the bundled BSB disagree.** Psalm superscriptions,
   `<p class="…">` markup bleeding into the English column, and a handful of 3rd-printing differences.
   Decide: patch, drop the interlinear for those verses, or rebuild the bundled BSB from the same 3rd-printing
   snapshot the tables come from. The last is cleanest and is a `build_bibles.py` change, not a new tool.
4. **The ASV gap.** Pick one of the three options in §5. This is a product decision, not a data one.
5. **Whether to show parsing at all.** The tables carry 3,819 distinct parsing codes with expanded English
   ("Verb - Qal - Perfect - third person masculine singular"). Free and already in the file, but it is the
   most scholarly-looking thing here and could work against the app's plain-reader tone. Cheap to ship
   behind a toggle.
6. **Extended Strong's sub-senses.** TBESH/TBESG split numbers (`H0430G`, `H0430H`, `G2264G/H/I` for the
   three Herods) that `bsb_tables.tsv` does not. Collapsing to the base number loses real information;
   showing all sub-senses on a tap may be the better UI, but it needs a design.
7. **Greek Strong's provenance, if Strong's own wording is ever wanted.** `openscriptures/strongs` has no
   licence file; only an in-file comment claiming CC BY-SA for the JSON conversion. Either use the XML and
   record the claim, or rely on TBESG and never ship Strong's Greek text. Recommend the latter.
8. **Re-check dates.** Every statement above was verified on 2026-09-19 and the SBLGNT case proves licences
   move. Put the check date in the `sources` table the way `build_study.py` already does, and re-verify at
   each release.
