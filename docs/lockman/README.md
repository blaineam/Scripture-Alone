# The NASB in Scripture Alone

The Lockman Foundation granted two Distribution Permission Agreements, effective **October 1, 2026**:
one for the **NASB 2020** and one for the **NASB 1995**. The signed agreements stay out of this
repository (`docs/licensing/` is git-ignored). This page lists what they require of the code and of
us, and how the text gets from Lockman into the app without breaking any of it.

## What the agreements require

| Condition | How it is met |
|---|---|
| Free to users: no charge, no access fee, no membership | The app is free with no in-app purchases. |
| Users must not be able to download a substantial portion of the text for use outside the app | The text ships only as a sealed `.sabible` package: AES-256-GCM per chapter, decrypted in memory one chapter at a time, with a content key that is never in this repository. Quotation leaving the app is capped by the signed package policy (`Tools/licensed/nasb.json`). |
| The applicable copyright notice, a **clickable link to www.Lockman.org**, and any notice Lockman designates, shown conspicuously | The notice comes word for word from `Tools/licensed/nasb.json`. It is drawn at the end of every chapter and in full on the Translations screen, and on both its "www.Lockman.org" is a working link (`NoticeLinks` on iOS, `noticeWithLinks` on Android). |
| "NASB 2020" / "NASB 1995" after quoted verses, with a link to www.Lockman.org, where the full notice doesn't fit | The package abbreviation is the designation itself (`NASB 2020`, `NASB 1995`), so every quotation is labelled with it, and shared text also carries the full notice, which ends in www.Lockman.org. Messaging apps turn that address into a link. A verse image can't hold a working link, so it carries the full notice instead. |
| Trademarks "NEW AMERICAN STANDARD BIBLE" and "NASB" only together with "2020" / "1995", and only to identify the version | Every name and abbreviation in `Tools/licensed/nasb.json` carries the year, and the two editions are never presented as one. |
| Text-to-speech only live, never recorded, cached or replayable | The system voice (`AVSpeechSynthesizer`, Android `TextToSpeech`) speaks live and keeps nothing, so it is allowed. The Mi Speaks Studio voice renders a recording, so the package sets `allowExternalHandoff: false`, and Listen falls back to the system voice for the NASB (`MiSpeaksClient.translationNotPermitted`). |
| **No AI or machine-learning use of any kind** without written permission. That includes training, fine-tuning, evaluation, embeddings, and *sending the text to* an AI system | The app has no AI feature. **Never paste, upload or attach NASB text or files to an AI assistant, coding agent or AI service**, including the one that wrote this page. Packaging runs locally or in CI with the scripts below, which call no AI service. |
| Promote Lockman and the NASB with a link to their website | The link on the Translations screen and on the chapter notice (above). |
| No sublicensing or transfer | The keys and packages stay with us. |
| Verse of the Day | Widgets and complications show the day's passage in the NASB 2020 for a NASB reader, read from the sealed package on the device into the widget snapshot (14 days ahead). The NASB text is never added to the public `DailyVerses.json`. |
| An annual report within 60 days after each October 1 | `.github/workflows/lockman-report.yml`; see "The annual report" below. |
| Agreement ends automatically if the NASB is unavailable for more than three consecutive months, or on 30 days' notice from Lockman | Don't pull it from a release without a replacement build ready. |
| Other websites need prior written notice to Lockman | Only https://wemiller.com/apps/scripture-alone/ is covered today. |

## Getting the text in, safely

1. **Lockman sends the files** as one coded text file per edition — `NASB 2020(b+n-r-num)(…).txt`,
   in a zip with two Word documents that define the codes ("Codes NC") and the front matter. The
   codes are Lockman's "Short Codes for LSB/NASB/LBLA/NBLA/AMP"; `Tools/lockman.py` documents how
   each one maps onto the reader's layout. Keep the files on your own machine, in an encrypted
   location, and nowhere else.
2. **Build a store outside the repository**, straight from Lockman's zip or the .txt in it. The tool
   refuses to write inside the repository:

   ```sh
   python3 Tools/build_bibles.py --licensed NASB2020 \
     --lockman ~/secure/nasb/"NASB 2020 (b+n-r-num)(08-12-26).zip" --out-dir ~/secure/nasb
   ```

   Its report names verse references, codes and counts — never a word of the text — so it is safe
   to share when something needs fixing. It stops on a code it doesn't know or on code characters
   left in a verse. If the parser needs to learn something new about the file, run
   `python3 Tools/lockman_probe.py <file.txt>` and share its output instead of the text: it prints
   the file's structure (codes, positions, punctuation counts, versification against the KJV) only.

   How the codes are presented:
   - Footnotes (`<$F … $E>`) become the reader's lettered notes; their `<FN>` chapter:verse prefix
     and the superior numbers and letters are dropped.
   - `*` (a historical present) is taken out of the verse text, so search, speech and sharing read
     cleanly, and shown as a tappable `*` note explaining it (`markers` in `Tools/licensed/nasb.json`).
   - Small caps: in the Old Testament they set LORD and GOD, uppercased so plain text keeps them
     apart from "Lord"; in the New Testament they mark Old Testament quotations, drawn in small caps.
   - Continuing quotes marked `+` are verse-format only: kept in single-verse text (sharing, Verse of
     the Day), removed from the paragraph layout. Those marked `-` are kept.
   - The thirteen verses the NASB 2020 moves to footnotes (Matthew 17:21 and others) have no text,
     as in print; the NASB 1995 has them all.

3. **Create the secret seed once, without anyone seeing it.** `SA_CONTENT_KEY_SEED` is the secret
   Xcode Cloud compiles in (`ci_scripts/ci_post_clone.sh`). On your Mac, these commands create it
   straight into your login keychain, copy it to the clipboard for the Xcode Cloud secret, and send it
   to GitHub. It is never printed:

   ```sh
   security add-generic-password -a scripture-alone -s SA_CONTENT_KEY_SEED \
     -w "$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
   security find-generic-password -s SA_CONTENT_KEY_SEED -w | pbcopy   # paste into Xcode Cloud › Environment › Secret
   security find-generic-password -s SA_CONTENT_KEY_SEED -w | gh secret set SA_CONTENT_KEY_SEED
   pbcopy < /dev/null                                                   # clear the clipboard
   ```

   Then package each edition:

   ```sh
   SA_CONTENT_KEY_SEED=$(security find-generic-password -s SA_CONTENT_KEY_SEED -w) \
     ./Tools/package_translation.py licensed --edition NASB2020 \
       --store ~/secure/nasb/NASB2020.sqlite --out ~/secure/nasb/NASB2020.sabible
   ```

   `licensed` takes identity, notice and policy from `Tools/licensed/nasb.json`, never from the file.
   It refuses the published ASV seed, and it refuses a store whose John 3:16 is not that edition's
   wording, so the 1995 text cannot go out labelled 2020 or the other way round. It signs with a
   one-time key and writes `NASB2020-signing.pub` for the app to pin. No signing secret exists to leak.
4. **Put the package in private storage.** Create a private repository (for example
   `blaineam/scripture-alone-licensed`) holding just `NASB2020.sabible` and `NASB2020-signing.pub`.
   Never add them to this repository; `.gitignore` refuses `Resources/Packages/NASB*`. Then create a
   fine-grained GitHub token with read-only Contents access to that one repository, and set it up in
   both build systems:

   | Where | Name | Value |
   |---|---|---|
   | Xcode Cloud workflow › Environment | `SA_LICENSED_REPO` | `blaineam/scripture-alone-licensed` |
   | Xcode Cloud workflow › Environment (secret) | `SA_LICENSED_TOKEN` | the token |
   | Xcode Cloud workflow › Environment (secret) | `SA_CONTENT_KEY_SEED` | from step 3 |
   | GitHub › Actions variable | `SA_LICENSED_REPO` | `blaineam/scripture-alone-licensed` |
   | GitHub › Actions secret | `SA_LICENSED_TOKEN` | the token |
   | GitHub › Actions secret | `SA_CONTENT_KEY_SEED` | from step 3 |

5. **It becomes the default by itself.** `ci_scripts/ci_post_clone.sh` (iOS) and the "Fetch licensed
   translations" step in `android.yml` copy the package into the build. When the package, its signing
   key and the seed are all present, the NASB 2020 is listed first and is what a fresh install opens
   to (`ReaderModel.defaultTranslation`, `BundledTranslations.DEFAULT`). When any of them is missing,
   the build ships no NASB and opens to the ASV exactly as before. Both builds refuse to bundle the
   package without the seed, so a default nobody can open can't ship. Readers who already chose a
   translation keep their choice.

## The annual report

`.github/workflows/lockman-report.yml` runs every **October 10**, or by hand from the Actions tab
with any date range. It fills [`annual-report-template.md`](annual-report-template.md) with:

- **Apple:** first-time downloads, re-downloads and updates from App Store Connect Sales and Trends,
  for the app's Apple ID only, counted day by day in a partly covered month.
- **Google Play:** user installs, updates and active device installs from the Play Console's
  monthly installs report.

It attaches the filled-in report to the run, ready to paste into an email to Lockman. Set up once:

| Where | Name | Value |
|---|---|---|
| Actions secret (exists) | `ASC_API_KEY_ID`, `ASC_API_ISSUER_ID`, `ASC_API_KEY_P8` | The key needs access to Sales and Trends; if the run says 403, give the key the Sales or Finance role |
| Actions secret (exists) | `PLAY_SERVICE_ACCOUNT_JSON` | Grant it **View app information and download bulk reports** in Play Console › Users and permissions |
| Actions variable | `ASC_VENDOR_NUMBER` | App Store Connect › Payments and Financial Reports, top left |
| Actions variable | `PLAY_REPORTS_BUCKET` | Play Console › Download reports › Statistics › Copy Cloud Storage URI |
| Actions variable | `NASB_AVAILABLE_FROM` | The date the first build with the NASB went live (YYYY-MM-DD) |

The first report is due **November 30, 2027**, for October 1, 2026 to September 30, 2027.
