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
/// `shouldDownload` is the one hook worth keeping. The study databases are on demand, so nothing
/// here is scheduled automatically; a reader asks for them from the app and the app calls
/// `ensureLocalAvailability`. Returning false keeps the system from fetching 55 MB on behalf of
/// someone who never opens Commentary.
@main
struct AssetDownloader: StoreDownloaderExtension {
    func shouldDownload(_ assetPack: AssetPack) -> Bool { false }
}
