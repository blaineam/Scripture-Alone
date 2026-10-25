# Reading plans

A plan is a schedule of passage **references** — nothing else. The text of a day's reading is always
read from the translation the reader has chosen, on the day, so a plan never carries (or licenses) any
Bible text, and it follows a change of translation at once.

Code: `ScriptureAloneCore/Sources/ScriptureAloneCore/Plans/` (model, schedule, importer — tested in
`ReadingPlanTests.swift`), `ScriptureAlone/Plans/` (store, reminders, screens),
`android/app/.../data/plans/` + `ui/plans/` (the same logic and screens in Kotlin; `ReadingPlanTest.kt`, `PlanStoreTest.kt`). The
Swift and Kotlin tests carry the same cases; add one to both or to neither.

## Built-in plans

| Plan | Shape |
|---|---|
| Bible in a Year | Genesis → Revelation in canonical order, 365 days, chapters spread evenly (about 3.3 a day). A day that crosses a book boundary holds a passage for each book. |
| Bible in a Year, Chronological | An editorial chronological order (`PlanCatalog.chronologicalOrder`), chapter-granular, 365 days. Every one of the 1,189 chapters appears exactly once (a test holds it). Psalms are placed in blocks with the events they belong to; the Gospels run Matthew → John rather than as a harmony. |
| Book by Book | Pick any of the 66 books: one chapter a day. |
| Spurgeon's Morning and Evening | A shipped devotional, by calendar date — see below. |
| Disciples Church | One tap from Add a Plan: follows the church's daily reading from its feed, refreshed each time the app opens. |

Days are *ordinal*: day 1 is the start date, and a plan that skips weekdays (an imported one whose source
doesn't post Saturday and Sunday) moves on to the next eligible date. A plan whose source dated every day
can instead **follow the source's calendar**: today's reading is the one the source posts today.

## Progress

Per plan: the start date, the days done, an optional daily reminder time. From those, pure functions
(`PlanSchedule`) give Today (a reading, a rest day, not started, finished), the **missed days** (catch-up),
the **streak** (consecutive scheduled days read, ending today's — or yesterday's while today's is open, so
a streak survives until a day is really missed) and the fraction done.

iOS keeps plans and progress in `Application Support/Plans/plans.json`; Android in `filesDir/plans/plans.json`, the same
shape, carried by Auto Backup to a new phone. Neither syncs live between a reader's devices (see *Not yet*). An imported
plan's schedule is stored on the device, so it works offline; only the *refresh* needs a connection.

## Reminders

A daily local notification at the reader's time, naming the passage ("Today's reading: Romans 8"). A
repeating notification can't carry a different passage each day, so the next 14 days are scheduled one by
one and topped up whenever the app opens or progress changes; a day already read gets none. The
permission is asked when the reader first turns a reminder on.

## Read aloud

`ListenController.playReading(_:in:)` reads a list of passages (each chapter announced), and
`ListenController.playTodaysReading(in:store:)` is the whole "play today's reading" call — **no UI is
involved**, so the CarPlay audio entry planned for a later release can call it as it stands. It returns
false when no plan is started or the translation can't supply the text.

A plan's own writing — the weekly study — can be read aloud too: *Read Aloud* on the study screen calls
`ListenController.playProse(_:title:in:)`, one paragraph at a time
under the text's own title. It is opt-in: it starts only when the reader taps the button.

The **Start Today's Reading** App Intent (`ReadingIntents.swift`) opens today's passage and reads it. With
no plan started it says so rather than opening the app to nothing. An app gets ten Siri phrases; to make room,
the phrase for *Add to Favorites* was given up (it needs a verse dictated, which *Open Passage* and *Get Verses* already do
by voice, where *Today's Reading* is a hands-free daily use) — Add to Favorites stays in the Shortcuts action list.
Nothing else among the ten was a double, an extra or mergeable: Find Notes/Create Note, Open Passage/Get Verses/Search and
Continue/Listen each take different parameters, and an App Shortcut can't be folded into another intent's phrases.
On Android the same call is `AppCommand.TodaysReading` (`scripturealone://todays-reading`, feature name `todays_reading`).

## Importing a church's plan

Only the **schedule** is imported: each post's title names the passage for its date ("Epistle- 1
Corinthians 5"), parsed with `ReferenceParser`. What the site republishes of the passage text is never
fetched or stored; the reader sees their own translation.

The importer (`PlanFeedImporter`) takes an address and tries, in order, what it could mean:

1. a WordPress site → the REST API (`/wp-json/wp/v2/posts`, paged 100 at a time, oldest first,
   `categories=` and `after=` honoured; `/wp-json/wp/v2/categories` lists the categories to choose from);
2. the site's RSS feed (`/feed/`) — typically only the latest posts;
3. an address already inside `/wp-json/…` or ending in `/feed`.

Posts whose title names no passage (an "About" page) are skipped and counted. Two posts on one date join
into one day. The weekdays with no reading anywhere become the plan's rest days. Re-importing the same
site and title gives the same plan id, so it replaces the days and keeps the reader's progress. A plan
followed by calendar picks up newly published days each time the app opens (quietly: offline, the days
already on the device are all it needs). A followed plan also **projects** the days the site hasn't posted yet: a church posts each day's reading on the day, but runs the same reading order every few years, so
the import reads five years back, finds where the last reading sat in the earlier pass, and lays what followed it then on the weekdays that pass read on, stopping where it
changed year (`PlanFeedImporter.projection`, `PlanDay.projected`, shown as “Expected”). Real posts replace the guess as they appear (the refresh keeps the plan's own projection and
re-anchors it on the newest real day), and nothing is projected when the last reading is found in no earlier pass. Swift only so far; Android still imports the last month.

Where the source links its **yearly plan as a PDF** (Disciples Church: <https://discipleschurch.com/bible/download/>, `Preset.planPage`), the import uses that instead of
guessing: `latestPlanPDF` picks the newest year's PDF from the page, the app reads its text with PDFKit and `parseScheduleText` turns the "Title · Week of 5-Jan · five readings" rows
into dated days (the first week may start in December of the year before; the year turns over when the months wrap). Days after the last post come from the PDF (shown as
*Expected* until the real post replaces them), and the projection above is the fallback when there is no PDF or it doesn't read as a year (fewer than 100 days). The 2026 PDF
agrees with every post so far and with the projection for the rest of the year. Only the references are read, never stored beyond the schedule.
Failures are told apart — *offline*, *not a feed*, *no readings* —
and leave nothing half-imported.

### Disciples Church — <https://discipleschurch.com/bible>

WordPress; category 2 (*Scripture*, ~3,700 daily posts, one chapter a day Monday–Friday, none on Sunday,
Saturday is the study) is the schedule; category 7 (*Saturday Study*, ~550 weekly studies) is commentary.
The preset is `PlanFeedImporter.disciplesChurch`.

**Saturday Study text** is the church's own writing. It is fetched on the device when the reader opens
*This Week's Study*, shown as plain paragraphs with a **link back** to the post, and the last one opened is
**kept on the device** (`StudyCache` on iOS, `plan-studies/` on Android; one per source, replaced by the
next, removed with the plan, excluded from backup) so it can be read offline. It is **never bundled,
shared or redistributed**; the importer takes the latest study published on or before today.

> **Licensing.** Disciples Church is the owner's own church, who expects them to be fine with it (2026-10-23);
> if they are not, the preset (`PlanFeedImporter.disciplesChurch` / `PlanImporter.DISCIPLES_CHURCH`) is one
> constant to drop. Reading a public feed on a reader's own device, with attribution and a link back, is the posture.
> The same applies to any other site a reader pastes — nothing from it ships in the app.

## Spurgeon's *Morning and Evening*

The first plan that ships its own writing: a morning and an evening meditation for every day of the year (366
days, so February 29 has its own), by C. H. Spurgeon (1866). It is **public domain** (Spurgeon died in 1892) and the
text is the original, from the Christian Classics Ethereal Library's edition (rights "Public Domain"), **not** the
modernised Alistair Begg edition, which is copyrighted. `Tools/build_spurgeon.py` turns the CCEL XML into
`ScriptureAlone/Resources/Plans/SpurgeonMorningEvening.json` (1.5 MB): a `ReadingPlan` whose days are
`MM-DD`, each with the two key verses as `passages` and the two meditations as `devotions`. iOS bundles the file;
Android's `syncBundledData` copies the same file into its assets. Both show the source and a link to CCEL.

- **Annual:** today's reading is the entry for today's date, and the plan starts over each January (progress is
  forgotten on the first open of the new year). There is no catch-up. In a common year February 29's entry is
  never reached.
- **Read aloud** reads each entry as its heading, the key verse in the reader's own translation, then the
  meditation. The meditation itself is Spurgeon's words (his KJV-style quotation is shown with it).
- **English only:** the text is English, so the plan is offered only when the device language is English
  (docs/localization.md: English-only commentary is hidden outside English).
- Spurgeon was a Particular Baptist; the owner asked for it by name (2026-10-24).

## Other devotional plans (men's, women's)

**Not built.** The owner won't write them; they are added only if a good Reformed Baptist source can be found and
licensed and its theology fits. Candidates: Spurgeon's *Faith's Checkbook* and *Daily Help* (public domain); Horatius
Bonar's *Light and Truth* (Reformed and respected, expository, but a paedobaptist Presbyterian); Mrs. Cowman's
*Streams in the Desert* is **not** a good default (Holiness/Keswick, outside the Reformed tradition); Bunyan and Puritan
daily-reading compilations (check each edition's licence); Reformed Baptist church plans (written permission needed).

## Not yet

- Live sync of plan progress between a reader's devices (iCloud on iOS; Android deliberately has none).
- Marking a day read automatically when Listen finishes it.
- An iOS notification tap deep-linking into Today (Android's opens the plans screen).
- Importing a separate *study* category from a pasted address (the preset knows Disciples'; a pasted site gets its schedule only).
- A Siri phrase for Start Today's Reading on Android (App Actions) — the deep link and feature name exist.
- Plan names and summaries (built-in catalogue text) are English only; the interface around them is translated on Android
  (machine-drafted — Levi to review) and English-only on iOS until Levi fills `Localizable.xcstrings`.
