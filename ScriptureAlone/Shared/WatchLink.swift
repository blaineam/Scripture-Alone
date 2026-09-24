#if os(iOS)
import Foundation
import WatchConnectivity
import ScriptureAloneCore

/// The phone's end of WatchConnectivity: tells the watch which translation the reader is using,
/// and keeps the watch holding every translation the reader imported.
///
/// The watch already bundles compact editions of the ASV, BSB and KJV, so for those only the
/// choice is sent. Each imported translation is written as a `WatchEdition` (about a third of the
/// full store's size) and transferred — only when its terms allow offline storage, and only when
/// the watch reports it doesn't already hold that version. The list of imports travels too, so one
/// removed on the phone (or on another device, through iCloud) is removed from the watch. A
/// language Bible is sent when it is the one being read. An online translation is never sent: its
/// terms forbid storing it, and it has no file.
///
/// See `WatchPhoneLink` on the watch for why the choice travels as application context and the
/// edition as a file transfer.
@MainActor
final class WatchLink: NSObject {
    static let shared = WatchLink()

    private let session: WCSession? = WCSession.isSupported() ? .default : nil
    private let defaults = UserDefaults.standard
    /// The latest translation to report, held until the session is ready to carry it.
    private var pending: TranslationEntry?
    /// Every imported translation, as the library last reported them — nil until it first has.
    /// The session can come up before the library reports, and an empty list sent then would tell
    /// the watch to delete every import it holds, only to be sent them all again.
    private var imports: [TranslationEntry]?

    func activate() {
        guard let session, session.delegate == nil else { return }
        session.delegate = self
        session.activate()
    }

    /// The reader switched translation. Records when, so a later launch re-reporting this same
    /// choice carries the original time and cannot override a newer pick made on the watch.
    func readerSwitched(to entry: TranslationEntry) {
        defaults.set(Date().timeIntervalSince1970, forKey: Self.changedAtKey)
        publish(entry)
    }

    /// Reports `entry` as the current translation — at launch, or after a switch.
    func publish(_ entry: TranslationEntry) {
        pending = entry
        send(entry)
    }

    /// Always reads `self.session` rather than taking one: WCSession isn't Sendable, so it must
    /// not be carried across from the delegate's background queue.
    private func send(_ entry: TranslationEntry) {
        guard let session, session.activationState == .activated,
              session.isPaired, session.isWatchAppInstalled else { return }
        updateContext()
        sendEditionIfNeeded(entry)
    }

    /// The reader's imports changed — one added or removed here, or arriving through iCloud.
    func importsChanged(_ entries: [TranslationEntry]) {
        let safe = entries.filter { WatchLinkKeys.isSafeID($0.id) }
        imports = safe
        updateContext()
        for entry in safe { sendEditionIfNeeded(entry) }
    }

    /// Application context holds one dictionary, replaced whole on every update, so every key the
    /// watch reads is written every time.
    private func updateContext() {
        guard let session, session.activationState == .activated,
              session.isPaired, session.isWatchAppInstalled else { return }
        // No list at all until the library has reported: the watch then keeps what it holds.
        var context: [String: Any] = [:]
        if let imports { context[WatchLinkKeys.imports] = imports.map(\.id) }
        if let pending {
            context[WatchLinkKeys.translation] = pending.id
            context[WatchLinkKeys.changedAt] = defaults.double(forKey: Self.changedAtKey)
        }
        try? session.updateApplicationContext(context)
    }

    private func sendEditionIfNeeded(_ entry: TranslationEntry) {
        guard let session, session.activationState == .activated, session.isPaired, session.isWatchAppInstalled,
              let url = entry.url, Self.isImported(url) || Self.isLocaleBible(entry.id),
              WatchLinkKeys.isSafeID(entry.id) else { return }
        let context = session.receivedApplicationContext
        let held = context[WatchLinkKeys.editions] as? [String] ?? []
        let versions = context[WatchLinkKeys.editionVersions] as? [String: String]
        let imported = Self.isImported(url)
        // An older watch app reports ids only; then holding the translation at all is enough.
        guard !held.contains(entry.id) || (imported && versions != nil) else { return }
        // Already on its way: don't queue a second copy of the same few megabytes.
        let inFlight = session.outstandingFileTransfers.contains {
            $0.file.metadata?[WatchLinkKeys.translation] as? String == entry.id
        }
        guard !inFlight else { return }

        let id = entry.id
        let heldVersion = versions?[id]
        Task.detached(priority: .utility) {
            guard let store = try? BibleStore(url: url), store.info.rights.allowOfflineStorage else { return }
            let version = imported ? ImportedBibleSync.fingerprint(of: url) : nil
            if let version, version == heldVersion { return }
            let edition = URL.cachesDirectory.appending(path: "WatchEditions/\(id)-Watch.sqlite")
            do {
                try WatchEdition.write(from: url, to: edition)
            } catch {
                return
            }
            var metadata: [String: String] = [WatchLinkKeys.translation: id]
            if imported { metadata[WatchLinkKeys.kind] = WatchLinkKeys.importKind }
            if let version { metadata[WatchLinkKeys.version] = version }
            await self.transfer(edition, metadata: metadata)
        }
    }

    private func transfer(_ edition: URL, metadata: [String: String]) {
        guard let session, session.activationState == .activated else { return }
        _ = session.transferFile(edition, metadata: metadata)
    }

    /// The big-8 locales' Bibles (docs/localization.md) aren't bundled on the watch — it carries the
    /// ASV, BSB and KJV — so the phone sends them as editions too, with their verse numbering.
    private static func isLocaleBible(_ id: String) -> Bool {
        AssetPack(translationID: id)?.locale != nil
    }

    /// Imported translations live in `ImportedLibrary.directory`; bundled ones are inside the app.
    private static func isImported(_ url: URL) -> Bool {
        url.standardizedFileURL.path.hasPrefix(ImportedLibrary.directory.standardizedFileURL.path)
    }

    private static let changedAtKey = "watch.translationChangedAt"
}

extension WatchLink: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState,
                             error: Error?) {
        Task { @MainActor in
            if let pending = self.pending { self.send(pending) }
            if let imports = self.imports { self.importsChanged(imports) }
        }
    }

    /// The watch reported which editions it holds — perhaps after being reinstalled — so re-check
    /// whether the current translation still needs sending.
    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        Task { @MainActor in
            if let pending = self.pending { self.sendEditionIfNeeded(pending) }
            for entry in self.imports ?? [] { self.sendEditionIfNeeded(entry) }
        }
    }

    /// A transfer that failed (the watch app was being installed, storage was full) is tried again
    /// the next time anything prompts a check; one that succeeded is reported back by the watch.
    nonisolated func session(_ session: WCSession, didFinish fileTransfer: WCSessionFileTransfer, error: (any Error)?) {
        guard error != nil else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(30))
            for entry in self.imports ?? [] { self.sendEditionIfNeeded(entry) }
        }
    }

    /// The watch app was installed (or removed) — send it what it should hold.
    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in if let imports = self.imports { self.importsChanged(imports) } }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    /// Switching to a different paired watch deactivates the session; activating again connects
    /// it to the new one.
    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }
}
#endif
