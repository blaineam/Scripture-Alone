# Study mode sources

Study mode (cross references and commentary) ships only public-domain or openly licensed data.
Each source below was checked against its primary source on 2026-09-18. The same names,
licenses and attribution lines live in `Tools/build_study.py` and in the `sources` table of
`ScriptureAlone/Resources/Study/Study.sqlite`, which the app's **About Study Resources** screen
shows.

Raw snapshots are kept in `Data/source/study/` with SHA-256 checksums recorded in the build
script, so the database can be rebuilt byte-for-byte without the network:

| File | Size | SHA-256 |
|---|---|---|
| `openbible-cross-references.zip` | 2.0 MB | `30379be544785f4c2cdf8eba0d83d10dedc04a6903b5dcd0d91d670e90619d6d` |
| `john-calvin.json.gz` | 16.2 MB | `92d2cba35e9b0c8eb070e3ed66d207548f0f7607dbab5fbb2a8a802cd750e0fa` |
| `john-gill.json.gz` | 15.6 MB | `bc192641caa9d63333562abf4fd38081466a451f963e784ef8922d8cc2b47aef` |
| `jamieson-fausset-brown.json.gz` | 4.2 MB | `af32175dda3f4d7193ae5bf33b636f4e5ba0ed625c736831abd77426eda7a08a` |

`python3 Tools/build_study.py --fetch` re-downloads the commentary snapshots (one request per
chapter against the Free Use Bible API); a changed checksum prints a warning so upstream
corrections get reviewed before they're recorded.

## Cross references — OpenBible.info (CC BY 4.0) ✅ used

- **Data**: <https://a.openbible.info/data/cross-references.zip> (linked from
  <https://www.openbible.info/labs/cross-references/>), snapshot dated 2026-09-14 in the file header.
  344,799 links, each with a reader vote count, so the strongest can be shown first.
- **License**: the page footer reads "Unless otherwise indicated, all content is licensed under a
  Creative Commons Attribution License", linking <http://creativecommons.org/licenses/by/4.0/>; the
  data file's own header says `#www.openbible.info CC-BY 2026-09-14`.
- **Provenance**: "This data draws primarily from public-domain sources, especially the Treasury
  of Scripture Knowledge", seeded with votes from OpenBible's topical data.
- **What we keep**: links with at least one net vote (341,278; 3,521 with zero or negative votes
  are dropped).
- **Attribution shown in the app**: "Cross references from OpenBible.info (www.openbible.info),
  licensed CC BY 4.0. Ranked by reader votes; references with no net votes are omitted."
- CC BY 4.0 is compatible with distributing the app under the AGPL: attribution is the only
  condition, and it appears under the list and on the About Study Resources screen.

## Commentary — from the Free Use Bible API (AO Lab) ✅ used

The commentaries come from AO Lab's Free Use Bible API (<https://bible.helloao.org>, code at
<https://github.com/HelloAOLab/bible-api>, MIT). Its README states: "No usage limits, no API Keys
required, and no copyright restrictions whatsoever (including for modification or commercial
uses)." `https://bible.helloao.org/api/available_commentaries.json` lists each commentary below
with `licenseUrl` = <https://creativecommons.org/publicdomain/mark/1.0/> (Public Domain Mark 1.0).
The underlying works are centuries old and public domain everywhere. Unlike CCEL or SWORD
modules built from CCEL (see below), the distributor releases these digital files explicitly
without restriction.

AO Lab doesn't document where its digitizations came from; e-Sword-style book abbreviations in
the text (`Pe1 2:25`, `Joh 3:22`) suggest an e-Sword lineage. The build rewrites those
abbreviations (`1Pe 2:25`, `Mark 4:3`) so the app can turn them into links. It also drops
Calvin's footnote callers (`[61]`), whose notes aren't in the data, and repairs JFB's mojibake
(`CÃ&brvbrsarea` becomes `Cæsarea`, `Â£` becomes `£`). Calvin's Greek quotations stay in the
source's old ASCII transliteration (`ojrqwv`).

| In the app | Work | License marker | Coverage |
|---|---|---|---|
| **Calvin** | John Calvin, *Commentaries*, Calvin Translation Society English edition (Edinburgh, 1843–1855) | PDM 1.0. AO Lab's note: "Calvin Translation Society English texts. CCEL identifies its electronic Calvin commentaries as public domain and usable for any purpose." | 48 books, 7,319 passages |
| **Gill** | John Gill, *An Exposition of the Old and New Testament* (1746–1766) | PDM 1.0 | 66 books, verse by verse (29,707 comments; 1 Chronicles 11 is empty upstream) |
| **JFB** | Jamieson, Fausset & Brown, *Commentary Critical and Explanatory on the Whole Bible* (1871) | PDM 1.0 | 66 books, verse by verse, plus section introductions |

Attribution lines (shown under each commentary and on About Study Resources) name the work, its
date, its public-domain status, and "Text from the Free Use Bible API by AO Lab
(bible.helloao.org), Public Domain Mark 1.0."

## Database size

`Study.sqlite` is 45.7 MB. Each chapter's commentary is compressed as a single raw-DEFLATE block,
which is about 15% smaller than compressing each comment separately:

| Part | Text | Stored |
|---|---|---|
| Cross references (341,278 links, 10-byte packed records) | — | 3.4 MB |
| Calvin | 46.6 MB | 17.0 MB |
| Gill | 45.4 MB | 16.6 MB |
| JFB | 10.2 MB | 4.6 MB |

If app size becomes a concern, Gill is the easiest to move into an on-demand download.

## Evaluated, not used

- **Matthew Henry's Commentary (Free Use Bible API `matthew-henry`)**: the license is fine (PDM 1.0),
  but the text is damaged. 36 of its 4,124 passage comments are cut off mid-sentence at 32,767
  bytes, Excel's cell limit, which suggests the upstream CSV went through a spreadsheet. The
  damaged comments include some of the most-read passages, such as the ones beginning at John 3:1
  (which covers John 3:16), Matthew 5:3, Romans 12:1 and Romans 14:1. Shipping those
  half-finished would be worse than leaving Henry out. Adding him back needs a complete, cleanly licensed digitization of the *Complete* or *Concise* commentary.
- **CCEL (ccel.org) texts**: the works are public domain, but CCEL's copyright policy
  (<https://www.ccel.org/about/copyright.html>) says "Contact us for permission to republish CCEL
  works or to use them commercially", and CCEL claims copyright on its formatting. Not used.
- **CrossWire SWORD modules** (`MHCC`, `MHC`, `JFB`, `CalvinCommentaries`, `TDavid`, `Geneva`,
  `TSK`; confs in <https://www.crosswire.org/ftpmirror/pub/sword/raw/mods.d.tar.gz>): most are
  marked `DistributionLicense=Public Domain`. But `MHCC` 2.0, `JFB` 3.0, `MHC` 2.x and
  `CalvinCommentaries` all name CCEL as their `TextSource`, so the CCEL question above carries
  over. `Geneva` (the 1599 notes) has no DistributionLicense at all. `TDavid` (Spurgeon's
  *Treasury of David*) is marked public domain from archive.spurgeon.org. It's a good candidate
  for a Psalms-only source, but it needs a SWORD zCom4 reader and wasn't worth adding this round.
  `TSK` (public domain) isn't needed, since OpenBible's votes are mostly TSK already.
- **Tyndale Open Study Notes** (CC BY-SA 4.0, <https://tyndaleopenresources.com/>): acceptable if
  the share-alike terms are honored, but it's a modern evangelical study Bible, not classic
  Reformed commentary. Not needed for this round.
- **Matthew Poole's *Annotations***: no clean, verse-keyed, clearly licensed digitization found.
