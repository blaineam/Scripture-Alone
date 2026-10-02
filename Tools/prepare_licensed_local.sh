#!/usr/bin/env bash
#
# Puts the licensed NASB into a local build, as Xcode Cloud does (ci_scripts/ci_post_clone.sh): copies
# the NASB 2020's package and both editions' signing keys from your private clone into
# ScriptureAlone/Resources/Packages/ (git-ignored), and writes the build's seed
# (ScriptureAlone/Generated/ContentKeySeed.swift, git-ignored) from your login keychain. The seed is
# never printed. Run it yourself — it reads a secret — then build or capture as usual.
#
#   ./Tools/prepare_licensed_local.sh            # set up
#   ./Tools/prepare_licensed_local.sh --remove   # back to a build without the NASB
#
# LICENSED_CLONE overrides where the private clone is (default ~/secure/nasb/licensed).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGES="$ROOT/ScriptureAlone/Resources/Packages"
CLONE="${LICENSED_CLONE:-$HOME/secure/nasb/licensed}"

if [ "${1:-}" = "--remove" ]; then
    rm -f "$PACKAGES"/NASB*
    env -u SA_CONTENT_KEY_SEED python3 "$ROOT/ci_scripts/write_content_key_seed.py"
    echo "Removed the NASB from the local build."
    exit 0
fi

for f in NASB2020.sabible NASB2020-signing.pub NASB1995-signing.pub; do
    [ -f "$CLONE/$f" ] || { echo "error: $CLONE/$f is missing" >&2; exit 1; }
done
cp "$CLONE/NASB2020.sabible" "$CLONE/NASB2020-signing.pub" "$CLONE/NASB1995-signing.pub" "$PACKAGES/"
# The NASB 1995's package is an asset pack, not an app resource; Debug builds copy it into the bundle
# when it is here, so it is offered offline in a local build too.
[ -f "$CLONE/NASB1995.sabible" ] && cp "$CLONE/NASB1995.sabible" "$PACKAGES/"

SA_CONTENT_KEY_SEED="$(security find-generic-password -s SA_CONTENT_KEY_SEED -w)" \
    python3 "$ROOT/ci_scripts/write_content_key_seed.py"
echo "The NASB 2020 is in the local build (package $(shasum -a 256 "$PACKAGES/NASB2020.sabible" | cut -c1-12))."
