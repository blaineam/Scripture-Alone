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
| The applicable copyright notice, a **clickable link to www.Lockman.org**, and any notice Lockman designates, shown conspicuously | The notice comes word for word from `Tools/licensed/nasb.json` and is drawn at the end of every chapter. Still to build before release: the chapter-end notice must link to https://www.lockman.org, and so must the Translations screen. |
| "NASB 2020" / "NASB 1995" after quoted verses, with a link to www.Lockman.org, where the full notice doesn't fit | The package abbreviation is the designation itself (`NASB 2020`, `NASB 1995`), so every quotation is labelled with it. Still to build before release: shared text and verse images must carry the www.Lockman.org link. |
| Trademarks "NEW AMERICAN STANDARD BIBLE" and "NASB" only together with "2020" / "1995", and only to identify the version | Every name and abbreviation in `Tools/licensed/nasb.json` carries the year, and the two editions are never presented as one. |
| Text-to-speech only live, never recorded, cached or replayable | The system voice (`AVSpeechSynthesizer`, Android `TextToSpeech`) speaks live and keeps nothing, so it is allowed. The Mi Speaks Studio voice renders a recording, so the package sets `allowExternalHandoff: false`, and Listen falls back to the system voice for the NASB (`MiSpeaksClient.translationNotPermitted`). |
| **No AI or machine-learning use of any kind** without written permission. That includes training, fine-tuning, evaluation, embeddings, and *sending the text to* an AI system | The app has no AI feature. **Never paste, upload or attach NASB text or files to an AI assistant, coding agent or AI service**, including the one that wrote this page. Packaging runs locally or in CI with the scripts below, which call no AI service. |
| Promote Lockman and the NASB with a link to their website | The link on the Translations screen and on the chapter notice (above). |
| No sublicensing or transfer | The keys and packages stay with us. |
| An annual report within 60 days after each October 1 | `.github/workflows/lockman-report.yml`; see "The annual report" below. |
| Agreement ends automatically if the NASB is unavailable for more than three consecutive months, or on 30 days' notice from Lockman | Don't pull it from a release without a replacement build ready. |
| Other websites need prior written notice to Lockman | Only https://wemiller.com/apps/scripture-alone/ is covered today. |

## Getting the text in, safely

1. **Lockman sends the files** in USFM (preferred), USX or OSIS. These are the industry-standard
   formats Bible publishers already produce for the Digital Bible Library, Paratext and CrossWire.
   Keep the files on your own machine, in an encrypted location, and nowhere else.
2. **Build a store outside the repository.** Run `Tools/build_bibles.py` against the USFM with the
   output pointed outside the working tree. That option is added when the files arrive.
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
4. **Ship the package without publishing it.** Keep the `.sabible` out of this public repository.
   Hold it in a private repository or private storage that the Xcode Cloud and Android builds fetch
   with a token, then copy into `Resources/Packages/` at build time.
5. **Make it the default.** Add the identifier to `SealedTranslations.identifiers`, derive its key
   from `ContentKeySeed.data` rather than the published seed, and point `ReaderModel.defaultTranslation`
   at it. Keep the ASV bundled as the fallback, and do the same on Android.

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
