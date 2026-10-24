# Reading plans

A plan is a schedule of passage **references** — nothing else. The text of a day's reading is always
read from the translation the reader has chosen, on the day, so a plan never carries (or licenses) any
Bible text, and it follows a change of translation at once.

Code: `ScriptureAloneCore/Sources/ScriptureAloneCore/Plans/` (model, schedule, importer — tested in
`ReadingPlanTests.swift`), `ScriptureAlone/Plans/` (store, reminders, screens),
`android/app/.../data/plans/` (the same logic in Kotlin, `ReadingPlanTest.kt`). The two test files
carry the same cases; add one to both or to neither.

## Built-in plans

| Plan | Shape |
|---|---|
| Bible in a Year | Genesis → Revelation in canonical order, 365 days, chapters spread evenly (about 3.3 a day). A day that crosses a book boundary holds a passage for each book. |
| Bible in a Year, Chronological | An editorial chronological order (`PlanCatalog.chronologicalOrder`), chapter-granular, 365 days. Every one of the 1,189 chapters appears exactly once (a test holds it). Psalms are placed in blocks with the events they belong to; the Gospels run Matthew → John rather than as a harmony. |
| Book by Book | Pick any of the 66 books: one chapter a day. |
| Men's / Women's Devotional | **Scaffolds only** — see below. |

Days are *ordinal*: day 1 is the start date, and a plan that skips weekdays (an imported one whose source
doesn't post Saturday and Sunday) moves on to the next eligible date. A plan whose source dated every day
can instead **follow the source's calendar**: today's reading is the one the source posts today.

## Progress

Per plan: the start date, the days done, an optional daily reminder time. From those, pure functions
(`PlanSchedule`) give Today (a reading, a rest day, not started, finished), the **missed days** (catch-up),
the **streak** (consecutive scheduled days read, ending today's — or yesterday's while today's is open, so
a streak survives until a day is really missed) and the fraction done.

iOS keeps plans and progress in `Application Support/Plans/plans.json`; it does not sync through iCloud
(see *Not yet* below).

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

A plan's own writing — a devotion, or the weekly study — can be read aloud too: *Read Aloud* on the study
screen and *Read Devotion Aloud* on Today call `ListenController.playProse(_:title:in:)`, one paragraph at a time
under the text's own title. It is opt-in: it starts only when the reader taps the button.

The **Start Today's Reading** App Intent (`ReadingIntents.swift`) opens today's passage and reads it. With
no plan started it says so rather than opening the app to nothing. An app gets ten Siri phrases and all ten
are taken, so it has no spoken phrase of its own yet — it is in the Shortcuts app's action list (owner
decision: which phrase to give up, or whether to fold it into *Listen*).

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
already on the device are all it needs). Failures are told apart — *offline*, *not a feed*, *no readings* —
and leave nothing half-imported.

### Disciples Church — <https://discipleschurch.com/bible>

WordPress; category 2 (*Scripture*, ~3,700 daily posts, one chapter a day Monday–Friday, none on Sunday,
Saturday is the study) is the schedule; category 7 (*Saturday Study*, ~550 weekly studies) is commentary.
The preset is `PlanFeedImporter.disciplesChurch`.

**Saturday Study text** is the church's own writing. It is fetched on the device when the reader opens
*This Week's Study*, shown as plain paragraphs with a **link back** to the post, and **never stored,
cached on disk by the app, bundled or redistributed**. The importer takes the latest study published on or
before today. Nothing in the app republishes it.

> **Licensing flag (owner).** We have not asked Disciples Church for permission. Reading a public feed on
> a reader's own device, for that reader, with attribution and a link back, is the posture here; if they
> object, or want it done differently, the study screen is one view (`StudyView`) and the preset one
> constant. The same applies to any other site a reader pastes — nothing from it ships in the app. A
> courtesy note to the church before this release would be wise.

## Devotional plans (men's, women's)

`ReadingPlan` already has the schema a devotional needs — each `PlanDay` has an optional `devotion` text
beside its passages — and `PlanCatalog.mensDevotional` / `womensDevotional` exist as empty, unlisted
scaffolds, so the structure, Today view and reminders are proved without any content. **Nothing is
written or licensed.** Decisions needed from the owner before content: who writes it (or whose public-
domain/licensed material), how long (30/90/365 days), which passages, whether it is translated into the
big 8 locales. Content would ship as JSON decoded with `ReadingPlan.decode`.

## Not yet

- iCloud sync of plan progress between a reader's devices (iOS) — progress is per device for now.
- A notification tap does not yet deep-link into Today.
- Marking a day read automatically when Listen finishes it.
- Importing a second, separate *study* category from a pasted address (the preset knows Disciples'; a
  pasted site gets its schedule only).
- Android's screens, reminders and read-aloud: see `docs/android-parity.md` › Reading plans.
- Plan names, summaries and the new interface strings are English only until Levi fills the eight
  languages (`docs/localization.md`).
