# Scripture Alone — App Store metadata

<!-- en-US source of truth. Edit, then: rocket meta "Scripture Alone" -->

## name
Scripture Alone Bible

## subtitle
Private, offline Bible study

## description
Scripture Alone is a Bible for iPhone, iPad, Mac and Apple Watch, built for one thing: studying God's word without anything in the way. No ads. No trackers. No accounts. The Bible lives on your device, so it works anywhere, with or without a connection.

Read the whole Bible, offline
The American Standard Version is on your device from the moment it installs; the Berean Standard Bible and the King James Version download once, when you first choose one. Full-text search, the words of Christ in red, poetry set as poetry, and footnotes a tap away.

What the Bible says about what you're going through
Topics gathers passages for 72 themes — anxiety, grief, loneliness, fear, marriage, waiting and more — in the translation you read. Type a feeling like "anxious" in Go To and its topic comes up. In English, Nave's Topical Bible is there too, A to Z.

Bring your own Bibles
Import a DRM-free ePub, a Bible PDF with a text layer or a USFM file you own, or browse the openly licensed translations on eBible.org. A study Bible brings its notes, introductions, essays and maps; the words of Christ carry over in red; and your imports reach your other devices through your own iCloud. The ESV, CSB, NASB and NKJV can be read with your own publisher key, or from a copy you've imported.

In your language
Also in Chinese, Japanese, German, French, Spanish, Korean, Portuguese and Italian, each with a whole Bible of its own.

Make it comfortable
Seven typefaces, adjustable size and spacing, five themes, paragraphs or verse by verse, full Dynamic Type, two columns on a wide screen, and auto-scroll into the next chapter.

Go straight there
Type "jn 3 16" or "rom 8:28-39" to jump to a passage, or search any word or phrase.

Highlight, note and favorite
Highlight in five colors, favorite the verses you return to, and attach notes to one or more passages — a Sunday sermon on Romans 8:1–17, say. The Highlights list gathers every marked verse in Bible order, and Notes, Highlights and Favorites narrow to one book or chapter. Export notes as PDF, Markdown or text, or bring years of them in from Life Bible (formerly Tecarta) or a CSV.

Snap the sermon slide
Photograph the slide at church and Scripture Alone starts your note: titled, linked to every passage on the slide, its points ready to fill in. The photo is read on your device, never uploaded.

Listen
Hear any chapter read aloud with your device's voices, Premium and Personal Voice included, the spoken verse marked as it goes — or in Studio voices with a Mi Speaks Premium subscription (a separate app).

Study mode, a toggle away
Ranked cross-references, commentary from Calvin, Gill and Jamieson-Fausset-Brown, the Hebrew and Greek word by word with parsing, Strong's numbers and a lexicon in every translation, and Context for every chapter: an overview, a map of the places it names, its era on a timeline, and charts of the kings, Paul's journeys, the tribes and the feasts.

Share beautifully
Design a verse image from eight templates, or share a link that rebuilds it in any browser — nothing is stored on a server.

On your wrist and Home Screen
Verse of the Day and Favorites widgets, and an Apple Watch app with a complication that reads offline and carries your imported translations, highlights, favorites and notes, in the accent color you chose on your iPhone.

A Bible to hand down
Make a Keepsake Bible: a file of your highlights and notes, with a dedication, that your family can open and read as you marked it — a digital "Dad's Bible".

Private by design
Your highlights, notes and favorites sync across your devices through your own private iCloud. No server, no account, and the developer never sees your data. Scripture Alone is open source under the AGPL, so anyone can read exactly what it does.

Built to help people study God's word and grow closer to Christ.

## keywords
kjv,asv,bsb,topical,anxiety,grief,comfort,commentary,devotional,sermon,notes,epub,reformed,christian

## promotional_text
Find what the Bible says about what you're going through, bring your own Bibles, and study offline with commentary, maps and timelines. No ads, no accounts.

## whats_new
The first release of Scripture Alone.

## review_notes
Scripture Alone is an open-source Bible app (AGPL-3.0, https://github.com/blaineam/Scripture-Alone). No account or login is required for any feature, and the app reads offline from its first launch.

The American Standard Version, the default translation, is now built into the app itself, so a fresh install opens straight to the Bible with or without a network. (Build 40 relied on an asset pack for it, which is why it loaded indefinitely on your iPad; that path is gone.)

Content delivery (Apple-hosted Background Assets, on demand):
- bsb, kjv (on demand): the Berean Standard Bible and King James Version. Listed from the start; each downloads once the first time it is chosen (Translation menu, top right), then reads offline.
- study-commentary (on demand): Calvin, Gill and JFB. Downloads the first time the Commentary tab is opened.
- study-interlinear (on demand): the Hebrew and Greek. Downloads the first time Original Languages is opened.
Cross-references, maps, timeline and charts ship inside the app and need no download. All five texts are public domain.

Optional iCloud: highlights, notes and favorites sync through the user's own private CloudKit database. The developer operates no server and collects no data.

Quick tour: tap the passage title to jump anywhere (try "jn 3 16"). Tap verses to select them, then highlight, favorite, add a note, copy, share or listen. The book-and-wrench button turns on Study mode (cross-references, commentary, context); the map button opens maps, timeline and charts. The notes button opens the Notes panel, where the camera button scans a sermon slide (the camera is optional; a photo from the library works too). The Aa button holds reading options and Keepsake & Export.

Original languages: with a verse selected, choose Original Languages to see it word by word in Hebrew or Greek with parsing, Strong's numbers and a lexicon. This works in every translation; it downloads once on first use.

Additional translations (Manage Translations): dozens of public-domain translations can be downloaded from eBible.org, and a user may import a USFM zip or a DRM-free ePub they own. The Browse… button (globe icon) in Manage Translations fetches https://ebible.org/Scriptures/translations.csv (a public catalogue) only when tapped; no identifiers are sent. Files carrying any copy protection are refused and the app contains no decryption code. The ESV, CSB, NASB and NKJV cannot be licensed for redistribution by anyone, so they are not included — instead a user may paste their own API key from the publisher's own service (api.esv.org or api.bible) and those translations are then read over the network. NOTHING IN THE REVIEW REQUIRES A KEY: the three bundled translations are fully functional on a fresh install, and the key screen is optional.

Importing notes (Notes panel > Bring Your Notes): a user can import their own notes from Life Bible (formerly Tecarta Bible) using that app's own export file, or paste notes or a CSV from any other source. Nothing is fetched from another vendor's servers — the user supplies the file.

Mi Speaks integration is optional and only used when the user chooses Studio voices and has the Mi Speaks app installed.

No in-app purchases, no subscriptions, no ads, no tracking.

## marketing_url
https://wemiller.com/apps/scripture-alone/

## support_url
https://wemiller.com/apps/scripture-alone/

## privacy_policy_url
https://wemiller.com/privacy/

## availability
<!-- Store policy — applied by: rocket territories "Scripture Alone" --apply
     (_shared/rocket/docs/compliance.md)
     Free app, available in the EU as a non-trader (owner policy 2026-09-09), France included.
     Encryption is exempt (plist NO, OS crypto only), so no ANSSI filing applies.
     China stays out: religious apps there require a local licence. -->
exclude: china
new_territories: yes

## price
<!-- Base-territory (USA) customer price — applied by: rocket price "Scripture Alone" --apply -->
free
