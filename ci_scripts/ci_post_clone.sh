#!/bin/zsh
# Xcode Cloud post-clone. Generates the project, and writes the build-time content-key seed from
# the SA_CONTENT_KEY_SEED environment variable (set as a secret in the Xcode Cloud workflow).
#
# The seed is never in the repository. A build without the variable set produces a file with no
# seed, and the app simply has no key for a licensed translation, so it ships none and opens to the
# ASV (which is sealed with a published seed of its own; see SealedTranslations.swift).
set -euo pipefail
cd "$CI_PRIMARY_REPOSITORY_PATH"

# ---- nothing-to-build guard ------------------------------------------------
# If EVERY file changed in this commit is outside the iOS app — the Android port under android/,
# docs, or Markdown — there is nothing to build, and letting the run continue would upload an
# identical iOS binary to TestFlight. That is not free: App Store Connect caps uploads per app per
# day. Runs before `brew install` so a skipped run costs seconds, not minutes.
#
# A change to ScriptureAlone/Resources is NOT skipped even though Android reads those databases
# too: it changes what the iOS app ships.
#
# NOTE: exiting non-zero is the ONLY way to stop an Xcode Cloud run early. A run that ends with
# the banner below is a DELIBERATE SKIP, not a broken build.
# Xcode Cloud clones shallowly, so HEAD~1 usually isn't there and the guard silently stood aside —
# a Markdown-only commit built and uploaded build 40. Fetch the one parent commit it needs.
if ! git rev-parse -q --verify HEAD~1 >/dev/null 2>&1; then
  git fetch -q --deepen=1 origin 2>/dev/null || true
fi
if git rev-parse -q --verify HEAD~1 >/dev/null 2>&1; then
  CHANGED="$(git diff --name-only HEAD~1 HEAD || true)"
  RELEVANT="$(printf '%s\n' "$CHANGED" | grep -vE '^android/|(^|/)(docs|\.claude)/|\.md$' || true)"
  if [ -n "$CHANGED" ] && [ -z "$RELEVANT" ]; then
    echo "=============================================================="
    echo "  BUILD SKIPPED — this is NOT a failure."
    echo "  Nothing in this commit affects the iOS app:"
    printf '%s\n' "$CHANGED" | sed 's/^/    /'
    echo "  Stopping now instead of uploading an identical binary."
    echo "=============================================================="
    exit 1
  fi
  echo "nothing-to-build guard: iOS-relevant changes present, building"
else
  echo "nothing-to-build guard: no parent commit reachable, building (cannot tell what changed)"
fi
# ---- end guard -------------------------------------------------------------

# ---- licensed translations ---------------------------------------------------
# The NASB 2020 and NASB 1995 (docs/lockman/README.md) ship sealed, but their packages are never in this public
# repository. They live in a private repository (SA_LICENSED_REPO, e.g. "blaineam/scripture-alone-
# licensed", read with the fine-grained token SA_LICENSED_TOKEN: Contents read-only, that repository
# only), and are copied into Resources/Packages before the project is generated so they are bundled.
# Without those variables the build simply ships no licensed translation and opens to the ASV.
if [ -n "${SA_LICENSED_REPO:-}" ] && [ -n "${SA_LICENSED_TOKEN:-}" ]; then
  LICENSED_TMP="$(mktemp -d)"
  git -c credential.helper= clone -q --depth 1 \
    "https://x-access-token:${SA_LICENSED_TOKEN}@github.com/${SA_LICENSED_REPO}.git" "$LICENSED_TMP"
  for id in NASB2020 NASB1995; do
    if [ -f "$LICENSED_TMP/$id.sabible" ] && [ -f "$LICENSED_TMP/$id-signing.pub" ]; then
      cp "$LICENSED_TMP/$id.sabible" "$LICENSED_TMP/$id-signing.pub" ScriptureAlone/Resources/Packages/
      # Which package set this build carries: a package opens only with the key it was signed with,
      # so the two fingerprints together say exactly what shipped.
      echo "licensed translation: $id bundled (package $(shasum -a 256 "$LICENSED_TMP/$id.sabible" | cut -c1-12), key $(shasum -a 256 "$LICENSED_TMP/$id-signing.pub" | cut -c1-12))"
    else
      echo "licensed translation: $id not in $SA_LICENSED_REPO, not bundled"
    fi
  done
  rm -rf "$LICENSED_TMP"
  # A licensed package with no seed to open it would ship as a default nobody can read.
  if ls ScriptureAlone/Resources/Packages/NASB*.sabible >/dev/null 2>&1 && [ -z "${SA_CONTENT_KEY_SEED:-}" ]; then
    echo "error: a licensed package is bundled but SA_CONTENT_KEY_SEED is not set" >&2
    exit 1
  fi
else
  echo "licensed translation: SA_LICENSED_REPO / SA_LICENSED_TOKEN not set, none bundled"
fi
# ---- end licensed translations -----------------------------------------------

brew install xcodegen
xcodegen generate

mkdir -p ScriptureAlone/Generated
SEED_FILE=ScriptureAlone/Generated/ContentKeySeed.swift

if [ -n "${SA_CONTENT_KEY_SEED:-}" ]; then
  # Stored as bytes XORed with a fixed pad. This is obfuscation, not protection: anyone who
  # disassembles the binary recovers it, and the white paper says so. It exists only so the seed
  # is not a plain string that `strings` prints for free.
  python3 - "$SA_CONTENT_KEY_SEED" > "$SEED_FILE" <<'PY'
import sys, hashlib
seed = bytes.fromhex(sys.argv[1]) if all(c in "0123456789abcdefABCDEF" for c in sys.argv[1]) else sys.argv[1].encode()
pad = hashlib.sha256(b"scripture-alone-seed-pad-v1").digest()
masked = bytes(b ^ pad[i % len(pad)] for i, b in enumerate(seed))
print("// Generated by ci_scripts/ci_post_clone.sh. Not in source control.")
print("import CryptoKit\nimport Foundation\n")
print("enum ContentKeySeed {")
print("    static let masked: [UInt8] = [" + ", ".join(f"0x{b:02x}" for b in masked) + "]")
print("""
    /// The seed, unmasked. Nil when the build carried none.
    static var data: Data? {
        guard !masked.isEmpty else { return nil }
        let pad = Array(SHA256.hash(data: Data("scripture-alone-seed-pad-v1".utf8)))
        return Data(masked.enumerated().map { $0.element ^ pad[$0.offset % pad.count] })
    }
}""")
PY
  echo "content key seed: written (${#SA_CONTENT_KEY_SEED} characters of input)"
else
  cat > "$SEED_FILE" <<'EMPTY'
// Generated by ci_scripts/ci_post_clone.sh. Not in source control.
import Foundation

enum ContentKeySeed {
    static let masked: [UInt8] = []
    /// No seed in this build: every bundled translation is public domain and unencrypted.
    static var data: Data? { nil }
}
EMPTY
  echo "content key seed: none set, wrote an empty seed"
fi
