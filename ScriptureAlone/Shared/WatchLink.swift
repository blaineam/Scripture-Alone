#if os(iOS)
import Foundation
import WatchConnectivity
import ScriptureAloneCore

/// The phone's end of WatchConnectivity: tells the watch which translation the reader is using,
/// and sends the watch an edition of a translation the reader imported.
///
/// The watch already bundles compact editions of the ASV, BSB and KJV, so for those only the
/// choice is sent. An imported translation has no bundled edition; this writes one with
/// `WatchEdition` (about a third of the full store's size) and transfers it — but only when the
/// translation's terms allow offline storage, and only when the watch reports it doesn't already
/// hold it. An online translation is never sent: its terms forbid storing it, and it has no file.
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
        let changedAt = defaults.double(forKey: Self.changedAtKey)
        try? session.updateApplicationContext([
            WatchLinkKeys.translation: entry.id,
            WatchLinkKeys.changedAt: changedAt,
        ])
        sendEditionIfNeeded(entry)
    }

    private func sendEditionIfNeeded(_ entry: TranslationEntry) {
        guard let session, session.activationState == .activated,
              let url = entry.url, Self.isImported(url) || Self.isLocaleBible(entry.id),
              WatchLinkKeys.isSafeID(entry.id) else { return }
        let held = session.receivedApplicationContext[WatchLinkKeys.editions] as? [String] ?? []
        guard !held.contains(entry.id) else { return }
        // Already on its way: don't queue a second copy of the same few megabytes.
        let inFlight = session.outstandingFileTransfers.contains {
            $0.file.metadata?[WatchLinkKeys.translation] as? String == entry.id
        }
        guard !inFlight else { return }

        let id = entry.id
        Task.detached(priority: .utility) {
            guard let store = try? BibleStore(url: url), store.info.rights.allowOfflineStorage else { return }
            let edition = URL.cachesDirectory.appending(path: "WatchEditions/\(id)-Watch.sqlite")
            do {
                try WatchEdition.write(from: url, to: edition)
            } catch {
                return
            }
            await self.transfer(edition, id: id)
        }
    }

    private func transfer(_ edition: URL, id: String) {
        guard let session, session.activationState == .activated else { return }
        _ = session.transferFile(edition, metadata: [WatchLinkKeys.translation: id])
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
        }
    }

    /// The watch reported which editions it holds — perhaps after being reinstalled — so re-check
    /// whether the current translation still needs sending.
    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        Task { @MainActor in
            if let pending = self.pending { self.sendEditionIfNeeded(pending) }
        }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    /// Switching to a different paired watch deactivates the session; activating again connects
    /// it to the new one.
    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }
}
#endif
