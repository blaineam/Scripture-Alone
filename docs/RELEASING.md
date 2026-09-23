# Releasing Scripture Alone

The **git tag decides** where a build goes — the same policy as Haven. Xcode Cloud (workflow
"Main") makes every Apple build from pushes to `main`; GitHub Actions never builds Apple anything.
It only talks to App Store Connect and Google Play.

| Tag | Google Play | Apple |
|---|---|---|
| `vX.Y.Z-rc.N` | phone AAB → `internal` + closed `alpha` ("Alpha" in the Console); Wear OS AAB → `wear:qa` (Wear internal) + `wear:Wear OS closed testing` | TestFlight only. Xcode Cloud already built the push; `apple-store.yml` waits for that build to be VALID. No App Store version, no review. |
| `vX.Y.Z` | phone AAB → `production`; Wear OS AAB → `wear:production` | `apple-store.yml` submits the Xcode Cloud build of that exact commit for review, with every pending asset pack on the same submission. Then it moves `main` to the next patch version. |

- `-rc.N` is the only pre-release suffix. **The rc guard beats everything**: an rc goes to testers
  whatever `play_track` input or `PLAY_TRACK` variable says.
- Promoting an rc means tagging the **same commit** again without `-rc.N`. Play gets a rebuilt
  bundle with a new versionCode; the App Store gets the Xcode Cloud build testers already used.
- Nothing is uploaded or submitted until the secrets in [One-time setup](#one-time-setup) exist.
  Until then every workflow **skips green**, with a notice saying which secret is missing. The
  Android lane still builds both bundles (unsigned) as a compile check.

## The workflows

| File | Runs on | Does |
|---|---|---|
| `.github/workflows/cut-release.yml` | Actions ▸ cut-release ▸ Run workflow (on `main`) | Checks the notes, stamps `MARKETING_VERSION` into `project.yml` + `ScriptureAlone.xcodeproj/project.pbxproj`, pushes the tag, and dispatches the other two at the tag ref. |
| `.github/workflows/android.yml` | `v*` tags, manual dispatch | Resolves the tracks, asks Play for the next versionCodes, builds `:app:bundleRelease` + `:wear:bundleRelease`, checks 16 KB page alignment, publishes both bundles in **one** Play edit. |
| `.github/workflows/apple-store.yml` | `v*` tags, manual dispatch | `scripts/asc-autosubmit.mjs`: finds the build, sets What's New, attaches the build + asset packs, submits, then bumps `main`. |

Scripts: `scripts/asc-autosubmit.mjs` (App Store Connect), `scripts/play-publish.mjs` (Play
Developer API), `scripts/check-16kb-pages.py` (native-library alignment in an AAB).

## Cutting a release candidate

1. Make sure `MARKETING_VERSION` in `project.yml` is the version you're testing. cut-release
   stamps it if it isn't.
2. Actions ▸ **cut-release** ▸ Run workflow on `main` ▸ version `1.1.0`, rc `1` → tag `v1.1.0-rc.1`.
3. Android: the phone bundle lands on internal + Alpha, and the Wear bundle on the Wear internal
   and closed tracks, all in one edit. Apple: the lane goes green once the build is VALID on
   TestFlight.

The release notes don't have to start with the version for an rc. A warning is printed if the
English ones don't.

## Cutting a production release

1. **Write the notes, all eighteen of them.** cut-release refuses until both English sources
   start with the version:
   - App Store: `## whats_new` (and `## promotional_text`, which a new App Store version does not
     inherit) in `appstore-metadata.md` plus the eight `appstore-metadata.<locale>.md` (`zh-Hans`,
     `ja`, `de-DE`, `fr-FR`, `es-ES`, `ko`, `pt-BR`, `it`).
   - Play: `## release_notes` in `android/play-metadata.md` plus the eight
     `android/play-metadata.<locale>.md` (`zh-CN`, `ja-JP`, `de-DE`, `fr-FR`, `es-ES`, `ko-KR`,
     `pt-BR`, `it-IT`). **500 characters maximum** per locale, counted as characters (never
     `wc -m`, which counts bytes).
   - A locale whose notes don't start with the version falls back to the English notes, with a
     warning.
   - **No price words** anywhere (App Review 2.3.7): free, price, sale, discount, "$4.99", 無料,
     免费, 무료, gratis, kostenlos, gratuit, gratuito… cut-release, the Play step and the App
     Store script all refuse them. A hyphenated compound such as "DRM-free" is allowed.
2. Push the notes. They're `.md` files, so the nothing-to-build guard makes Xcode Cloud skip that
   push. Ideally let Xcode Cloud finish building `main`'s tip before cutting.
3. Actions ▸ **cut-release** ▸ version `1.1.0`, rc empty → tag `v1.1.0`.
4. Android → production (phone + Wear). Apple → submitted for review with its asset packs. Then
   `main` becomes `1.1.1` ("Back to development"). That commit lands only after the submission
   succeeds; if the Apple lane fails, `main` stays at the released version until it's re-run.

### Apple: which build ships

`apple-store.yml` looks for the Xcode Cloud run whose source commit is the tagged commit. It
waits **10 minutes** for the push-triggered run to be listed. If none shows up it starts one on
the tag, and when Xcode Cloud refuses a tag start (409 "not associated with the workflow", or a
500) it starts one on branch `main`.

**The nothing-to-build guard** (`ci_scripts/ci_post_clone.sh`) deliberately exits 1 for a commit
that only touches `android/`, `docs/`, `.claude/` or `*.md`. So a **failed** Xcode Cloud run can
be a skip, and the script never takes one as the build. Instead it accepts the run of an
**iOS-equivalent** commit, meaning every path that differs between the two is one the guard
ignores (or `.github/` / `scripts/`, which the iOS build never reads). The commit pin holds: a run that built different iOS files is refused.

If no equivalent commit has a build (for example, the tag sits on a docs commit and the iOS
build before it was cancelled), the lane fails and tells you to push an **empty commit** to
`main`. An empty diff passes the guard and is iOS-equivalent to the tag:

```sh
git commit --allow-empty -m "Build 1.1.0 for the App Store" && git push
# wait for Xcode Cloud, then re-run the apple-store lane
gh workflow run apple-store.yml -R blaineam/Scripture-Alone --ref v1.1.0
```

### Apple: asset packs

Every non-archived Background Assets pack whose newest version is COMPLETE, and whose App Store
release is `PREPARE_FOR_SUBMISSION` / `READY_FOR_REVIEW` / rejected, rides on the same
reviewSubmission as the version. It's added as a `backgroundAssetVersion` item. The rules:

- **At most 10 packs per submission.** More fails the run before anything changes.
- A pack still processing stops the submit, because the app would ship without its data.
- Packs are **never archived**. Archiving is permanent.
- **Superseding.** A version queued for review that is *older* than the tag gets cancelled and
  renamed, as in Haven. Its queued packs then have to ride again, so they count toward the 10
  *before* the cancel. Example: with 1.0.0 in review holding 5 packs, tagging 1.1.0 would need
  5 + 8 = 13. The lane stops without cancelling anything (verified by a dry run on 2026-09-23).
  Wait for 1.0.0 to be approved, then tag.

### Play: versionCodes and tracks

- The phone is always below 1,000,000 and the Wear OS app is always at 1,000,000 or above. The
  plan step reads every code Play has ever seen (uploaded bundles plus track releases), and each
  build gets `max + 1`, passed as `-PsaVersionCode` / `-PsaWearVersionCode`. The defaults in
  `android/app` and `android/wear/build.gradle.kts` are only for local builds. Codes can never be
  reused.
- Both bundles and every track assignment go into **one edit**: all or nothing. Re-running is
  safe, because an uncommitted edit consumes no code.
- Play refuses a Wear bundle on a phone track. Wear OS tracks are form-factor tracks with a
  `wear:` prefix ([Play Developer API: tracks](https://developers.google.com/android-publisher/tracks)):
  - `wear:production` for production.
  - `wear:beta` for open testing.
  - `wear:qa` for internal testing.
  - For a custom closed track, `wear:<name as shown in the Console>`.

  The plan step fails in seconds, listing every track Play knows, if a custom track id is wrong.
- Every 64-bit `.so` in both bundles must be 16 KB page aligned (`scripts/check-16kb-pages.py`).
  32-bit libraries are reported, not failed. ML Kit's armeabi-v7a/x86 OCR library is 4 KB aligned
  today; 16 KB devices are 64-bit only.
- A personal Play account needs 12+ testers × 14 days of closed testing before production opens.
  Until then the production edit is refused and the run fails, saying so.

### Manual dispatches

- `android.yml` has four inputs: `play_track` (auto / internal / alpha / production), a
  `play_rollout` fraction for a staged production rollout, `build_only` (compile check, never
  talks to Play) and `plan_only` (read-only Play check, no build). On a branch, `auto` means
  internal. A plain dispatch with none of these set publishes to internal.
- `apple-store.yml` has three inputs: `version`, `commit`, and `dry_run`. A dry run is read-only
  (GETs only, enforced in the script). It finds the run and the builds, then checks the notes and
  plans the packs.
- Locally, with the same credentials rocket uses:
  `node scripts/asc-autosubmit.mjs --version 1.1.0 --commit <sha> --dry-run`.

### Moving a tag

Moving a tag re-fires every `v*` workflow. Cancel any lane that already shipped: Play uploads and
ASC submissions don't tolerate being repeated.

## One-time setup

The secrets are **not set yet** (`gh secret list -R blaineam/Scripture-Alone` is empty). Add them
yourself; nothing in this repository reads or stores their values. Paths below are placeholders.

### Google Play (android.yml)

| Secret | What it is | Where it comes from |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | The Play **upload** keystore, base64 | The file `SA_UPLOAD_STORE_FILE` points at in `~/.gradle/gradle.properties` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password | `SA_UPLOAD_STORE_PASSWORD` in the same file |
| `ANDROID_KEY_ALIAS` | Key alias | `SA_UPLOAD_KEY_ALIAS` |
| `ANDROID_KEY_PASSWORD` | Key password | `SA_UPLOAD_KEY_PASSWORD` |
| `PLAY_SERVICE_ACCOUNT_JSON` | Play Developer API service-account JSON key | The **same service account Haven uses**. It also needs access to Scripture Alone: Play Console ▸ Users and permissions ▸ the service account's email ▸ App permissions ▸ add *Scripture Alone*, with "Release to production, exclude devices, and use Play App Signing", "Release apps to testing tracks" and "Manage testing tracks and edit tester lists". |

In CI the keystore secrets become the `ORG_GRADLE_PROJECT_SA_UPLOAD_*` properties, the same names
the local build reads, so local signing through `~/.gradle/gradle.properties` is unchanged.

```sh
R=blaineam/Scripture-Alone
base64 -i /path/to/scripture-alone-upload.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R $R
gh secret set ANDROID_KEYSTORE_PASSWORD -R $R    # prompts; paste SA_UPLOAD_STORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS -R $R            # prompts; paste SA_UPLOAD_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD -R $R         # prompts; paste SA_UPLOAD_KEY_PASSWORD
gh secret set PLAY_SERVICE_ACCOUNT_JSON -R $R < /path/to/play-service-account.json
```

### App Store Connect (apple-store.yml)

| Secret | What it is | Where it comes from |
|---|---|---|
| `ASC_API_KEY_ID` | API key id | `ascKeyId` in `~/.rocket/config.json` (the id in `AuthKey_<id>.p8`) |
| `ASC_API_ISSUER_ID` | Issuer id | `ascIssuerId` in `~/.rocket/config.json` |
| `ASC_API_KEY_P8` | The `.p8` private key, raw PEM or base64 | `~/.appstoreconnect/private_keys/AuthKey_<id>.p8` |

The key needs the **App Manager** role (Users and Access ▸ Integrations ▸ App Store Connect API).
Xcode Cloud's own settings are untouched: workflow "Main"
(`4977FB75-C998-4E7B-B3A5-34F85CDF19B2`), app id `6813729762`.

```sh
R=blaineam/Scripture-Alone
gh secret set ASC_API_KEY_ID -R $R --body "<key id>"
gh secret set ASC_API_ISSUER_ID -R $R --body "<issuer id>"
gh secret set ASC_API_KEY_P8 -R $R < /path/to/AuthKey_<key id>.p8
```

### Optional repository variables

All of these are optional (`gh variable set NAME -R blaineam/Scripture-Alone --body "…"`):

| Variable | Default | Effect |
|---|---|---|
| `PLAY_WEAR_RC_TRACKS` | `wear:qa,wear:Wear OS closed testing` | Wear tracks for an rc (internal first, closed second). If the plan step says the closed track id is wrong, set this to the `wear:` name it lists. |
| `PLAY_WEAR_PROD_TRACK` | `wear:production` | Wear track for a plain tag. |
| `PLAY_WEAR` | (on) | `false` publishes the phone bundle only. |
| `PLAY_TRACK` | (tag decides) | Pins every **plain** tag's phone track. rc tags ignore it. |
| `PLAY_USER_FRACTION` | (full) | Staged production rollout, e.g. `0.2`. |
| `PLAY_RELEASE_STATUS` | `completed` / `inProgress` | `draft` while Play still treats the app as a draft. |
| `APPLE_STORE_SUBMIT` | (submit) | `false` sets the notes and attaches the build but doesn't press Submit. Asset packs are added at submit time, so none are attached. |

### Before the first automated production release

- [ ] All the secrets above are set, and the service account has access to Scripture Alone.
- [ ] Actions ▸ android ▸ Run workflow with `plan_only` on passes. It's read-only: it lists
      Play's tracks and next versionCodes and confirms the phone and Wear track ids, including
      the Wear OS closed-testing one.
- [ ] Actions ▸ apple-store ▸ Run workflow with `dry_run` on and version `1.1.0` works.
