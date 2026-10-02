#!/bin/zsh
# Xcode Cloud post-clone. Generates the project, and writes the build-time content-key seed from
# the SA_CONTENT_KEY_SEED environment variable (set as a secret in the Xcode Cloud workflow).
#
# The seed is never in the repository. A build without the variable set produces a file with no
# seed, and the app simply has no key for a licensed translation, so it ships none and opens to the
# ASV (which is sealed with a published seed of its own; see SealedTranslations.swift).
set -euo pipefail
cd "$CI_PRIMARY_REPOSITORY_PATH"

# ---- nothing-to-build: decided by the workflow, not here --------------------
# The Xcode Cloud "Main" workflow's start condition does not start when every changed file is
# docs, screenshots, Monkr, Android or Markdown (DO_NOT_START_IF_ALL_FILES_MATCH), so a commit with
# nothing for the iOS app never reaches this script. A guard here that compared HEAD~1..HEAD did
# nothing for automatic starts and only broke manual ones: "Start Build" builds the branch head,
# and when that head was a screenshots commit the run stopped itself (1.1.1, run #118). Removed.

# ---- licensed translations ---------------------------------------------------
# The NASB 2020 and NASB 1995 (docs/lockman/README.md) ship sealed, but their packages are never in
# this public repository. They live in a private repository (SA_LICENSED_REPO, e.g. "blaineam/
# scripture-alone-licensed", read with the fine-grained token SA_LICENSED_TOKEN: Contents read-only,
# that repository only). Copied into Resources/Packages before the project is generated:
#   NASB2020 — its package and signing key. The package is in the app: it is the default translation.
#   NASB1995 — its signing key only. Its package is an on-demand asset pack (Tools/asset-packs/
#              nasb1995.json, uploaded from private storage), and project.yml keeps it out of the app.
if [ -n "${SA_LICENSED_REPO:-}" ] && [ -n "${SA_LICENSED_TOKEN:-}" ]; then
  LICENSED_TMP="$(mktemp -d)"
  git -c credential.helper= clone -q --depth 1 \
    "https://x-access-token:${SA_LICENSED_TOKEN}@github.com/${SA_LICENSED_REPO}.git" "$LICENSED_TMP"
  # Which package set this build carries: a package opens only with the key it was signed with, so
  # the two fingerprints together say exactly what shipped.
  fingerprint() { shasum -a 256 "$1" | cut -c1-12; }
  if [ -f "$LICENSED_TMP/NASB2020.sabible" ] && [ -f "$LICENSED_TMP/NASB2020-signing.pub" ]; then
    cp "$LICENSED_TMP/NASB2020.sabible" "$LICENSED_TMP/NASB2020-signing.pub" ScriptureAlone/Resources/Packages/
    echo "licensed translation: NASB2020 bundled (package $(fingerprint "$LICENSED_TMP/NASB2020.sabible"), key $(fingerprint "$LICENSED_TMP/NASB2020-signing.pub"))"
  else
    echo "licensed translation: NASB2020 not in $SA_LICENSED_REPO, not bundled"
  fi
  if [ -f "$LICENSED_TMP/NASB1995-signing.pub" ]; then
    cp "$LICENSED_TMP/NASB1995-signing.pub" ScriptureAlone/Resources/Packages/
    echo "licensed translation: NASB1995 key bundled (key $(fingerprint "$LICENSED_TMP/NASB1995-signing.pub")); its package is the nasb1995 asset pack" \
      "$([ -f "$LICENSED_TMP/NASB1995.sabible" ] && echo "(package $(fingerprint "$LICENSED_TMP/NASB1995.sabible") — upload that one)")"
  else
    echo "licensed translation: NASB1995 key not in $SA_LICENSED_REPO, not offered"
  fi
  rm -rf "$LICENSED_TMP"
  # A licensed translation with no seed to open it would ship as a default nobody can read.
  if ls ScriptureAlone/Resources/Packages/NASB*-signing.pub >/dev/null 2>&1 && [ -z "${SA_CONTENT_KEY_SEED:-}" ]; then
    echo "error: a licensed translation is in this build but SA_CONTENT_KEY_SEED is not set" >&2
    exit 1
  fi
else
  echo "licensed translation: SA_LICENSED_REPO / SA_LICENSED_TOKEN not set, none bundled"
fi
# Every other Bible is a download since 1.1.1, the ASV included, so an Xcode Cloud build without the
# NASB 2020 would open to a download — what App Review rejected in 1.0.0 build 40. Refuse to make one.
if [ "${CI_XCODE_CLOUD:-}" = "TRUE" ] && [ ! -f ScriptureAlone/Resources/Packages/NASB2020.sabible ]; then
  echo "error: the NASB 2020 is not in this build, and nothing else is in the app to open to" >&2
  exit 1
fi
# ---- end licensed translations -----------------------------------------------

# The seed file must exist BEFORE xcodegen runs: XcodeGen lists the files that are on disk when it
# generates, so one written afterwards is never compiled ("Cannot find 'ContentKeySeed' in scope").
python3 ci_scripts/write_content_key_seed.py

brew install xcodegen
xcodegen generate

