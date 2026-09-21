# Scripture Alone — Google Play listing

<!-- en-US source of truth for the Play Console. Adapted from ../appstore-metadata.md: Android has no
     iCloud sync, no family sharing (keepsakes travel as files), no Personal Voice or Mi Speaks, and a
     Wear OS app instead of Apple Watch. Limits: title 30, short description 80, full 4000 chars. -->

## title
Scripture Alone Bible

## short_description
<!-- Play flags price/promotion words here ("free", "no ads") and may then not feature the app. -->
A private, offline Bible with study tools, sermon notes and maps. No account.

## full_description
Scripture Alone is a free Bible for Android phones, tablets and Wear OS, built for one thing: studying God's word without anything in the way. No ads. No trackers. No accounts. Two complete translations arrive with the app, and a third is a tap away, so reading, searching, highlighting and notes work anywhere in the world, with or without a connection. The large study add-ons — the commentary and the original-language data — download the first time you open them, so the app itself stays small.

Read the whole Bible, offline
The American Standard Version, the Berean Standard Bible and the King James Version live on your device, with full-text search. The words of Christ appear in red, poetry is set as poetry, and section headings and footnotes are a tap away.

Bring your own translation
Browse the free translations published by eBible.org — more than 1,200, in over 1,000 languages, with yours first — or import a USFM file or a DRM-free ePub you already own. Nothing downloads until you choose it, and what you add stays on your device.

Make it comfortable
Seven typefaces, adjustable size and line spacing, five themes (Auto, Light, Sepia, Dark and Black), paragraphs or verse by verse, and your phone's font size. Auto-scroll keeps the text moving hands-free and carries on into the next chapter.

Go straight there
Type "jn 3 16" or "rom 8:28-39" to jump to a passage, or search any word or phrase. The chapters you have read and the words you have searched are waiting the next time you open it.

Highlight, note and favorite
Highlight in five colors, favorite the verses you return to, and attach notes to one or more passages — a Sunday sermon on Romans 8:1–17, say. Notes appear beside the verse and in a searchable Notes panel. Export them as a PDF, Markdown or plain text, or bring notes in from Life Bible or a pasted list.

Snap the sermon slide
Photograph the slide at church and Scripture Alone starts your note: titled with the sermon title, linked to every passage on the slide, with its points ready to fill in. The photo is read on your device and never uploaded.

Listen
Hear any chapter read aloud with the voices on your phone, with the spoken verse marked as it goes. It keeps reading with the screen off, with controls on the lock screen and your headset, and a sleep timer.

Study mode, a toggle away
Ranked cross-references, classic commentary from John Calvin, John Gill and Jamieson-Fausset-Brown, the original Hebrew and Greek word by word with a lexicon, and the context of every chapter: its era on a timeline of the biblical periods, an offline map of the places it names, and charts of the kings of Israel and Judah, Paul's journeys, the twelve tribes and the feasts of Israel.

Share beautifully
Design a verse image right on your phone from eight templates, or share a link that rebuilds it in any browser from the link itself — nothing is stored on a server.

On your wrist and home screen
Verse of the Day and Favorites & Notes widgets, and a Wear OS app with its own offline Bible, a tile and a complication with the day's reference.

A Bible to hand down
Make a Keepsake Bible: a file of your highlights and notes, with a dedication, that your family can open and read as you marked it — a digital "Dad's Bible". Keepsakes open on Android and iPhone alike.

Private by design
Your highlights, notes and favorites stay on your device. There is no server and no account, and the developer never sees your data. Scripture Alone is open source under the AGPL, so anyone can read exactly what it does.

Free, and it will stay free. Built to help people study God's word and grow closer to Christ.

## category
Books & Reference

## tags
Bible, Books & Reference

## contact_email
apps@wemiller.com

## website
https://wemiller.com/apps/scripture-alone/

## privacy_policy_url
https://wemiller.com/apps/scripture-alone/privacy/

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
