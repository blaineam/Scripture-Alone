#!/usr/bin/env bash
#
# Captures Scripture Alone on the iPhone Duo — the COVER panel (1398x2034) and the INNER panel
# (2853x2007 landscape when unfolded) — for the layout audit and the APP_IPHONE_DUO store slot.
# Same scenes and launch arguments as Tools/capture_screenshots.sh (every scene is a DEBUG launch
# path over the demo library). English is the NASB when the local build has it (never viewed by an AI), else the ASV;
# set ENGLISH_BIBLE=BSB for a reviewable English set. Other languages use their own Bible.
#
# THE NASB TEXT MUST NEVER BE SHOWN TO AN AI SYSTEM (docs/lockman/README.md). This script always
# captures the BSB, so these frames are safe to review.
#
# PRECONDITION: two booted iPhone Duo simulators, one folded (cover live) and one unfolded (inner
# live). A fresh boot always comes up folded and the fold cannot be scripted, so unfold one in
# Xcode's device hub once; this script never boots, reboots or shuts a Duo sim down (that would
# drop the posture). See _shared/screenshots/capture-lib.sh (cap_duo_*).
#
# Output: screenshots/iphone-duo-cover/ and screenshots/iphone-duo-inner/.
# Usage: ./Tools/capture_duo.sh
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
# shellcheck disable=SC1091
source "$PROJECT_ROOT/../_shared/screenshots/capture-lib.sh"

BUNDLE_ID="com.blainemiller.ScriptureAlone"
export CAP_APP_NAME="Scripture Alone"
export CAP_STATUSBAR_STRIP=0.032
OUT_DIR="$PROJECT_ROOT/screenshots"
SLIDE="$PROJECT_ROOT/docs/appstore-screenshots/sample-slide.jpg"

SCENES=(
    "01-reader|reader|-reader.theme light"
    "02-topics|topics|-reader.theme light"
    "03-study|study|-reader.theme light"
    "04-maps|maps|-reader.theme light"
    "05-sermon-notes|sermon-notes|-reader.theme light"
    "06-highlights|highlights|-reader.theme light"
    "07-listen|listen|-reader.theme light"
    "08-themes|themes|-reader.theme black"
    "09-share|share|-reader.theme light -share.template dawn -share.aspect square"
    "10-jump|jump|-reader.theme light"
)

xcodegen generate > /dev/null 2>&1 || true
cap_duo_prepare "ScriptureAlone.xcodeproj" "ScriptureAlone" "$BUNDLE_ID" \
    "$HOME/Library/Developer/Xcode/DerivedData/scripture-alone-shots-duo" none

# Each screen is shown ONCE, opened (inner) or folded (cover) — the plan lives in .local-screenshots.conf
# (DUO_PLAN=(01:inner 02:cover ...)); only the planned panel is captured for each scene.
# shellcheck disable=SC1091
source "$PROJECT_ROOT/.local-screenshots.conf"
plan_posture() { local e; for e in "${DUO_PLAN[@]}"; do [ "${e%%:*}" = "$1" ] && { echo "${e##*:}"; return; }; done; }

# The English set shows what a new install opens to: the NASB 2020, once Tools/prepare_licensed_local.sh has put
# it (and the seed that opens it) into the local build — same rule as capture_screenshots.sh. ENGLISH_BIBLE in
# the environment overrides (ENGLISH_BIBLE=BSB for an English set that is safe to review).
# THE NASB TEXT MUST NEVER BE SHOWN TO AN AI SYSTEM: this script only writes PNGs and prints file names; the
# owner reviews the NASB frames. Never open them with an AI tool.
if [ -n "${ENGLISH_BIBLE:-}" ]; then
    echo "note: English shows the $ENGLISH_BIBLE (ENGLISH_BIBLE override)" >&2
elif [ -f "$PROJECT_ROOT/ScriptureAlone/Resources/Packages/NASB2020.sabible" ] &&
    ! grep -q 'masked: \[UInt8\] = \[\]' "$PROJECT_ROOT/ScriptureAlone/Generated/ContentKeySeed.swift" 2>/dev/null; then
    ENGLISH_BIBLE=NASB2020
else
    ENGLISH_BIBLE=ASV
    echo "note: the NASB 2020 isn't in the local build (./Tools/prepare_licensed_local.sh), so English shows the ASV" >&2
fi
echo "English Bible for this run: $ENGLISH_BIBLE" >&2

locale_bible() {  # the Bible a reader in that locale opens to
    case "$1" in
        zh-Hans) echo CUVS ;; ja) echo BUNGO ;; de-DE) echo LUT1912 ;; fr-FR) echo LSG ;;
        es-ES) echo RVR1909 ;; ko) echo KRV ;; pt-BR) echo BLIVRE ;; it) echo RIV1927 ;; *) echo "$ENGLISH_BIBLE" ;;
    esac
}

# CAP_SKIP_CLEAN=1 keeps the existing frames (e.g. an English-only recapture over the other languages' sets).
for POSTURE in cover inner; do
    [ "${CAP_SKIP_CLEAN:-0}" = 1 ] || rm -rf "$OUT_DIR/iphone-duo-$POSTURE"
    mkdir -p "$OUT_DIR/iphone-duo-$POSTURE"
done

capture_locale() {  # capture_locale <locale or "" for the English base>
    local locale="$1" entry file scene extra nn posture udid dir slide container
    local args=""; [ -n "$locale" ] && args="$(cap_locale_args "$locale")"
    echo "  — ${locale:-en-US}"
    for entry in "${SCENES[@]}"; do
        IFS='|' read -r file scene extra <<<"$entry"
        nn="${file%%-*}"; posture="$(plan_posture "$nn")"
        [ -n "$posture" ] || { echo "  ✗ $file is not in DUO_PLAN" >&2; return 1; }
        udid="$(cap_duo_udid "$posture")"
        dir="$OUT_DIR/iphone-duo-$posture"; [ -n "$locale" ] && dir="$dir/$locale"; mkdir -p "$dir"
        # That locale's sample slide goes where the app's temporary directory is (sermon-notes imports it).
        slide="${SLIDE%.jpg}.$locale.jpg"; [ -n "$locale" ] && [ -f "$slide" ] || slide="$SLIDE"
        container="$(xcrun simctl get_app_container "$udid" "$BUNDLE_ID" data)"
        mkdir -p "$container/tmp"; cp "$slide" "$container/tmp/sample-slide.jpg"
        cap_terminate_foreign "$udid" "$BUNDLE_ID"
        # shellcheck disable=SC2086
        CAP_EXTRA_LAUNCH_ARGS="-inMemoryStore -seedDemoLibrary $args -translation $(locale_bible "$locale") $extra" \
            cap_launch "$udid" "$BUNDLE_ID" "$scene" screenshotScene
        local settle=6
        case "$scene" in listen|sermon-notes|maps|topics) settle=9 ;; esac
        cap_duo_shot "$posture" "$dir/$file.png" "$settle"
    done
}

capture_locale ""
for LOCALE in $(cap_locales); do capture_locale "$LOCALE"; done
for POSTURE in cover inner; do xcrun simctl terminate "$(cap_duo_udid "$POSTURE")" "$BUNDLE_ID" >/dev/null 2>&1 || true; done
echo "✓ Duo captures: $OUT_DIR/iphone-duo-{cover,inner}"
