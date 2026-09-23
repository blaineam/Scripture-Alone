#!/usr/bin/env bash
#
# Captures App Store PNGs of Scripture Alone: iPhone 17 Pro Max, iPad Pro 13-inch (M5) and
# Apple Watch Series 11 (46mm) simulators. Every scene is a DEBUG launch path
# (`-screenshotScene <name>`, see ScriptureAlone/App/ScreenshotScene.swift) over the demo library
# (`-inMemoryStore -seedDemoLibrary`, see ScriptureAlone/Shared/DemoLibrary.swift): real app
# screens, invented content, no personal data, no taps. Status bar pinned to 9:41 on iOS
# (watchOS simulators refuse status-bar overrides).
#
# Output: screenshots/<device>/NN-<scene>.png (English, the base set every localization gets) and
# screenshots/<device>/<locale>/NN-<scene>.png for each big-8 locale — every scene in every locale,
# because an uploaded locale directory REPLACES that locale's whole set. Each locale launches with
# its language and region, so the app shows that locale's own Bible (Debug installs it from the
# bundle), book names and interface. SHOT_LOCALES narrows the list ("en-US fr-FR").
# Framing (Monkr, with captions) and upload happen in scripts/update-screenshots.sh.
#
# Usage: ./Tools/capture_screenshots.sh [all|iphone|ipad|watch]
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
# shellcheck disable=SC1091
source "$PROJECT_ROOT/../_shared/screenshots/capture-lib.sh"

BUNDLE_ID="com.blainemiller.ScriptureAlone"
WATCH_BUNDLE="com.blainemiller.ScriptureAlone.watchkitapp"
PROJECT="ScriptureAlone.xcodeproj"
export CAP_APP_NAME="Scripture Alone"
ONLY="${1:-all}"
read -r -a LOCALES <<<"${SHOT_LOCALES:-en-US zh-Hans ja de-DE fr-FR es-ES ko pt-BR it}"

locale_dir() {  # locale_dir <base dir> <asc locale> -> where that locale's captures go
    if [ "$2" = "en-US" ]; then echo "$1"; else echo "$1/$2"; fi
}
OUT_DIR="$PROJECT_ROOT/screenshots"
SLIDE="$PROJECT_ROOT/docs/appstore-screenshots/sample-slide.jpg"

# Dedicated simulators, so this rig never shares a device (or its status-bar breadcrumbs) with
# another app's rig. "<name>|<device type id>|<platform prefix>"
IPHONE="Scripture Alone Shots iPhone 17 Pro Max|com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max|iOS"
IPAD="Scripture Alone Shots iPad Pro 13-inch (M5)|com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M5-12GB|iOS"
WATCH="Scripture Alone Shots Apple Watch Series 11 (46mm)|com.apple.CoreSimulator.SimDeviceType.Apple-Watch-Series-11-46mm|watchOS"

# The store story, in listing order: "<file>|<scene>|<extra launch args>".
SCENES=(
    "01-reader|reader|-reader.theme light"
    "02-jump|jump|-reader.theme light"
    "03-study|study|-reader.theme light"
    "04-maps|maps|-reader.theme light"
    "05-sermon-notes|sermon-notes|-reader.theme light"
    "06-listen|listen|-reader.theme light"
    "07-share|share|-reader.theme light -share.template dawn -share.aspect square"
    "08-themes|themes|-reader.theme black"
)

sim_udid() {  # sim_udid "<name>|<type>|<platform>" -> UDID, created on first use
    local name="${1%%|*}" rest="${1#*|}"; local type="${rest%%|*}" platform="${rest#*|}"
    local udid
    udid=$(xcrun simctl list devices available -j | python3 -c "
import json, sys
for devs in json.load(sys.stdin)['devices'].values():
    for d in devs:
        if d['name'] == sys.argv[1]: print(d['udid']); sys.exit()
" "$name")
    if [ -z "$udid" ]; then
        local runtime
        runtime=$(xcrun simctl list runtimes -j | python3 -c "
import json, sys
rs = [r for r in json.load(sys.stdin)['runtimes'] if r['isAvailable'] and r['name'].startswith(sys.argv[1] + ' ')]
rs.sort(key=lambda r: [int(x) for x in r['version'].split('.')])
print(rs[-1]['identifier'])
" "$platform")
        udid=$(xcrun simctl create "$name" "$type" "$runtime")
    fi
    echo "$udid"
}

build_ios() {  # build_ios UDID DERIVED -> installs the app
    local udid="$1" derived="$2"
    mkdir -p "$derived"
    if ! xcodebuild -project "$PROJECT" -scheme ScriptureAlone -configuration Debug \
        -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$derived" \
        CODE_SIGNING_ALLOWED=NO build >"$derived/build.log" 2>&1; then
        echo "  build failed; tail of $derived/build.log:" >&2
        tail -30 "$derived/build.log" >&2
        return 1
    fi
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl uninstall "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl install "$udid" "$derived/Build/Products/Debug-iphonesimulator/Scripture Alone.app"
}

capture_ios() {  # capture_ios "<sim spec>" <rawKey>
    local spec="$1" key="$2"
    local out="$OUT_DIR/$key" udid derived="/tmp/scripture-alone-shots-dd-$key"
    udid="$(sim_udid "$spec")"
    echo ""
    echo "==> ${spec%%|*} ($udid)"
    cap_boot "$udid"
    xcrun simctl bootstatus "$udid" -b >/dev/null
    # Light appearance, English, and a clean 9:41 status bar with no foreign apps running.
    xcrun simctl ui "$udid" appearance light
    cap_clean_statusbar "$udid"
    if [ "$key" = "ipad-13" ]; then export CAP_STATUSBAR_STRIP=0.032; else export CAP_STATUSBAR_STRIP=0.06; fi

    echo "  Building…"
    build_ios "$udid" "$derived"

    # The sample slide goes where the app's temporary directory is; the sermon-notes scene
    # imports it through SlideCapture.accept(data:), the Photos path.
    local container
    container="$(xcrun simctl get_app_container "$udid" "$BUNDLE_ID" data)"
    mkdir -p "$container/tmp"
    cp "$SLIDE" "$container/tmp/sample-slide.jpg"

    local entry file scene extra locale dir
    for locale in "${LOCALES[@]}"; do
        dir="$(locale_dir "$out" "$locale")"
        mkdir -p "$dir"
        rm -f "$dir"/*.png   # never rm -rf the base: the locale sets live inside it
        echo "  [$locale]"
        for entry in "${SCENES[@]}"; do
            IFS='|' read -r file scene extra <<<"$entry"
            cap_terminate_foreign "$udid" "$BUNDLE_ID"
            # shellcheck disable=SC2086
            CAP_EXTRA_LAUNCH_ARGS="-inMemoryStore -seedDemoLibrary $(cap_locale_args "$locale") $extra" \
                cap_launch "$udid" "$BUNDLE_ID" "$scene" screenshotScene
            # Scenes stage themselves ~0.7-3 s after launch; the listen scene needs a voice going.
            local settle=6
            case "$scene" in listen|sermon-notes|maps) settle=9 ;; esac
            cap_screenshot "$udid" "$dir/$file.png" "$settle"
        done
    done
    xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
    cap_teardown "$udid" "$BUNDLE_ID"
}

capture_watch() {
    local udid derived="/tmp/scripture-alone-shots-dd-watch" out="$OUT_DIR/watch-46mm"
    udid="$(sim_udid "$WATCH")"
    echo ""
    echo "==> ${WATCH%%|*} ($udid)"
    cap_boot "$udid"
    xcrun simctl bootstatus "$udid" -b >/dev/null
    echo "  Building watch app…"
    mkdir -p "$derived"
    if ! xcodebuild -project "$PROJECT" -scheme ScriptureAloneWatch -configuration Debug \
        -destination "platform=watchOS Simulator,id=$udid" -derivedDataPath "$derived" \
        CODE_SIGNING_ALLOWED=NO build >"$derived/build.log" 2>&1; then
        echo "  watch build failed; tail of $derived/build.log:" >&2
        tail -30 "$derived/build.log" >&2
        return 1
    fi
    xcrun simctl terminate "$udid" "$WATCH_BUNDLE" >/dev/null 2>&1 || true
    xcrun simctl uninstall "$udid" "$WATCH_BUNDLE" >/dev/null 2>&1 || true
    xcrun simctl install "$udid" "$derived/Build/Products/Debug-watchsimulator/Scripture Alone.app"

    # "<file>|<-watchRoute value or empty for home>"
    local shots=(
        "01-today|"
        "02-verse|scripturealone://open?ref=43003016-43003017"
        "03-favorites|favorites"
        "04-notes|notes"
    )
    local entry file route tmp locale dir
    for locale in "${LOCALES[@]}"; do
        dir="$(locale_dir "$out" "$locale")"
        mkdir -p "$dir"
        rm -f "$dir"/*.png   # never rm -rf the base: the locale sets live inside it
        echo "  [$locale]"
        # shellcheck disable=SC2206
        local lang=($(cap_locale_args "$locale"))
        for entry in "${shots[@]}"; do
            IFS='|' read -r file route <<<"$entry"
            xcrun simctl terminate "$udid" "$WATCH_BUNDLE" >/dev/null 2>&1 || true
            sleep 0.5
            if [ -n "$route" ]; then
                xcrun simctl launch "$udid" "$WATCH_BUNDLE" -inMemoryStore -seedDemoLibrary "${lang[@]}" -watchRoute "$route" >/dev/null
            else
                xcrun simctl launch "$udid" "$WATCH_BUNDLE" -inMemoryStore -seedDemoLibrary "${lang[@]}" >/dev/null
            fi
            sleep 6
            # A launch that never came up captures the watch FACE and still "succeeds" — the
            # framed set must be eyeballed (see reference_watch_screenshot_traps).
            tmp="${TMPDIR:-/tmp}/.sa-watch-$$-$RANDOM.png"
            xcrun simctl io "$udid" screenshot "$tmp" >/dev/null
            mv -f "$tmp" "$dir/$file.png"
            echo "  → $file.png"
        done
    done
    xcrun simctl terminate "$udid" "$WATCH_BUNDLE" >/dev/null 2>&1 || true
}

echo "==> Generating Xcode project"
xcodegen generate >/dev/null

case "$ONLY" in
    all) capture_ios "$IPHONE" iphone-6.9; capture_ios "$IPAD" ipad-13; capture_watch ;;
    iphone) capture_ios "$IPHONE" iphone-6.9 ;;
    ipad) capture_ios "$IPAD" ipad-13 ;;
    watch) capture_watch ;;
    *) echo "usage: $0 [all|iphone|ipad|watch]" >&2; exit 2 ;;
esac

echo ""
echo "==> Raw captures in $OUT_DIR — eyeball them, then frame with ./scripts/update-screenshots.sh --skip-capture --no-upload"
