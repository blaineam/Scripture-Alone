# Asset packs

Since 1.1.1 the only Bible inside the app binary is the sealed **NASB 2020**, the translation a fresh
install opens to (copied in by Xcode Cloud from private storage; see `docs/lockman/README.md`). Every
other Bible, and the study databases, are **Apple-hosted Background Assets** packs, uploaded to App Store
Connect separately from the build.

| Pack ID | File | Policy | Size |
|---|---|---|---|
| `asv` | `Packages/ASV.sabible` | `onDemand` (v3 since 1.1.1 — re-signed, see below) | ~16 MB |
| `nasb1995` | `NASB1995.sabible`, from private storage | `onDemand` | ~18 MB |
| `bsb` | `Bibles/BSB.sqlite` | `onDemand` | ~5 MB packed |
| `kjv` | `Bibles/KJV.sqlite` | `onDemand` | ~5 MB packed |
| `study-commentary` | `Study/Study.sqlite` | `onDemand` | ~40 MB |
| `study-interlinear` | `Study/Interlinear.sqlite` | `onDemand` | ~9 MB |

Every manifest uses `fileSource` / `fileDestination` so each file sits at the **root** of its pack,
which is how `AssetLibrary` addresses it (`descriptor(for: FilePath(pack.file))`). A plain `file`
selector preserves the source directory inside the pack — Mi Speaks shipped that bug and downloaded
325 MB to fail with "No file was found".

What stays in the binary: the NASB 2020, `Study/CrossReferences.sqlite` (derived by
`Tools/build_study.py`, so cross references never wait on the commentary download),
`Study/Context.sqlite`, `Basemap.bin`, and the signing keys every sealed package is verified against —
`Packages/bundled-signing.pub` (the ASV's), `NASB2020-signing.pub` and `NASB1995-signing.pub`. A trust
anchor that arrived by the same channel as the package it vouches for would vouch for nothing.

## The ASV and the NASB 1995

Sealed packages, opened by `SealedTranslations` once copied out. Neither is ever what a fresh install
opens to: version 1 of `asv` was `essential`, and App Review's iPad launched 1.0.0 build 40 to a spinner
that never ended — the essential pack was accepted in the same submission, yet the ASV wasn't readable
at launch. So a build carries the NASB 2020 (Xcode Cloud refuses to build without it), and the ASV
waits to be chosen like any other Bible.

**The ASV was re-signed for 1.1.1** (the old signing key was derived from a published string), so its
pack needs **version 3**, uploaded from today's `ScriptureAlone/Resources/Packages/ASV.sabible` and
submitted with the 1.1.1 version. A copy signed with the old key that a device already holds no longer
opens, and the app removes it and downloads again. **Never archive `asv`**: builds up to 40 fetch it.

**The NASB 1995's package is never in this repository.** Its manifest's `fileSource` is the bare file
name, so package it from the private clone holding the file:

```bash
cd ~/secure/nasb/licensed
rm -f /tmp/nasb1995.aar
xcrun ba-package package "<repo>/Tools/asset-packs/nasb1995.json" -o /tmp/nasb1995.aar
```

then upload as below. Upload the package whose key Xcode Cloud bundles: the build log prints the
fingerprint of the one in the private repository (`licensed translation: NASB1995 key bundled …`).

Every pack is copied out of (Background Assets exposes only `Data` or a file descriptor; SQLite
and the package reader need a path) and then released, so nothing is stored twice.

## Uploading

```bash
rm -f /tmp/asv.aar                       # ba-package will NOT overwrite, and says nothing useful
xcrun ba-package package Tools/asset-packs/asv.json -o /tmp/asv.aar
xcrun altool --upload-asset-pack /tmp/asv.aar --apple-id 6813729762 \
    --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
xcrun altool --list-asset-pack-versions --apple-id 6813729762 \
    --asset-pack-identifier asv --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
```

`xcrun ba-package evaluate <manifest>` checks a manifest without packaging it.

## Submitting

**Packs are reviewed.** They reach App Store users only after App Review, and until the app's first
version is approved they must be in the **same review submission** as that version (up to ten packs
per submission). Through the API that is a `reviewSubmissionItems` entry whose relationship is
`backgroundAssetVersion`. Submit the app without its packs and App Store users get a build with no
Bible. Later, a pack version can be submitted with or without an app version, but an app version
that needs a new pack version must be submitted with it.

TestFlight uses a pack version as soon as it is `READY_FOR_TESTING`.

## Things that bite

Every one of these fails only at App Store delivery; Xcode Cloud reports no more than `Preparing
build for App Store Connect failed`, and the only detail is the ITMS email. **Validate locally**:
signed archive with `-authenticationKeyPath`, export, then
`xcrun altool --validate-app -f <ipa> --type ios --apiKey … --apiIssuer … --output-format json`.
It reports the same errors and creates no build record.

- **XcodeGen wipes a hand-written entitlements file.** With only `entitlements: path:`, every
  `xcodegen generate` writes an empty `<dict/>`. The extension's app-group entitlement must be under
  `properties:` in `project.yml`. This — not the portal — was the persistent cause of `ITMS-90958`.
  Check with `codesign -d --entitlements :- <appex>`, not the source file.
- **The app group must also be assigned to the extension's bundle ID** in the Developer Portal
  (Identifiers → `com.blainemiller.ScriptureAlone.assets` → App Groups). The public App Store
  Connect API can enable `APP_GROUPS` but cannot assign a group.
- **The app declares Apple hosting**: `BAAppGroupID`, `BAHasManagedAssetPacks`, `BAUsesAppleHosting`
  in `Info.plist`, and no other `BA*` key. Demands for `BAManifestURL` or `BAMaxInstallSize` mean the
  app was read as self-hosted, i.e. one of the three is missing.
- **ExtensionKit, embedded into the wrapper.** `type: extensionkit-extension`, and the dependency
  pinned to `copy: { destination: wrapper, subpath: Extensions }` — XcodeGen's default puts the appex
  in a stray `.app` during archive.
- **No `EXPrincipalClass`** (`ITMS-90979` with `@main`), and **`import ExtensionFoundation`** or
  `@main` fails at archive time.
- **No `shouldDownload` in the extension.** Returning `false` would veto the essential ASV.
- **Identifiers cannot contain dots.**
- **Archiving a pack is permanent.** An archived pack rejects every change through the API — no new
  version, no unarchiving. That is why the study packs are `study-commentary` and
  `study-interlinear`: `commentary` and `interlinear` were archived and can never carry content again.
- **A pack update applies to app versions already installed.** Keep old builds able to read a new
  version, or ship it under a new identifier.

## Development builds

Builds run from Xcode get no asset packs, so the app would open with no Bible. In Debug only, a build
phase copies the pack sources into the bundle and `AssetLibrary` installs from there. Release never
carries them, so a broken pack path can't hide behind a bundled copy.

## Regional Bible packs (1.0.0)

`bibles-east-asia.json` and `bibles-europe.json` carry the eight locale Bibles for 1.0.0 — Apple
allows ten packs per review submission, and a first version must carry all of its packs. The app
copies only the reader's Bible out of its region (`AssetLibrary.install`). The single-Bible
manifests (`lsg.json`, `cuvs.json`…) are for the update that splits them. Never archive a regional
pack: installs of 1.0.0 keep fetching from it.
