# Asset packs

The two study databases do not ship inside the app. They are **Apple-hosted Background Assets**,
uploaded to App Store Connect separately from the build and fetched when a reader first opens
Commentary or Original Languages.

| Pack | File | Size | Manifest |
|---|---|---|---|
| `commentary` | `Study.sqlite` | ~40 MB | `commentary.json` |
| `interlinear` | `Interlinear.sqlite` | ~9 MB | `interlinear.json` |

## Why not On-Demand Resources

ODR ties a pack to an app *version*. Apple's own answer to "my tagged files are identical between
builds, why did they download again":

> resources are tied to a specific app version as part of the app's submission, and the system does
> not have any notion of understanding whether the files in an asset pack are identical across app
> versions

So every update re-downloaded 55 MB that had not changed. ODR is also deprecated as of iOS 27 —
`NSBundleResourceRequest` now carries *"Use Background Assets instead."*

## Uploading

Needed **whenever a database changes**, and once before the first build that expects a pack. A
build cannot fetch a pack that was never uploaded; the reader is told the pack "isn't available for
this version of the app yet".

```bash
xcrun ba-package package Tools/asset-packs/commentary.json -o /tmp/commentary.aar
xcrun altool --upload-asset-pack /tmp/commentary.aar --apple-id 6813729762 \
    --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
```

Then watch for `State: AVAILABLE`:

```bash
xcrun altool --list-asset-pack-versions --apple-id 6813729762 \
    --asset-pack-identifier commentary --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
```

`ba-package` **will not overwrite an existing archive** and says nothing useful when it declines —
delete the `.aar` first, or you will upload the previous contents and spend a while confused.

## Things that bite

- **The identifier cannot contain dots.** `com.blainemiller.ScriptureAlone.commentary` is rejected
  by App Store Connect with `PARAMETER_ERROR` on `filter[assetPackIdentifier]`. Plain names only;
  they are scoped to the app already. `StudyPack.id` must match `assetPackID` exactly.
- **Platforms must match the app's App Store Connect record.** This app has one platform, iOS — the
  Mac runs the same iPad build — so listing `macOS` earns `ITMS-91139`, and *removing* it from a
  later version earns `ITMS-91148` warning that earlier versions had it. Both are expected here.
- **A pack update applies to app versions already installed.** Per WWDC25/325: "all versions of your
  app downloaded from the App Store will automatically be switched over to using asset pack version
  2, including older versions that are still installed." So a schema change to either database must
  stay readable by older builds, or ship under a *new* `assetPackID` rather than a new version of
  this one.
- **The extension must be embedded into the app *wrapper*, not the products directory.** XcodeGen's
  default for an `extensionkit-extension` is a copy phase with `dstPath = $(EXTENSIONS_FOLDER_PATH)`
  and `dstSubfolderSpec = 16` (products directory). During `xcodebuild archive` that is not where
  the app is installed — the appex is copied into a second `Scripture Alone.app` sitting in
  `BuildProductsPath` rather than the one under `InstallationBuildProductsLocation`. Xcode reports
  it only as a *warning* ("is embedded in the parent app bundle's `../../../BuildProductsPath/…`
  directory") and the archive and all three exports still succeed, so it passes locally; Xcode
  Cloud then fails the build at `Preparing build for App Store Connect` with no further detail.
  The dependency therefore pins the destination itself:

  ```yaml
  - target: ScriptureAloneAssets
    copy: { destination: wrapper, subpath: Extensions }
  ```

  which is what `PlugIns` gets for free, since its subfolder spec is already wrapper-relative.
- **The download policy is `onDemand` on purpose.** `essential` blocks app launch on 40 MB;
  `prefetch` spends it on readers who never open Commentary.

## What the app does with a pack

`StudyAssetLibrary` downloads it, copies the database into Application Support, and then releases
the pack. Background Assets exposes `Data` or a file descriptor and never a path, and SQLite needs a
path — so the copy is necessary, and it is also the reason a reader downloads the commentary once
and keeps it through every future update.

## The app must declare Apple hosting

Apple-Hosted Background Assets is not configured by the extension alone. The app and the downloader
extension must **share an app group** (the system coordinates between them through it), and the app
target's `Info.plist` must carry three keys:

```xml
<key>BAAppGroupID</key>            <string>group.com.blainemiller.ScriptureAlone</string>
<key>BAHasManagedAssetPacks</key>  <true/>
<key>BAUsesAppleHosting</key>      <true/>
```

Apple's instruction is to *omit every other* Background Assets key when using Apple hosting — no
`BAManifestURL`, no `BAInitialRestrictions*`, no `BAEssentialMaxInstallSize`.

Without them the app builds, archives and exports cleanly, and Xcode says nothing at all. Xcode
Cloud fails the run at `Preparing build for App Store Connect` with no further detail. The
extension's bundle identifier also needs the **App Groups** capability enabled in the developer
portal, or automatic signing cannot build a profile for it.

Get any of it wrong and App Store Connect rejects the *delivery*, so the build never becomes a
build record — nothing is burned, but the only description of the problem is the ITMS mail Apple
sends afterwards. Xcode Cloud itself says nothing beyond `Preparing build for App Store Connect
failed`. Two that cost a round trip each:

- **`ITMS-90979`** — `EXPrincipalClass` and `EXExtensionPrincipalClass` are *disallowed* when the
  extension binary has a `__swift5_entry` section, which is exactly what `@main` emits. The Swift
  entry point already is the principal class. Declare only `EXExtensionPointIdentifier`.
- **`ITMS-90923`/`90924`** demanding `BAManifestURL`, `BAMaxInstallSize` and
  `BAInitialDownloadRestrictions.*` mean the app was read as *self-hosted*. That is the symptom of
  the three keys above being absent, not an instruction to add the legacy ones.
