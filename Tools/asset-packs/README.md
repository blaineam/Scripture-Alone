# Asset packs

The BSB, KJV and the study databases don't ship inside the app binary. They are **Apple-hosted
Background Assets** packs, uploaded to App Store Connect separately from the build. The ASV does
ship inside it — see below.

| Pack ID | File | Policy | Size |
|---|---|---|---|
| `asv` | `Packages/ASV.sabible` | `onDemand` (v2) — legacy only, see below | ~16 MB |
| `bsb` | `Bibles/BSB.sqlite` | `onDemand` | ~5 MB packed |
| `kjv` | `Bibles/KJV.sqlite` | `onDemand` | ~5 MB packed |
| `study-commentary` | `Study/Study.sqlite` | `onDemand` | ~40 MB |
| `study-interlinear` | `Study/Interlinear.sqlite` | `onDemand` | ~9 MB |

Every manifest uses `fileSource` / `fileDestination` so each file sits at the **root** of its pack,
which is how `AssetLibrary` addresses it (`descriptor(for: FilePath(pack.file))`). A plain `file`
selector preserves the source directory inside the pack — Mi Speaks shipped that bug and downloaded
325 MB to fail with "No file was found".

What stays in the binary: `Study/CrossReferences.sqlite` (derived by `Tools/build_study.py`, so
cross references never wait on the commentary download), `Study/Context.sqlite`, `Basemap.bin`, and
`Packages/bundled-signing.pub` — the key the ASV package is verified against. A trust anchor that
arrived by the same channel as the package it vouches for would vouch for nothing.

## Why the ASV is bundled, and why its pack still exists

Version 1 of `asv` was `essential` on first installation. App Review's iPad launched 1.0.0 build 40
to a spinner that never ended: the essential pack was accepted in the same submission, yet the ASV
wasn't readable at launch. The translation a fresh install opens to can't wait on delivery, so
`ASV.sabible` is an app resource again and `SealedTranslations` opens it from the bundle.

The pack is kept, as version 2 with an `onDemand` policy, for builds up to 40 (TestFlight), which
still fetch it through `AssetLibrary.ensure(.asv)`. `onDemand` means no new install downloads it
for nothing. **Never archive it**: archiving is permanent.

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
