import SwiftUI
import WatchConnectivity
import ScriptureAloneCore

/// The watch's end of WatchConnectivity: which translation the reader is using on the phone, and
/// the translations the phone sends: compact editions of imports and of the Bibles the watch doesn't
/// carry, and the sealed ASV and NASB 1995 packages.
///
/// Two channels, each chosen for what it carries:
/// - **Application context** for the phone's current translation. It holds only the latest value
///   and is delivered the next time the watch app runs, which is exactly the semantics of "what
///   is the reader using now" — a stale value is never replayed after a newer one.
/// - **File transfer** for a translation's edition or sealed package, megabytes that must arrive
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
    fileprivate nonisolated static func phoneChoice(in context: [String: Any]) -> PhoneChoice? {
        guard let id = context[WatchLinkKeys.translation] as? String,
              let changedAt = context[WatchLinkKeys.changedAt] as? TimeInterval else { return nil }
        return PhoneChoice(id: id, at: changedAt,
                           notForWatch: context[WatchLinkKeys.translationNotForWatch] as? Bool ?? false,
                           label: context[WatchLinkKeys.translationLabel] as? String)
    }

    fileprivate struct PhoneChoice: Sendable {
        let id: String
        let at: TimeInterval
        /// The publisher keeps this translation off watches; the phone will never send it.
        let notForWatch: Bool
        /// What the phone calls it ("NASB 1995"), when the phone says.
        let label: String?
    }

    fileprivate func apply(_ choice: PhoneChoice?) {
        guard let choice else { return }
        bible?.phoneChose(choice.id, at: choice.at, notForWatch: choice.notForWatch, label: choice.label)
    }

    /// The imports the phone offers, when its context says (an older phone app doesn't).
    fileprivate nonisolated static func phoneImports(in context: [String: Any]) -> [String]? {
        context[WatchLinkKeys.imports] as? [String]
    }

    /// The phone's accent colour, when its context carries one.
    fileprivate nonisolated static func phoneAccent(in context: [String: Any]) -> Int? {
        context[WatchLinkKeys.accent] as? Int
    }

    fileprivate func applyAccent(_ accent: Int?) {
        guard let accent else { return }
        UserDefaults.standard.set(accent, forKey: WatchAccent.key)
    }

    fileprivate func applyImports(_ imports: [String]?) {
        guard let imports, let bible else { return }
        if bible.removeImports(notIn: Set(imports)) { reportEditions() }
    }

    /// Tells the phone which received editions the watch holds, and which version of each.
    fileprivate func reportEditions() {
        guard let session, session.activationState == .activated, let bible else { return }
        let received = bible.editions.filter { !$0.bundled }.map(\.id)
        try? session.updateApplicationContext([WatchLinkKeys.editions: received,
                                               WatchLinkKeys.editionVersions: WatchBible.receivedVersions(),
                                               WatchLinkKeys.bundled: WatchBible.bundledIDs])
    }
}

extension WatchPhoneLink: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState,
                             error: Error?) {
        // Whatever the phone set while the watch app wasn't running is waiting here.
        let choice = Self.phoneChoice(in: session.receivedApplicationContext)
        let imports = Self.phoneImports(in: session.receivedApplicationContext)
        let accent = Self.phoneAccent(in: session.receivedApplicationContext)
        Task { @MainActor in
            self.apply(choice)
            self.applyImports(imports)
            self.applyAccent(accent)
            self.reportEditions()
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext context: [String: Any]) {
        let choice = Self.phoneChoice(in: context)
        let imports = Self.phoneImports(in: context)
        let accent = Self.phoneAccent(in: context)
        Task { @MainActor in
            self.apply(choice)
            self.applyImports(imports)
            self.applyAccent(accent)
        }
    }

    nonisolated func session(_ session: WCSession, didReceive file: WCSessionFile) {
        // The system deletes `file.fileURL` as soon as this method returns, so the move happens
        // here, synchronously, before anything hops to the main actor.
        guard let id = file.metadata?[WatchLinkKeys.translation] as? String,
              WatchLinkKeys.isSafeID(id), !WatchBible.bundledIDs.contains(id) else { return }
        // A package whose publisher keeps it off wearables is never kept, whoever sent it: returning
        // leaves it where the system deletes it. (Opening would refuse it too; this keeps it off disk.)
        if WatchBible.sealedIDs.contains(id), !WearableLicence.allowsWearables(packageAt: file.fileURL) { return }
        let destination = WatchBible.receivedURL(for: id)
        try? FileManager.default.removeItem(at: destination)
        guard (try? FileManager.default.moveItem(at: file.fileURL, to: destination)) != nil else { return }
        WatchBible.recordReceived(id, version: file.metadata?[WatchLinkKeys.version] as? String,
                                  isImport: file.metadata?[WatchLinkKeys.kind] as? String == WatchLinkKeys.importKind)
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
                                Text(edition.abbreviation).font(.headline)
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
        // Named as the phone names it ("NASB 1995"), never by its internal identifier ("NASB1995").
        if let phone = bible.phoneTranslation, !bible.editions.contains(where: { $0.id == phone }),
           bible.phoneTranslationNotForWatch {
            // Never in the list: its publisher keeps it off watches, so it is hidden here, not greyed.
            Text("\(bible.phoneTranslationLabel) on your iPhone isn't available on the watch: its licence doesn't allow it.")
        } else if let phone = bible.phoneTranslation, !bible.editions.contains(where: { $0.id == phone }) {
            Text("\(bible.phoneTranslationLabel) on your iPhone can't be read here. Online translations can't be stored on the watch.")
        } else {
            Text("Follows your iPhone. A translation you import there appears here too.")
        }
    }
}
