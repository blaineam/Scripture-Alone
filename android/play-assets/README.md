# Google Play listing assets

Everything here except this README is generated and git-ignored (`android/.gitignore`). Rebuild it
with the tools in `android/tools/`.

## Layout

```
play-assets/
  icon-512.png, feature-graphic.png      play_graphics.py graphics (English artwork, all listings)
  <Play locale>/                         en-US de-DE es-ES fr-FR it-IT ja-JP ko-KR pt-BR zh-CN
    phone/        8 × 1080×2160          Phone screenshots
    tablet-7in/   8 × 1200×1920          7-inch tablet screenshots
    tablet-10in/  8 × 2560×1600          10-inch tablet screenshots
    wear/         6 × 384×384            Wear OS screenshots
  upload/                                bundles copied for a manual Console upload (not screenshots)
  _stale-2026-09-21/                     the previous English-only set, kept for reference only
```

The Play locale folders match `android/play-metadata.<locale>.md`. Files are numbered in listing
order, strongest first; upload each folder as that form factor's complete set for that language,
in file-name order. Every image is a raw screen (no frame, no caption) as the listing has always
been, RGB PNG, within Play's limits (320–3840 px, long side at most twice the short; Wear square).

### What each set shows

| # | Phone | 7-inch | 10-inch (Study beside the page) | Wear OS |
|---|---|---|---|---|
| 1 | Reader: John 10, highlights, a verse selected | Reader | Study → References beside John 10 | Home: Verse of the Day |
| 2 | Topics (Go To → See All) | Topics | Topics, with Study → Context | John 10:11–15 |
| 3 | Topic: Anxiety & Worry | Study → Context: Overview + still map | Notes → Highlights | Highlights |
| 4 | Study → Context: Overview + still map | Notes → Highlights | Study → Context: Overview | Favorites |
| 5 | Notes → Highlights (All Books / This Book / This Chapter) | Topic: Anxiety & Worry | Topic: Anxiety & Worry | Notes |
| 6 | Notes | Sermon note with its slide photo | Study → Context → Map: Paul's first journey (Acts 13) | A note |
| 7 | Sermon note with its slide photo | Study → References | Notes, with Study → Context | |
| 8 | Share Image (Dawn) | Share Image | Share Image | |

Each locale shows the app in its own language, reading its own Bible — BSB (en-US), LUT1912,
RVR1909, LSG, RIV1927, 文語訳 (BUNGO), 개역한글 (KRV), BLIVRE, 和合本 (CUVS) — with the invented
demo library of `play_seed.py` written in that language (four notes, highlights, favorites; never
a real reader's data) and that language's sermon slide (`docs/appstore-screenshots/sample-slide.*.jpg`,
read only). Study's Commentary tab is English-only content, so it is absent outside en-US.

## Rebuilding

One emulator at a time; always pass the serial (`-s`) of the AVD you booted.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
(cd android && ./gradlew :app:assembleDebug :wear:assembleDebug)   # debug APKs carry every Bible pack
W=$TMPDIR/sa-play; mkdir -p $W/editions

# Phone: haven_phone (Pixel 8, google_apis → rootable)
emulator -avd haven_phone -no-window -no-audio -gpu swiftshader_indirect &
adb -s emulator-5554 root && adb -s emulator-5554 shell wm size 1080x2160
adb -s emulator-5554 install -r -g android/app/build/outputs/apk/debug/app-debug.apk
TMPDIR=$W python3 android/tools/play_capture.py emulator-5554 phone android/play-assets
#   (also leaves $W/snapshot-<locale>.json, the Wear set's library)

# Tablets: haven_tablet (Pixel Tablet, 2560×1600, 320 dpi), after stopping the phone
emulator -avd haven_tablet -no-window -no-audio -gpu swiftshader_indirect &
adb -s emulator-5554 root && adb -s emulator-5554 install -r -g android/app/build/outputs/apk/debug/app-debug.apk
TMPDIR=$W python3 android/tools/play_capture.py emulator-5554 tablet-10in android/play-assets
adb -s emulator-5554 shell wm size 1200x1920
TMPDIR=$W python3 android/tools/play_capture.py emulator-5554 tablet-7in android/play-assets
adb -s emulator-5554 shell wm size reset

# Wear OS: scripture_wear (large round, 454×454) — after the phone run
for id in LUT1912 RVR1909 LSG RIV1927 BUNGO KRV BLIVRE CUVS; do
  python3 Tools/build_companion_data.py --watch-edition $id $W/editions/$id-Watch.sqlite; done
emulator -avd scripture_wear -no-window -no-audio -gpu swiftshader_indirect &
adb -s emulator-5554 install -r android/wear/build/outputs/apk/debug/wear-debug.apk
TMPDIR=$W python3 android/tools/play_capture.py emulator-5554 wear android/play-assets
```

Add locales after the profile to redo only some (`… phone android/play-assets fr-FR ja-JP`), and
`SCENES="sermon-slide share-image"` to redo only some scenes (they keep their numbers). Then look
at every set: the script taps coordinates measured on the English UI and retries a scene that
loses focus, but a slow emulator can still capture a half-open sheet.

**Traps seen producing this set.** A windowed emulator froze outright (qemu at 0% CPU) while the
host was loaded with iOS simulators: run it `-no-window`. `pm clear` between locales made the
debug app redo its first-launch work and brought the system server down; the script instead
keeps the app's data and resets its settings and library. System UI demo mode must be `exit`ed
before `enter`, or the status bar can show two Wi-Fi icons. The Wear image can't be rooted, so
the watch gets its library and its locale Bible through `run-as` — exactly where the Data Layer
would have left them — rather than from a paired phone; and it has no demo mode, so the script sets
the watch's clock to 9:41 (`cmd alarm set-time`, automatic time off) and turns automatic time back on
after each locale. The watch's clock follows the locale's 12/24-hour habit (9:41 or 09:41).

## Uploading

`scripts/play-listing.mjs` uploads this layout as it is (`<root>/<locale>/<type>/`, the short type
names, file-name order; see its header and docs/RELEASING.md › Play store listing). Each type
folder replaces that type's whole set for that language; `_stale-*/` and `upload/` are not read,
and the root `icon-512.png` / `feature-graphic.png` are not either (en-US has its own folder), so
the icon and feature graphic on Play stay as they are.

```sh
# check the files (no credentials, Play not contacted)
node scripts/play-listing.mjs --validate-only --images android/play-assets
# from this machine, with the service-account key: dry run, then for real
node scripts/play-listing.mjs --key-file <key.json> --images android/play-assets --dry-run
node scripts/play-listing.mjs --key-file <key.json> --images android/play-assets
```

The play-listing.yml workflow runs the same script in CI, but it reads the images from the
checked-out repository, where this folder is git-ignored: `-f images=true` there finds nothing
unless the sets are committed (or `PLAY_LISTING_IMAGES` points somewhere that has them). By hand
instead: Play Console → Grow users → Store presence → Main store listing (en-US), then each
translation, the Phone, 7-inch tablet, 10-inch tablet and Wear OS screenshot sections.

## Known

- The German set's John 10 comes from LUT1912 as bundled, whose John 10:10 is split in two, so
  "verse 11" there reads "Ich bin gekommen, daß sie das Leben…" and the good-shepherd sentence is
  verse 12. The phone/tablet reader shots highlight what the data calls 10:11 and 10:14; they are
  right again once the Bible is fixed and the German set re-shot (`… phone android/play-assets de-DE`, etc.).
- pt-BR's Share Image title crowds the Done button ("Concluído"): the app's own layout, as shipped.
