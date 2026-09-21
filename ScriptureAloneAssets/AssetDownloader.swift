import BackgroundAssets
// ExtensionKit, not the legacy plug-in kind: a Background Assets downloader is declared with
// `EXAppExtensionAttributes` and lives in the app's Extensions directory. Without this import the
// `@main` below is "cannot use static method 'main()' here", which surfaces only at archive time.
import ExtensionFoundation
import StoreKit

/// The Background Assets downloader extension.
///
/// It has no behaviour of its own, and that is the design: adopting `StoreDownloaderExtension` opts
/// into the system's own downloader, which schedules Apple-hosted asset packs at install, at update
/// and in the background without this process doing anything. The framework requires the extension
/// to exist and to adopt the protocol — `AssetPackManager` states that not doing so is a programmer
/// error — so this file is the whole of it.
///
/// **There is deliberately no `shouldDownload`.** Each pack's manifest already says when it
/// downloads: the ASV is `essential`, so it arrives with the install and a fresh install reads
/// offline; the BSB, the KJV, the commentary and the original languages are `onDemand`, fetched only
/// when the reader asks. An earlier version returned `false` from `shouldDownload`, which is
/// harmless for on-demand packs but would veto the essential ASV and leave a new install with no
/// Bible at all. Apple's guidance is to omit the method when the manifest policies are enough.
@main
struct AssetDownloader: StoreDownloaderExtension {}
