#!/bin/zsh
# Scripture Alone App Store screenshots: capture → Monkr frame (a caption per scene) → ASC.
#
# Like the shared ../../_shared/update-screenshots.sh (Tilebreak, Kern), with one difference: the
# shared script frames a device's whole set through one .monkr, so every frame carries the same
# text. This listing has a caption per screen, so framing is Tools/frame_screenshots.mjs (same
# Monkr CLI, same designs, text from docs/appstore-screenshots/captions.json). Upload is the
# shared toolkit's asc-screenshots.mjs, called exactly as the shared script calls it.
#
#   ./scripts/update-screenshots.sh --no-upload                # capture + frame for review
#   ./scripts/update-screenshots.sh --skip-capture --no-upload # re-frame existing captures
#   ./scripts/update-screenshots.sh --dry-run                  # ASC flow without writes
#   ./scripts/update-screenshots.sh                            # replace the sets in ASC
#
# Flags: --no-upload --skip-capture --dry-run --allow-in-review --device <rawKey>
# Each screenshots/<rawKey>/framed/ directory uploads as that display type's COMPLETE set (the
# uploader deletes what is there first). Uploads need an editable (PREPARE_FOR_SUBMISSION)
# version; without one the uploader skips and leaves the frames local.
set -euo pipefail

ROOT="${0:A:h:h}"
SHARED="$(cd "$ROOT/../_shared" && pwd)"
cd "$ROOT"
source ./.local-screenshots.conf

NO_UPLOAD=0 SKIP_CAPTURE=0 DRY_RUN=0 ALLOW_IN_REVIEW=0 ONLY_DEVICE=""
while (( $# )); do
    case "$1" in
        --no-upload) NO_UPLOAD=1 ;;
        --skip-capture) SKIP_CAPTURE=1 ;;
        --dry-run) DRY_RUN=1 ;;
        --allow-in-review) ALLOW_IN_REVIEW=1 ;;
        --device) shift; ONLY_DEVICE="$1" ;;
        *) print -u2 "unknown flag: $1"; exit 2 ;;
    esac
    shift
done

if [[ "$SKIP_CAPTURE" != "1" ]]; then
    case "$ONLY_DEVICE" in
        iphone-6.9) eval "$CAPTURE_CMD iphone" ;;
        ipad-13) eval "$CAPTURE_CMD ipad" ;;
        watch-46mm) eval "$CAPTURE_CMD watch" ;;
        *) eval "$CAPTURE_CMD all" ;;
    esac
fi

if [[ -n "$ONLY_DEVICE" ]]; then
    node Tools/frame_screenshots.mjs --device "$ONLY_DEVICE"
else
    node Tools/frame_screenshots.mjs
fi

if [[ "$NO_UPLOAD" = "1" ]]; then
    print -u2 "review mode — framed images in $SCREENSHOTS_DIR/*/framed (no upload)"
    exit 0
fi

# ASC auth, as the shared pipeline does it.
if [[ -z "${ASC_API_KEY_PATH:-}" ]]; then
    for DIR in "$HOME/.appstoreconnect/private_keys" "$HOME/private_keys"; do
        CAND=$(ls "$DIR"/AuthKey_*.p8 2>/dev/null | head -1 || true)
        [[ -n "$CAND" ]] && ASC_API_KEY_PATH="$CAND" && break
    done
fi
[[ -z "${ASC_API_KEY_PATH:-}" ]] && { print -u2 "no ASC API key (.p8); use --no-upload"; exit 1; }
if [[ -z "${ASC_API_KEY_ID:-}" ]]; then
    FN="$(basename "$ASC_API_KEY_PATH")"; ASC_API_KEY_ID="${FN#AuthKey_}"; ASC_API_KEY_ID="${ASC_API_KEY_ID%.p8}"
fi
[[ -z "${ASC_API_ISSUER_ID:-}" ]] && { print -u2 "ASC_API_ISSUER_ID env var required for upload"; exit 1; }
export ASC_API_KEY_ID ASC_API_ISSUER_ID

for entry in "${DEVICES[@]}"; do
    key="${entry%%|*}"; rest="${entry#*|}"; dtype="${rest#*|}"
    [[ -n "$ONLY_DEVICE" && "$key" != "$ONLY_DEVICE" ]] && continue
    args=(--bundle-id "$BUNDLE_ID" --display-type "$dtype" --platform IOS --p8 "$ASC_API_KEY_PATH"
          --images "$SCREENSHOTS_DIR/$key/framed")
    [[ "$DRY_RUN" = "1" ]] && args+=(--dry-run)
    [[ "$ALLOW_IN_REVIEW" = "1" ]] && args+=(--allow-in-review)
    print -u2 "ASC replace: $key ($dtype)"
    node "$SHARED/screenshots/asc-screenshots.mjs" "${args[@]}"
done
