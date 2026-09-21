import SwiftUI
import WatchConnectivity
import ScriptureAloneCore

/// The watch's end of WatchConnectivity: which translation the reader is using on the phone, and
/// the compact editions the phone sends of translations the reader imported.
///
/// Two channels, each chosen for what it carries:
/// - **Application context** for the phone's current translation. It holds only the latest value
///   and is delivered the next time the watch app runs, which is exactly the semantics of "what
///   is the reader using now" — a stale value is never replayed after a newer one.
/// - **File transfer** for an imported translation's edition, a few megabytes that must arrive
///   intact even if the watch is out of range when it is sent.
///
/// The watch reports back which editions it holds, so the phone sends a translation only when the
/// watch lacks it — including after the watch app is reinstalled and its received editions are
/// gone, which a "sent it once" flag on the phone would never notice.
@MainActor
final class WatchPhoneLink: NSObject {
    static let shared = WatchPhoneLink()

    private let session: WCSession? = WCSession.isSupported() ? .default : nil
    private weak var bible: WatchBible?

    func activate(bible: WatchBible) {
        self.bible = bible
        guard let session, session.delegate == nil else { return }
        session.delegate = self
        session.activate()
    }

    /// Reads the phone's choice out of an application context. Done on the delegate's own queue,
    /// before any hop to the main actor: the context is `[String: Any]`, which isn't Sendable, but
    /// the two values in it are.
    fileprivate nonisolated static func phoneChoice(in context: [String: Any]) -> (id: String, at: TimeInterval)? {
        guard let id = context[WatchLinkKeys.translation] as? String,
              let changedAt = context[WatchLinkKeys.changedAt] as? TimeInterval else { return nil }
        return (id, changedAt)
    }

    fileprivate func apply(_ choice: (id: String, at: TimeInterval)?) {
        guard let choice else { return }
        bible?.phoneChose(choice.id, at: choice.at)
    }

    /// Tells the phone which received editions the watch holds.
    fileprivate func reportEditions() {
        guard let session, session.activationState == .activated, let bible else { return }
        let received = bible.editions.filter { !$0.bundled }.map(\.id)
        try? session.updateApplicationContext([WatchLinkKeys.editions: received])
    }
}

extension WatchPhoneLink: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState,
                             error: Error?) {
        // Whatever the phone set while the watch app wasn't running is waiting here.
        let choice = Self.phoneChoice(in: session.receivedApplicationContext)
        Task { @MainActor in
            self.apply(choice)
            self.reportEditions()
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        let choice = Self.phoneChoice(in: context)
        Task { @MainActor in self.apply(choice) }
    }

    nonisolated func session(_ session: WCSession, didReceive file: WCSessionFile) {
        // The system deletes `file.fileURL` as soon as this method returns, so the move happens
        // here, synchronously, before anything hops to the main actor.
        guard let id = file.metadata?[WatchLinkKeys.translation] as? String,
              WatchLinkKeys.isSafeID(id), !WatchBible.bundledIDs.contains(id) else { return }
        let destination = WatchBible.receivedURL(for: id)
        try? FileManager.default.removeItem(at: destination)
        guard (try? FileManager.default.moveItem(at: file.fileURL, to: destination)) != nil else { return }
        Task { @MainActor in
            self.bible?.reloadEditions()
            self.reportEditions()
        }
    }
}

/// The picker, reached from the home screen.
struct WatchTranslationsView: View {
    @Environment(WatchBible.self) private var bible

    var body: some View {
        List {
            Section {
                ForEach(bible.editions) { edition in
                    Button {
                        bible.choose(edition.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(edition.id).font(.headline)
                                Text(edition.name).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                            }
                            Spacer()
                            if edition.id == bible.translation {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                                    .accessibilityLabel("Selected")
                            }
                        }
                    }
                    .swipeActions {
                        if !edition.bundled {
                            Button("Remove", role: .destructive) { bible.removeReceived(edition) }
                        }
                    }
                }
            } footer: {
                footer
            }
        }
        .navigationTitle("Translation")
    }

    @ViewBuilder private var footer: some View {
        if let phone = bible.phoneTranslation, !bible.editions.contains(where: { $0.id == phone }) {
            Text("\(phone) on your iPhone can't be read here. Online translations can't be stored on the watch.")
        } else {
            Text("Follows your iPhone. A translation you import there appears here too.")
        }
    }
}
