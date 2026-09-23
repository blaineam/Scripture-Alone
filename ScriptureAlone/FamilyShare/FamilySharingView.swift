import SwiftUI
import SwiftData
import CloudKit
import ScriptureAloneCore
#if os(iOS)
import UIKit
#endif

/// The "Share with Family" row in Legacy & Export.
struct FamilySharingSettingsSection: View {
    var body: some View {
        let owner = FamilySharingOwner.shared
        Section {
            NavigationLink {
                FamilySharingView()
            } label: {
                HStack {
                    Label("Share with Family", systemImage: "person.2")
                    Spacer()
                    if owner.isSharing {
                        Text("On").foregroundStyle(.secondary)
                    }
                }
            }
        } footer: {
            Text("Let the family members you invite see your highlights, notes and favorites as you keep reading — live and read-only, through iCloud.")
        }
    }
}

/// The owner's screen: what family will see, who can see it, and turning it on and off.
struct FamilySharingView: View {
    @Environment(ReaderModel.self) private var model
    @Query private var highlights: [Highlight]
    @Query private var notes: [Note]
    @Query private var favorites: [Favorite]
    @AppStorage("legacy.ownerName") private var ownerName = ""
    @AppStorage("legacy.dedication") private var dedication = ""
    @AppStorage("legacy.translation") private var translation = ""
    @State private var confirmStop = false

    private var owner: FamilySharingOwner { FamilySharingOwner.shared }
    private var trimmedName: String { ownerName.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var title: String { KeepsakeManifest(ownerName: trimmedName).displayTitle }

    var body: some View {
        ScrollViewReader { proxy in
            form
                #if DEBUG
                .task {
                    guard FamilyDebug.scene == "owner-bottom" else { return }
                    try? await Task.sleep(for: .milliseconds(600))
                    proxy.scrollTo("family", anchor: .top)
                }
                #endif
        }
    }

    private var form: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Image(systemName: "person.2.fill")
                        .font(.largeTitle)
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                    Text("Share your Bible with family")
                        .font(.system(.title2, design: .serif).weight(.semibold))
                    Text("The people you invite can open your Bible in Scripture Alone and read it as you mark it. When you highlight a verse or write a note, it appears on their device too.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section {
                LabeledContent("Highlights", value: "\(Set(highlights.map(\.verseKey)).count)")
                LabeledContent("Notes", value: "\(notes.count)")
                LabeledContent("Favorites", value: "\(favorites.count)")
                LabeledContent("Your name and dedication", value: trimmedName.isEmpty ? String(localized: "Not set", comment: "No name or dedication has been entered") : title)
            } header: {
                Text("What Family Will See")
            } footer: {
                Text("It’s read-only: they can’t highlight, edit, add or delete anything, and nothing of theirs comes back to you. Not shared: your reading position, your settings, and the slide photos kept with camera notes.")
            }

            Section {
                TextField("Your name, as family knows you", text: $ownerName, prompt: Text("Dad, Grandma Ruth, Pastor Jim"))
                    #if os(iOS)
                    .textContentType(.name)
                    #endif
                TextField("Dedication (optional)", text: $dedication, axis: .vertical)
                    .lineLimit(2...5)
            } header: {
                Text("From You")
            } footer: {
                Text("Shown above your Bible on their device — the same name and dedication as your keepsake.")
            }

            switch owner.status {
            case .on:
                sharingSections
            case .working:
                Section { HStack { ProgressView(); Text("Working with iCloud…").foregroundStyle(.secondary) } }
            case .unavailable(let message):
                Section { Text(message).foregroundStyle(.secondary) }
            case .off, .unknown:
                startSection
            }

            Section {
                Text("A live share depends on your iCloud account. If the account is closed — after someone dies, for instance — the share goes with it. A keepsake is a file your family keeps forever, so make one too, and make a new one now and then.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                NavigationLink {
                    KeepsakeCreateView()
                } label: {
                    Label("Make a Keepsake Too", systemImage: "gift")
                }
            } header: {
                Text("For Years to Come")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Share with Family")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await owner.refresh() }
        .refreshable { await owner.refresh(force: true) }
        .confirmationDialog("Stop sharing your Bible?", isPresented: $confirmStop, titleVisibility: .visible) {
            Button("Stop Sharing", role: .destructive) { Task { await owner.stop() } }
        } message: {
            Text("Your shared copy is deleted from iCloud and family stop receiving updates. They keep the last copy their device has, marked as ended, and can save it as a keepsake. Your own highlights and notes aren’t touched.")
        }
    }

    // MARK: Sections

    private var startSection: some View {
        Section {
            Button {
                Task { await start() }
            } label: {
                Label("Start Sharing and Invite…", systemImage: "person.badge.plus")
                    .bold()
            }
            .disabled(trimmedName.isEmpty)
            if let error = owner.lastError {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
        } footer: {
            Text(trimmedName.isEmpty
                 ? "Add your name above first, so family know whose Bible it is."
                 : "Only people you invite can open it — the invitation link does nothing for anyone else. Each person needs an Apple Account and Scripture Alone. Your data goes only to your iCloud and theirs; there’s no server in between.")
        }
    }

    @ViewBuilder
    private var sharingSections: some View {
        Section {
            if owner.participants.filter({ $0.status != .owner }).isEmpty {
                Text("No one yet. Invite family to share it with them.")
                    .foregroundStyle(.secondary)
            }
            ForEach(owner.participants.filter { $0.status != .owner }) { person in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(person.name)
                        if let detail = person.detail {
                            Text(detail).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Text(person.statusText)
                        .font(.caption)
                        .foregroundStyle(person.status == .accepted ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                        .multilineTextAlignment(.trailing)
                }
                .accessibilityElement(children: .combine)
            }
            #if os(iOS)
            Button {
                invite()
            } label: {
                Label("Invite or Remove People…", systemImage: "person.badge.plus")
            }
            #endif
        } header: {
            Text("Family").id("family")
        } footer: {
            #if os(iOS)
            Text("Invitations are read-only and private. Removing someone stops their updates; they keep the copy they have.")
            #else
            Text("Invite or remove people from Scripture Alone on your iPhone or iPad.")
            #endif
        }

        Section {
            syncRow
            if let error = owner.lastError {
                Text(error).font(.footnote).foregroundStyle(.red)
            }
        } footer: {
            Text("Changes go out a few seconds after you make them, whenever you’re online.")
        }

        Section {
            Button("Stop Sharing…", role: .destructive) { confirmStop = true }
        }
    }

    @ViewBuilder
    private var syncRow: some View {
        switch owner.sync {
        case .idle:
            Label("Shared", systemImage: "checkmark.icloud")
        case .syncing:
            HStack { ProgressView(); Text("Sending changes…") }
        case .upToDate(let date):
            TimelineView(.periodic(from: .now, by: 30)) { _ in
                Label("Up to date · \(date, format: .relative(presentation: .named))", systemImage: "checkmark.icloud")
            }
        case .problem(let message):
            Label(message, systemImage: "exclamationmark.icloud").foregroundStyle(.orange)
        }
    }

    // MARK: Actions

    private var input: FamilyMirrorInput {
        FamilyShareInput.make(highlights: highlights, notes: notes, favorites: favorites,
                              ownerName: trimmedName, dedication: dedication,
                              translation: translation.isEmpty ? model.translationID : translation)
    }

    private func start() async {
        let input = input
        guard await owner.start(profile: input.profile, input: input) else { return }
        invite()
    }

    private func invite() {
        #if os(iOS)
        guard let share = owner.share else { return }
        FamilyShareInviter.shared.present(share: share, title: title)
        #endif
    }
}

#if os(iOS)
/// Presents the system sharing sheet for the owner's existing zone-wide share: invite people
/// (Messages, Mail, a link), see who has joined, remove someone, or stop sharing. Permissions
/// are fixed to private and read-only.
final class FamilyShareInviter: NSObject, UICloudSharingControllerDelegate {
    static let shared = FamilyShareInviter()
    private var title = ""

    func present(share: CKShare, title: String) {
        guard let container = FamilyCloud.container, let top = Self.topViewController() else { return }
        self.title = title
        let controller = UICloudSharingController(share: share, container: container)
        controller.availablePermissions = [.allowPrivate, .allowReadOnly]
        controller.delegate = self
        if let popover = controller.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        top.present(controller, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        var top = scene?.keyWindow?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }

    func itemTitle(for csc: UICloudSharingController) -> String? { title }

    func cloudSharingController(_ csc: UICloudSharingController, failedToSaveShareWithError error: any Error) {
        FamilySharingOwner.shared.note(error)
    }

    func cloudSharingControllerDidSaveShare(_ csc: UICloudSharingController) {
        Task { await FamilySharingOwner.shared.refresh(force: true) }
    }

    func cloudSharingControllerDidStopSharing(_ csc: UICloudSharingController) {
        FamilySharingOwner.shared.sharingSheetStopped()
    }
}
#endif
