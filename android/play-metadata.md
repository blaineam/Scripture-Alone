# Scripture Alone — Google Play listing

<!-- en-US source of truth for the Play Console. Adapted from ../appstore-metadata.md: Android has no
     iCloud sync, no family sharing (keepsakes travel as files), no Personal Voice or Mi Speaks, and a
     Wear OS app instead of Apple Watch. Limits: title 30, short description 80, full 4000 chars. -->

## title
Scripture Alone Bible

## short_description
<!-- Play flags price/promotion words here ("free", "no ads") and may then not feature the app. -->
A private, offline Bible: topics for hard days, study tools, sermon notes.

## full_description
Scripture Alone is a Bible for Android phones, tablets and Wear OS, built for one thing: studying God's word without anything in the way. No ads. No trackers. No accounts. Reading, searching, highlighting and notes work anywhere, with or without a connection.

Read the whole Bible, offline
The American Standard Version and the Berean Standard Bible arrive with the app; the King James Version downloads once, when you first choose it. Full-text search, the words of Christ in red, poetry set as poetry, and footnotes a tap away.

What the Bible says about what you're going through
Topics gathers passages for 72 themes — anxiety, grief, loneliness, fear, marriage, waiting and more — in the translation you read. Type a feeling like "anxious" in Go To and its topic comes up. In English, Nave's Topical Bible is there too, A to Z.

Bring your own Bibles
Import a DRM-free ePub, a Bible PDF with a text layer or a USFM file you own, or browse the openly licensed translations on eBible.org. A study Bible brings its notes, introductions, essays and maps; the words of Christ carry over in red; and an import takes the place of the same translation read with an online key. Imports stay on your device.

Make it comfortable
Seven typefaces, adjustable size and spacing, five themes, paragraphs or verse by verse, and auto-scroll into the next chapter.

Go straight there
Type "jn 3 16" or "rom 8:28-39" to jump to a passage, or search any word or phrase.

Highlight, note and favorite
Highlight in five colors, favorite the verses you return to, and attach notes to one or more passages — a Sunday sermon on Romans 8:1–17, say. The Highlights list gathers every marked verse in Bible order, and Notes, Highlights and Favorites narrow to one book or chapter. Export notes as PDF, Markdown or text, or bring them in from Life Bible.

Snap the sermon slide
Photograph the slide at church and Scripture Alone starts your note: titled, linked to every passage on the slide, its points ready to fill in. The photo is read on your device, never uploaded.

Listen
Hear any chapter read aloud with your phone's voices, the spoken verse marked as it goes — screen off, from the lock screen or headset, with a sleep timer.

Study mode, a toggle away
Ranked cross-references, commentary from Calvin, Gill and Jamieson-Fausset-Brown, the Hebrew and Greek word by word with a lexicon, and Context for every chapter: an overview, a map of the places it names, its era on a timeline, and charts of the kings, Paul's journeys, the tribes and the feasts.

Share beautifully
Design a verse image from eight templates, or share a link that rebuilds it in any browser — nothing is stored on a server.

On your wrist and home screen
Verse of the Day and Favorites & Notes widgets, and a Wear OS app with a tile and a complication that reads offline and carries your imported translations, highlights, favorites and notes, in the accent color you chose on your phone.

A Bible to hand down
Make a Keepsake Bible: a file of your highlights and notes, with a dedication, that your family can open and read as you marked it — a digital "Dad's Bible". Keepsakes open on Android and iPhone alike.

Private by design
Your highlights, notes and favorites stay on your device. No server, no account, and the developer never sees your data. Scripture Alone is open source under the AGPL, so anyone can read exactly what it does.

Built to help people study God's word and grow closer to Christ.

## category
Books & Reference

## tags
Bible, Books & Reference

## contact_email
apps@wemiller.com

## website
https://wemiller.com/apps/scripture-alone/

## privacy_policy_url
https://wemiller.com/privacy/

## release_notes
The first release of Scripture Alone for Android.

## data_safety
<!-- Play Console → App content → Data safety -->
- Does the app collect or share any of the required user data types? **No.**
- Is all user data encrypted in transit? **Yes** — the only network traffic is HTTPS: eBible.org's catalogue and a translation the user picks, the optional ESV / API.Bible text with the user's own key, and Play asset-pack downloads.
- Do you provide a way for users to request that their data be deleted? Not applicable — no data leaves the device; uninstalling or clearing app storage removes it.
- Notes: highlights, notes, favorites and settings are stored only on the device. API keys the user enters are encrypted with the Android Keystore and backed up only through Google Block Store (end-to-end encrypted, the user's own account). No analytics, no advertising ID, no crash reporting. Google ML Kit (on-device text recognition for sermon slides) normally uploads SDK diagnostics — device/app info and a per-installation id — through Google's datatransport; the app strips that transport's three entry points from its manifest (`tools:node="remove"`), so nothing is sent. Re-check this after any ML Kit upgrade: if the transport's component names change, the removal silently stops applying.

## content_rating
<!-- Play Console → App content → Content rating (IARC questionnaire) -->
- Category: Reference, News, or Educational.
- Violence, sexuality, language, controlled substances, gambling: **No** to all. (Scripture is quoted text; the questionnaire treats a reference app's text as educational content.)
- User-generated content shared with others, chat, location sharing, purchases: **No.**
- Expected rating: Everyone / PEGI 3.

## target_audience
- Target age groups: 13+ (not designed for children; no child-directed content or features). Not in the Families program.

## ads
- Contains ads: **No.**

## app_access
- All functionality is available without special access. No login.
