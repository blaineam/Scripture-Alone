# Study context: sources and licenses

Study mode's **Context** tab (maps, timeline and charts) runs entirely offline from two bundled
files built by `Tools/build_context.py`:

| File | Size | Contents |
|---|---|---|
| `ScriptureAlone/Resources/Study/Context.sqlite` | ~480 KB | 1,277 places, 8,705 verse→place links, 11 eras, 84 events, 1,189 chapter→era rows, 4 charts, 21 map labels |
| `ScriptureAlone/Resources/Study/Basemap.bin` | ~140 KB | Land, lakes and rivers at two levels of detail (≈33,900 points) |

```bash
python3 Tools/build_context.py --check   # downloads once into Data/cache/ (SHA-256 pinned), builds, asserts
```

## Places — OpenBible.info Bible Geocoding Data

- **What:** every identifiable place in the Protestant Bible, the modern locations proposed for
  it with confidence scores, and the verses that mention it.
- **Author:** Stephen Smith, [OpenBible.info](https://www.openbible.info/geo/).
- **Source:** <https://github.com/openbibleinfo/Bible-Geocoding-Data>, pinned at commit
  `7eb18a5ee62f27b9b93bd6689ea272d76dd23b8f` (2021-11-01). Files used: `data/ancient.jsonl`
  (SHA-256 `b8187aa4…c8c0f2`) and `data/modern.jsonl` (`da731f6e…2c60087`).
- **License:** [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/),
  verified from the repository's `license.txt` and README ("This data is licensed under a
  Creative Commons Attribution 4.0 license"). Attribution is given in the app's
  *Sources & Credits* screen and here.
- **How it is used:** for each ancient place with at least one verse, the build keeps the
  identification with the highest current confidence (`score.time_total`, 0–1000) and, within
  it, the resolution with the best path score. The app shows that confidence as *Identified*
  (≥ 900), *Likely* (500–899) or *Uncertain*, flags regions and "center of a possible area"
  points as approximate, and links each place to its OpenBible page for the full evidence and
  the alternatives. Names drop OpenBible's disambiguating suffix ("Antioch 1" → "Antioch").
  Places outside the map (20°W–60°E, 10°N–45°N) are dropped (one place). Verse references use
  OpenBible's `sort` key, which matches this app's English versification; every one of the
  8,705 links falls inside the bundled text.
- **OpenStreetMap:** OpenBible notes that its OpenStreetMap-derived data is under the
  [ODbL 1.0](https://opendatacommons.org/licenses/odbl/). Only point coordinates are used here —
  no OSM geometry — and 17 of the 1,277 places take their coordinates from an OSM node
  (flagged `osm = 1` in the `places` table). That is an insubstantial extract; the app still
  credits "© OpenStreetMap contributors" in *Sources & Credits*.
- **Not bundled:** OpenBible's thumbnails, images (various licenses) and GeoJSON geometry.

## Base map — Natural Earth

- **What:** 1:10m physical vectors — `ne_10m_land`, `ne_10m_lakes`,
  `ne_10m_rivers_lake_centerlines`.
- **Source:** <https://github.com/nvkelso/natural-earth-vector> (v5.1.2 line), pinned at commit
  `ca96624a56bd078437bca8184e78163e5039ad19`, `geojson/` directory. SHA-256s are in
  `Tools/build_context.py`.
- **License:** public domain ([naturalearthdata.com/about/terms-of-use](https://www.naturalearthdata.com/about/terms-of-use/)).
  Credit is given anyway.
- **Processing:** clipped to 21°W–61°E, 9°N–46°N (Sutherland–Hodgman for polygons,
  Liang–Barsky for rivers), simplified with Douglas–Peucker at 0.035° (overview) and 0.0045°
  (close-up) on a cos 32° equirectangular plane, and quantized to 16-bit coordinates.
  Modern reservoirs (Lake Nasser, Lake Assad, the Atatürk and Keban dams…) and canals (Suez,
  Ismailia…) are left out because they did not exist in biblical times. The Dead Sea and the
  Mediterranean coastline are modern, not ancient, shorelines.
- The app draws the map itself (SwiftUI `Canvas`); there are no map tiles and no network use.

## Map labels (authored)

`Data/context/map_labels.json` — seas, rivers and landscape names placed by hand for this app
(e.g. "Mediterranean Sea — The Great Sea", "Dead Sea — Salt Sea", "Sea of Galilee — Chinnereth").

## Timeline (authored)

`Data/context/eras.json` (eras and events) and `Data/context/chapters.json` (every chapter of
all 66 books → its era(s) and an approximate year). Written for this app. Years are whole
numbers; negative years are BC. Events carry verse ranges, validated by the build against the
bundled text.

The chronology follows standard conservative reference works, cross-checked against each other:

- Eugene H. Merrill, *Kingdom of Priests: A History of Old Testament Israel*, 2nd ed. (Baker, 2008) — patriarchs through the return; early exodus date.
- Edwin R. Thiele, *The Mysterious Numbers of the Hebrew Kings*, 3rd ed. (Zondervan, 1983) — the divided monarchy (931 BC division; co-regencies).
- Kenneth A. Kitchen, *On the Reliability of the Old Testament* (Eerdmans, 2003) — the case for a 13th-century (late) exodus, noted as the main alternative.
- Jack Finegan, *Handbook of Biblical Chronology*, rev. ed. (Hendrickson, 1998).
- Harold W. Hoehner, *Chronological Aspects of the Life of Christ* (Zondervan, 1977) — birth before 4 BC; crucifixion AD 33 (the app marks AD 30 and notes 33).
- F. F. Bruce, *Paul: Apostle of the Heart Set Free* (Eerdmans, 1977) — Paul's journeys and letters.

Only dates and the general shape of the periods are drawn from these works; all wording is original.

### Where dates are approximate or debated

The app says so in place (an "About these dates" note per era, a **?** on debated events, and
notes on individual chapters):

- **Primeval history** (Genesis 1–11) is undated.
- **Patriarchs** are dated back from the early exodus with 1 Kings 6:1 (480 years) and
  Exodus 12:40 (430 years); on the late-exodus view they move about two centuries later.
- **Exodus:** c. 1446 BC (early date, 1 Kgs 6:1; Judg 11:26) with c. 1260 BC (late date,
  Ramesses II) given as the alternative.
- **Judges** overlap; their dates are approximate. Saul's reign length is uncertain in the
  Hebrew of 1 Samuel 13:1 (Acts 13:21 gives forty years).
- **Jerusalem's fall:** 586 BC (or 587).
- **Seventy years** of exile (Jer 25:11) counted from 605 or 586 BC.
- **Ezra's return:** 458 BC (majority) or 398 BC.
- **Isaiah 40–66**, **Joel**, **Obadiah**, **Job**, **Ecclesiastes** and **Galatians** carry
  notes on disputed dates or authorship.
- **Crucifixion:** AD 30 or 33. **Revelation:** c. AD 95, or before AD 70.
- Psalms are placed in the united monarchy (David) except Psalm 90 (Moses), 126 (return) and
  137 (exile); the note says they span about a thousand years.

## Charts (authored)

`Data/context/charts/*.json`, written for this app. Every reference is validated by the build.

| Chart | Content and sources |
|---|---|
| Kings of Israel & Judah | 3 united-kingdom kings, 19 kings of Israel, 20 rulers of Judah. Reign dates from Thiele (approximate, include co-regencies); verdicts summarize the text's own judgment ("did what was right / evil in the sight of the LORD"); prophets are those the text places in each reign (book superscriptions, e.g. Isa 1:1, Hos 1:1, Amos 1:1, Mic 1:1, Zeph 1:1, Jer 1:2–3, and the narratives of Kings and Chronicles). |
| Paul's Missionary Journeys | Four routes (three journeys and the voyage to Rome) as ordered stops named in Acts 13:1–28:16, each with its verse, drawn on the map with OpenBible coordinates. Lines between stops are schematic. Region waypoints (Cilicia, Phrygia, Mysia, Galatia) mark "went through" passages. Dates after Bruce. |
| The Twelve Tribes | Birth order and mothers (Gen 29:31–30:24; 35:16–18), Jacob's blessing (Gen 49), Moses' blessing (Deut 33), allotments (Josh 13–19; Levi's cities, Josh 21). Allotment label positions are approximate centers authored for this app. |
| Feasts of Israel | The appointed times of Leviticus 23 (with Exod 12, Num 28–29, Deut 16) plus Purim (Esth 9) and Dedication (John 10:22). Seasons are approximate (the Hebrew calendar is lunar). New Testament links are cited passages; the Trumpets link is marked as an interpretation. Short quotations are from the Berean Standard Bible (public domain). |

## Verification

`python3 Tools/build_context.py --check` asserts, among other things: Acts 13–14 mention
Antioch, Cyprus, Iconium, Lystra, Derbe, Paphos and Perga; 1 Kings 12 mentions Shechem, Bethel,
Dan and Jerusalem; Jerusalem sits at about 35.23°E 31.78°N with 700+ verse mentions; Rome is
where it should be; Genesis 12 is in the patriarchal era and 1 Kings 12 in the divided kingdom
(931 BC); all 1,189 chapters have an era; dated eras are contiguous; the charts decode with the
expected rows; and both files stay under their size budgets. `ContextStoreTests` in
`ScriptureAloneCore` covers the same ground through the Swift API.
