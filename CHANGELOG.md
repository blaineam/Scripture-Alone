# Changelog

## After 1.0.0

- On iPhone, a verse you tap with Study open stays above the Study sheet (and the maps and
  places it opens): the page scrolls it into the part of the screen still showing, and the end of
  a chapter can be scrolled clear of the sheet. In the sheet, references and commentary scroll
  clear of the highlighter bar instead of ending under it.
- In Go To, Return opens the topic when the words you typed name one and no verse uses them.
- Topics: a way to find what the Bible says about what you're going through. In Go To there is
  now a Topics row — Anxiety & Worry, Fear, Grief & Loss, Loneliness and more — and See All opens
  72 topics in seven groups, from Depression & Despair and Guilt & Shame to Marriage, Parenting,
  Work, Waiting and God's Presence. Each one gathers a dozen or so well-known passages, shown in
  the translation you're reading; tap one to go there, or press and hold to add it to your
  Favorites, copy it or share it (as far as the translation's publisher allows). Typing a word
  like "anxious", "lonely" or "burned out" in Go To offers the matching topic above the verses, in
  any of the app's nine languages. In English, the directory also holds Nave's Topical Bible from
  A to Z — more than 5,000 topics, each passage a link. On iPhone, iPad, Mac and Android.
- With an imported study Bible, the Commentary tab still offers the bundled commentators
  (Calvin, Gill, and Jamieson, Fausset & Brown) to download, in a row above the study Bible's notes.
- Fixed a crash opening Manage Translations on a Mac.
- The iCloud record types for notes, highlights, favorites, family sharing and imported
  translations are now written down in `Tools/cloudkit/schema.ckdb`, to import with `cktool`.
- On a wide screen — an iPhone on its side, an iPad, a wide Mac window — the reader lays a
  chapter out in two columns, like a printed Bible, and turns page by page with a
  swipe (or the arrow keys, space bar or trackpad on a Mac); swiping past the last page moves
  to the next chapter. Tapping a verse still selects it. Columns can be turned off under
  Appearance, and auto-scroll keeps the single column.
- The import summary lists verses a translation leaves out because the oldest manuscripts
  don't have them (such as Acts 8:37) under their own heading instead of as gaps, so a clean
  file of a modern translation reads as clean.
- Android imports ePubs the way iPhone, iPad and Mac do: study-Bible shapes bring in the whole
  Bible and nothing else (drop-cap chapters, each file's own title, headings above the verse they
  introduce, notes pages, boxed essays, captions and "next book" links kept out of the text, red
  letters wherever the stylesheet colors them), every import is judged by the same quality gate
  and refused with its score when it reads too poorly, and a study Bible titled for itself is
  recognised from its copyright page.
- On Android too, an imported study Bible brings its notes into the Commentary picker (each
  note on every verse it covers, in any language), its book introductions with each book's first
  chapter, its essays at the verse they stand beside, and its maps and pictures under Maps &
  Images in the Context tab, full size on a tap. They go when the translation is removed.
- On Android, an imported translation whose file doesn't mark the words of Christ takes them,
  verse by verse, from the BSB (or KJV) on the device, marking only words that line up closely.
- Android copies and shares by each publisher's own terms too: the verse limit, the whole-book
  and share-of-a-book rules, no verse images where the publisher licenses them separately, no
  sharing where it allows none from an app, and the exact notice with every copy (the short form
  on a verse image, where the publisher accepts one). The import summary says which terms apply,
  and imported translations show their abbreviation, not an internal id, in the pickers.
- On Android, a translation you've imported is used in place of the same translation behind an
  online key, which no longer shows beside it, and an import can be removed with a long press as
  well as with its delete button.
- Android imports Bible PDFs with a text layer too, read from how they are typeset: sizes against
  the file's own body type tell titles, drop caps, verse numbers, headings, footnotes and page
  furniture apart; rows, word spaces and two-column reading order are rebuilt from where each
  glyph sits; words broken across lines are joined (or keep their hyphen) from the book's own
  vocabulary; small capitals come back as LORD; and each footnote finds its verse. Locked and
  copy-protected PDFs are refused, and a PDF meets the same quality gate as any import.
- Importing a Bible PDF on iPhone, iPad and Mac no longer drops or scrambles verses: the gap
  between the columns is found from the text itself, so a short last line or a verse number
  beside the gutter stays in its own column; lines set beside a chapter's large number read in
  order; a verse number stacked in a list or run onto the page's printer mark is found; measures
  like "10 1/2 feet" and a psalm's printed "1" after its title are no longer taken for verse
  numbers; words split by the type ("profi t", "diff icult") or broken across lines are joined,
  while a compound keeps its hyphen; and running heads, headings in two type sizes and double
  footnote marks are recognised.
- An imported English translation whose file doesn't mark the words of Christ gets them from
  the BSB (or KJV) on the device: each verse is matched word for word, and only the words that
  line up with red ones turn red — "Jesus said to him" stays black, "I am the way" turns red. A
  verse that doesn't line up closely, or a translation in another language, is left as it is,
  and a file that marks its own red letters keeps them.
- A study Bible you import brings its study material with it, kept apart from the text: its
  notes appear as a source in the Commentary picker (each note on every verse it covers), book
  introductions and outlines open with each book's first chapter, essays sit at the verse they
  stand beside, and its maps and pictures appear under Maps & Images in the Context tab, full
  size on a tap. The notes are credited to the study Bible's publisher, travel with the
  translation through iCloud, and go when it is removed. Commentary shows for an imported study
  Bible in any language.
- A Bible PDF with a text layer can be imported, read from how it is typeset rather than how
  any one publisher lays it out: the size of its text against its own body type tells book
  titles, chapter drop caps, verse numbers, headings, footnotes and page furniture apart; its
  columns are read in order, whatever side of the sheet; words broken across lines or at
  ligatures are joined from the book's own vocabulary; small capitals come back as LORD; and
  each footnote finds its verse. Locked and copy-protected PDFs are refused.
- Every import is judged before it is kept: chapters whole and in order, and text free of the
  debris a bad read leaves. A file that reads too poorly is refused with its score, and nothing
  is added.
- A translation you've imported is used in place of the same translation behind an online key,
  which no longer shows beside it. Imports can be removed from a long-press or right-click menu
  as well as by swiping.
- Translations you import follow you: each one is kept in your own private iCloud and appears on
  your other iPhone, iPad and Mac when it arrives — nothing is shared with anyone else, launch
  never waits on it, and removing one removes it everywhere.
- Every translation you import goes to your Apple Watch too, not only the one you're reading,
  when its publisher's terms allow keeping it offline. A re-imported translation is sent again,
  one removed on the phone leaves the watch, and the watch lists imports by abbreviation.
- Copying and sharing follow each translation's own published terms. The app knows the
  permissions pages of the ESV, CSB, HCSB, NIV, NKJV, NASB, AMP, LSB, NLT, The Message, NRSV,
  NRSVue, RSV, NET and CEB: its verse limit, whether a whole book or more than half of one may be
  quoted, and the exact notice to carry, which now travels with every copy, share and Shortcut.
  Publishers that license verse art separately (NIV, NKJV, NLT, The Message) have no verse
  images; those that don't allow sharing from an app (NRSV, NRSVue, RSV, The Message) can still
  be copied for your own use. An import is recognised from its copyright page — a study Bible
  titled for itself still quotes as the ESV — and the import summary says which terms apply.
  Imported translations show their abbreviation, not an internal id, in the picker.
- Importing a study-Bible ePub brings in the whole Bible and nothing but the Bible. Chapters set
  as drop caps keep their first verse; each file's own title says which book it is; psalm titles,
  acrostic letters and section headings land above the verse they introduce; indented poetry
  keeps its indent. Study notes, cross-reference letters, boxed essays, map captions,
  concordances, footnote and cross-reference pages, and "next book" links no longer run into
  the text, and the translation's own footnotes keep only their note. Red letters are kept
  wherever the file colors them, under any class name its stylesheet makes red or an inline
  color.
- The commentary picker has an ⓘ that says where each commentator stands — Calvin (Reformed, infant
  baptism, Presbyterian), Gill (Particular Baptist) and Jamieson-Fausset-Brown (Presbyterian and
  Anglican) — so a reader knows whose tradition is speaking on baptism and church-order passages.
- Scripture Alone speaks eight more languages — Simplified Chinese, Japanese, German, French,
  Spanish, Korean, Brazilian Portuguese and Italian — on iPhone, iPad, Apple Watch, the widgets
  and Android. Each language comes with a whole 66-book Bible of its own: the 和合本, the 文語訳,
  the Lutherbibel 1912, Louis Segond, the Reina-Valera 1909, the 개역한글, the Bíblia Livre and the
  Riveduta. The app opens to the ASV at once and switches when the reader's Bible has downloaded;
  it never waits on it. Each Bible keeps its own verse numbering on screen while highlights,
  notes and shared links stay interchangeable between translations. Book names, typed
  references (3章16節, 3장 16절, Joh 3,16), search (Chinese, Japanese and Korean included), the
  study panel's places, people and feasts, the Verse of the Day and Listen all follow the
  reader's language. The commentary and lexicon definitions exist only in English and are hidden
  elsewhere; the Hebrew and Greek keep their words, parsing and Strong's numbers.
- Shortcuts and Siri: open a passage, get a verse in any translation, make a verse image, create
  and find notes, favorite or unfavorite a verse, and open the Verse of the Day. Notes and
  favorites can be added to Spotlight (off until switched on in Settings), and a result opens
  straight to the note or verse. Any standard OSIS reference opens the app — urn:osis:John.3.16,
  scripturealone://open?ref=Ps.23 — as do wemiller.com passage links. Tapping the Verse of the
  Day widget opens that verse.
- Android has the same links, app shortcuts and system search for notes and favorites.
- Settings come back on a reinstall or a new device: the theme, accent colour, typeface, text
  size (kept per iPhone, iPad and Mac), spacing, layout, red letters, the translation, recent
  chapters and searches, study, Listen, share-card and Spotlight choices, the keepsake owner and
  dedication, and the API.Bible translations picked all sync through iCloud key-value storage,
  and apply as soon as they arrive — launch never waits on them. API keys stay in the keychain
  and ride iCloud Keychain; a key saved by an early build as a device-only item is moved over
  once. Voices stay on the device that has them. On Android the same settings return through
  Android's backup, now limited to the reader's settings and library so downloaded Bibles can
  no longer push it over its size limit and skip it altogether; keys stay in Block Store.

- A fresh install always opens to the Bible. The American Standard Version ships inside the app
  again rather than as an asset pack meant to arrive with the install: App Review's iPad launched
  to a spinner that never ended when it didn't. If the ASV ever can't be opened, the reader now
  says why and offers Try Again instead of spinning forever, and a device key that can't be read
  is re-sealed rather than locking the translation away. The app is about 16 MB larger; the BSB,
  KJV, commentary and original languages still download the first time they're chosen.

- Scripture Alone for Android is in closed beta on Google Play: phones, tablets and a Wear OS
  app, built from the same bundled Bibles and study data as the iPhone app. Join the
  scripture-alone-android-beta Google Group, then opt in on Google Play.
- Imported ePub and USFM Bibles come out whole. Words of Christ marked `class="WJ"` are red;
  footnote letters no longer stick to the word before them ("saidb"), and a verse number styled
  as a superscript no longer loses its verse; a line break keeps its space ("children. These");
  a psalm title written straight before verse 1 no longer swallows the verse; soft hyphens are
  removed, so the words they sat in can be searched. Damaged or hostile files are refused rather
  than exhausting memory, and a crashed import leaves no stale journal behind. The bundled
  ASV, BSB and KJV import exactly as before.
- Importing notes from Life Bible or a pasted list no longer invents highlights. A highlight that
  crossed a chapter break (Genesis 1:30–2:2) was filled in verse by verse through every possible
  verse number, nearly a thousand verses that don't exist; it now follows the chapter's real
  length. Six-letter words made of the letters a–f ("decade", "facade") are no longer read as
  highlight colours, and a catalogue listing with a repeated column no longer crashes the app.
- Section headings in an online translation's cached chapters keep their words. API.Bible
  chapters were cached with each heading ("Jesus and Nicodemus" in John 3) stored blank, so
  headings vanished once a chapter was read offline; existing caches are discarded and refetched.
  Words of Christ ending in a space at a verse boundary are no longer dropped.
- A notes export that quotes a copyrighted translation always ends with the publisher's notice.
  It was missing from a single-note Markdown export, from each file of a Markdown folder, and
  from the PDF. The bundled ASV, BSB and KJV are public domain and carry no notice, so no export
  so far was affected; a licensed translation's would have been.
- A verse with two footnotes letters them in reading order. They were lettered "b … a" — the
  markers were inserted from the end of the verse, which is right, but lettered in that same
  reversed pass. 345 verses of the Berean Standard Bible have more than one note, starting at
  Genesis 5:2. The Android port lettered them correctly, which is how this came to light.

## 1.0.0 (in review)

- The Apple Watch reads the ASV, the BSB and the KJV, and follows the translation you are reading
  on your iPhone. It had carried the ASV alone. Each translation the phone bundles now has a
  compact watch edition — verse text and red letters without the phone's layout data or search
  index, about 4.5 MB each — and a Translation row on the watch's home screen picks between them.
  Switch translation on the phone and the watch switches too; pick one on the watch and it keeps
  that until you change the phone again — the most recent choice wins. A translation you imported
  on the phone reaches the watch as well: the phone writes a compact edition of it and sends it
  over WatchConnectivity, but only if its terms allow offline storage, and only if the watch
  doesn't already hold it. Online translations stay on the phone, since their terms forbid storing
  them, and the watch says so rather than silently showing something else.

- Signed, encrypted translation packages (`.sabible`), for a licensed translation a publisher has
  agreed to: a plaintext, Ed25519-signed header carrying the translation's identity and the
  publisher's terms, and a body of chapters sealed one at a time with AES-256-GCM, each bound to
  its package, translation, chapter and the exact header — so a chapter moved between packages, a
  chapter replayed after the terms tightened, and an edited policy all fail rather than pass. A
  package is decrypted a chapter at a time and never as a whole; the terms (copy, share, verse
  images, notes export, hand-off to other apps, offline storage, quotation limit, expiry) are
  enforced by the same `TranslationRights` gate that governs the bundled public-domain texts.
  `Tools/package_translation.py` builds packages on the publisher's own machine and refuses to
  read a key from inside this repository. The mechanism is demonstrated on the bundled ASV and
  BSB; see `docs/encrypted-translations.md`, which also states what the design cannot do.
- A packaged translation is fully searchable, phrases included, without a plaintext index existing at
  any point. Postings carry word positions, so a phrase is an adjacency check rather than a text scan;
  prefixes of three to ten characters have their own postings, so results narrow while typing. The
  postings are bucketed by a keyed hash and each bucket is sealed like a chapter — a hashed-token
  index left in the clear would, for a Bible, let an attacker align frequencies against a public
  edition until every hash was identified. Searching the commonest word in scripture opens one bucket
  of 256 and decrypts at most thirty chapters of 1,189, and the reader counts what it opened so the
  tests can say so.
- The icon's starburst is white, and no longer seams. It had been drawn as a hub rectangle with
  four triangles abutting it, and two independently anti-aliased edges never sum to full coverage
  along the edge they share — so every join showed a hairline, which the layer's glass shading then
  lit. It is one continuous outline now, with no internal edges to seam. The sun is centred in the
  square, and the burst's left and right arms match its top; the bottom still reaches down into
  the sun.

- The app itself is about 37 MB instead of about 124 MB. The Bibles and the study databases are
  Apple-hosted Background Assets now. The American Standard Version arrives with the install, so a
  fresh install reads offline before it has ever reached a network. The Berean Standard Bible and
  the King James Version are listed from the start and download once, the first time you choose
  one — the reader keeps what you were reading, with a banner showing progress, and switches when
  it arrives. The commentary and the original languages download the first time you open them.
  Each is downloaded once and kept: they had been On-Demand Resources, and ODR ties a pack to an
  app *version* — Apple's own words: the system "does not have any notion of understanding whether
  the files in an asset pack are identical across app versions" — so 55 MB came down again after
  every update even though nothing in it had changed. ODR is also deprecated as of iOS 27.

- Cross references no longer wait on the commentary. They lived in the same database, so the
  whole Study panel — references, commentary and even Context — asked for a 44 MB download before
  showing anything. Cross references are now a small database of their own that ships in the app;
  only the Commentary tab asks to download.

- The Hebrew and Greek behind a verse is available in every translation, not only the Berean
  Standard Bible. The word-by-word data is keyed to the BSB's wording, and the whole feature was
  gated on that — so anyone reading the ASV, the KJV, an import or an online translation was denied
  the original languages, the parsing, the Strong's numbers and the lexicon as well, none of which
  depend on the English in front of them. The lookup is offered everywhere now; the English beside
  each word is the BSB's, and the sheet says so rather than leaving it to be noticed.

- Closing the Listen player closes it. The lock-screen and headset transport controls were
  registered once and never switched off, and toggling play from one of them while nothing was
  playing would *start* a new chapter — so a stray command from a headset, a car or a stale
  now-playing entry could restart playback right after you tapped the X, and put the player back on
  screen. Toggling can no longer start a session (that is the toolbar's job), remote commands are
  ignored while the player is closed, and closing it takes the app out of the now-playing role.

- An online translation opens on launch instead of claiming it needs a key. The app installed the
  code that fetches chapters *after* registering the translations — and registering them can
  select one immediately, now that your last translation is restored. So the first fetch of the
  session failed and reported a missing key, when the key was fine and the app simply wasn't wired
  up yet. Switching away and back fixed it, which is the signature of an ordering problem rather
  than a missing key. The order is right now, and installing the fetcher also retries a chapter
  that was waiting on it, so the same mistake can't be reintroduced by rearranging startup.
- Notes can come from any app, not only Life Bible. Paste them, or choose a CSV or text file you
  exported from somewhere else: entries are read by shape — a reference, and the text belonging to
  it — rather than by a list of vendors whose files nobody here has seen. Quoted commas and
  newlines inside a cell are handled, a colour column becomes highlights, a bare reference becomes
  a saved verse, and anything that names no verse is shown to you rather than guessed at.

- Your notes can come with you from Life Bible — the app that used to be called Tecarta Bible.
  Keepsake & Export → Bring Notes From Another App reads the `LifeBibleData.zip` it exports, and
  brings across notes on verses, journal entries, highlights with their colours, and saved verses.
  It says what it found before writing anything, names anything it could not place rather than
  guessing at a verse, and running it twice adds nothing twice.

- The translation you were reading comes back when you reopen the app. Only the bundled
  translations exist at launch — imports arrive from the library and online ones from your keys, a
  moment later — so a reader whose translation was either of those found it missing, and the app
  fell back. Worse, the fallback was written down as though you had chosen it, which destroyed the
  real preference before the translation could arrive: the choice could never return, on that
  launch or any after it. A fallback is no longer mistaken for a choice, and the moment the
  translation registers it is selected.
- The API.Bible translations you picked survive a restart. Which Bibles a key may read is chosen on
  API.Bible's dashboard and identified by opaque ids, and the app only ever learned them from the
  keys screen — so they vanished from the picker until you opened that screen again. The picks are
  remembered now (the ids and names, not the key, which stays in the keychain) and restored at
  launch with no network call. Removing the key still removes the translations.

- An online translation no longer shows the chapter you navigated away from. The toolbar moved to
  the new reference as soon as you turned the page, but the text underneath was the previous
  chapter's until the fetch returned — the right address over the wrong words, which for scripture
  is the worst way to be wrong. The layout now travels with the chapter it was built for, the
  reader refuses to draw a mismatch, and a fetch for a different chapter shows that it is loading.
  Re-reading the chapter already on screen keeps its text, since there is nothing to confuse.

- Online translations have the words of Christ in red, and psalms that look like psalms. Both
  services were being asked for plain text, which is a *rendering* — and everything that makes
  scripture look like scripture is thrown away in making it. Crossway's text endpoint has no
  red-letter option at all, and API.Bible's text output is a flat string. Asked for HTML instead,
  both state their structure outright: the ESV marks the words of Christ and its poetry lines, and
  API.Bible's markup is USFM with the markers as class names — the same vocabulary this app's own
  layout speaks. So an online chapter now arrives with its red letters, its poetry, its psalm
  superscriptions and its headings, rather than as one flat paragraph. Chapters cached under the
  old rules are re-fetched.

- An online translation no longer renders with broken spacing. Both APIs send poetry as hard
  newlines and leading spaces — the shape a terminal would print — and the reader was drawing them
  literally, which produced breaks in the middle of a sentence, stray indents, and two verses
  colliding on one line. That whitespace is now collapsed to ordinary spaces, so an online chapter
  reads as clean prose. It is a real loss for the Psalms, and deliberate: whitespace cannot tell a
  poetic line from a wrapped one, and printing scripture in a shape we guessed at is worse than
  printing it plainly. Chapters cached under the old rules are discarded and re-fetched, so the fix
  reaches passages already read.

- The American Standard Version now ships as a signed, encrypted package instead of a database, and
  it is still the translation the app opens by default. There is no `ASV.sqlite` in the app to fall
  back on: every launch derives the content key, unwraps it from the Secure Enclave, verifies the
  publisher signature and reads chapters decrypted one at a time, and searching it searches a
  sealed index. Nothing about it looks different to a reader, which is the point — the encrypted
  path is the ordinary path now, not a sample sitting beside the real thing.
- Sealing it takes nothing away. The text is public domain, so the package permits everything a
  public-domain text may do — no disabled controls, no quotation cap. That a package can *forbid*
  things, and that the app obeys, is proved separately against packages built to forbid them.
- The reader draws every translation through one seam (`ChapterTextSource`), so a bundled store, an
  imported file, an online translation's cache and an encrypted package are read by the same code
  rather than by four branches that have to be kept in step. Listening, sharing, favourites, notes,
  cross references, commentary, compare and the slide scanner all ask the source rather than
  demanding a SQLite file, so none of them goes quiet on a packaged translation.
- A translation's terms now reach the controls a reader touches. Copy and share are disabled, with
  a line saying whose limit it is and what it is, when a selection is larger than the translation
  allows; exporting notes is refused when its terms forbid carrying the text out; and the Mi Speaks
  hand-off already asked. The terms come from the signed package for a packaged translation and
  from the licence line for everything else — one question, asked the same way.
- On-demand resources now work on the Mac, where they do not exist. `NSBundleResourceRequest` is
  unavailable on macOS, so the Mac build ships every pack inside the app and asks the bundle rather
  than the network; a Mac download is one file either way.
- App icon: a gold sun on the horizon behind the open Bible, with a cross-shaped starburst
  flaring off its rim — the crossing sits on the sun's edge the way a camera flares a light,
  its arms reaching into the sky and its long foot down the sun's face.
- First build: offline ASV, BSB and KJV with full-text search, red letters, section headings,
  poetry and footnotes.
- Reader with paragraph and verse-by-verse layouts, seven typefaces, five themes, adjustable
  size and spacing, and auto-scroll.
- The commentary and the original-language data download the first time you open them, which
  halves the initial install. All three translations, the maps, the timeline and the cross
  references stay in the app, so a fresh install reads scripture offline with no network at all.
- Compare two translations side by side, verse aligned, with a verse only one of them prints
  marked rather than silently shifting the rest.
- Original-language study: select a verse in the Berean Standard Bible to see the Hebrew or Greek
  behind each English word, with transliteration, parsing, Strong's number and the lexicon entry.
- Online translations: the ESV through Crossway's API and the CSB, NASB and NKJV through
  API.Bible, each read with a free key you register yourself. Nobody may give these away, so they
  are read over the network and cached within the publisher's own limit.
- Add a translation: browse the free translations published by eBible.org — over 1,200 in more
  than 1,000 languages, with your own languages first — or import a USFM zip or a DRM-free ePub
  you already own. Nothing is downloaded until you choose something, imported translations stay
  on the device, and anything copy-protected is refused outright. Every import ends with a
  coverage report, so a file that yields half of Genesis says so.
- Accent colour: seven accents (Sunrise, Ember, Olive, Sea, Lapis, Plum, Ink) for verse
  numbers, links, selection and the app's controls, each tuned separately for light and dark
  pages. Under Appearance, beside the themes.
- Feedback, rating and About rows (MillerKit): a guided bug report that carries version and
  device, a link to the other apps, and the privacy policy — no analytics behind any of it.
- Quick passage jump ("jn 3 16", "rom 8:28-39") and book/chapter browsing, with recent chapters
  and recent searches kept between launches — tap a past search to run it again, or clear the list.
- Highlights in five colors and notes on verse ranges, shown inline and in a Notes panel,
  synced through your private iCloud.
- Camera notes: photograph a sermon slide (live text scanner, camera, Photos; image file, paste
  or Continuity Camera on Mac) and review a note titled from the slide, linked to its passages,
  with its other lines as bullets — or add a later slide to an existing note. Recognition runs
  on device; the photo is kept only if you choose to.
- Study mode: turn it on from the toolbar and the Study panel follows the verse you tap. It shows
  ranked cross references with their text (jump there and back, or press and hold to preview) and
  commentary from Calvin, Gill and Jamieson‑Fausset‑Brown with linked references. An About Study
  Resources page lists every source's license and attribution. Everything works offline.
- Maps & Timeline: each chapter's era on a timeline of the biblical periods, an offline map of
  the places it names (tap a place for every verse that mentions it), and charts of the kings
  of Israel and Judah, Paul's journeys, the twelve tribes and the feasts of Israel. Opens in its
  own window on iPad and Mac to sit beside the text.
- Keepsake Bible: make a keepsake of your highlights and notes for family, optionally protected
  with a passphrase; open keepsakes you're given and read them as a read-only "Dad's Bible",
  with their highlights, notes and dedication, kept apart from your own.
- Share with Family: invite named family members to see your highlights, notes and favorites
  live and read-only, through iCloud with no server in between. They read it as "Dad's Bible —
  shared live, updated 5 min. ago", offline too, apart from their own notes. Stop sharing any
  time; family keep the last copy and can save it as a keepsake.
- Export notes as a PDF, Markdown (one file or a folder) or plain text, with verse text, from
  the Notes panel.
- Favorites: tap verses, then the heart. Favorites have their own tab in the Notes panel and
  sync through iCloud.
- Widgets for iPhone, iPad and Mac: Verse of the Day (a hand-picked passage for every day of
  the year, in your translation, with red letters) and Favorites & Notes (rotates through your
  favorited, highlighted and noted verses, with a Next button). Lock Screen widgets on iPhone.
  Tapping a widget opens the passage.
- Apple Watch app: Verse of the Day, favorites, notes, and a reader for the whole ASV, all
  offline and synced through iCloud. Speak reads a passage aloud. Watch face complications
  show the day's reference.
- App icon: an open book as the horizon with the sun rising from its fold, built as a layered
  Liquid Glass icon with light, dark, tinted and clear appearances.
