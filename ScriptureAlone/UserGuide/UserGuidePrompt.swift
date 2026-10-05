import SwiftUI
import ScriptureAloneCore

/// The Settings row that opens the guide (Aa › User Guide).
struct UserGuideRow: View {
    @State private var showing = false

    var body: some View {
        Button { showing = true } label: {
            Label("User Guide", systemImage: "book.pages")
        }
        .sheet(isPresented: $showing) {
            UserGuideView().userGuideSheetSize()
        }
    }
}

extension View {
    /// The guide's size as a Mac sheet, like the app's other sheets.
    func userGuideSheetSize() -> some View {
        #if os(macOS)
        frame(minWidth: 560, idealWidth: 720, minHeight: 640, idealHeight: 820)
        #else
        self
        #endif
    }

    /// Offers the guide once per install, a moment after the reader appears.
    /// - Parameter blocked: something else is up (a sheet, the inspector, a keepsake) — the offer
    ///   waits for the next launch rather than stacking on it.
    func userGuidePrompt(blocked: Bool) -> some View {
        modifier(UserGuidePromptModifier(blocked: blocked))
    }
}

private struct UserGuidePromptModifier: ViewModifier {
    let blocked: Bool
    @State private var showPrompt = false
    @State private var showGuide = false
    @State private var readChosen = false
    @State private var attempted = false

    func body(content: Content) -> some View {
        content
            // One try per launch, after the reader has settled. Anything else that comes up first
            // (a sheet, the inspector, a keepsake) wins, and the offer waits for the next launch.
            .task(id: blocked) {
                guard !attempted else { return }
                if blocked {
                    attempted = true
                    return
                }
                try? await Task.sleep(for: .seconds(1.5))
                guard !Task.isCancelled else { return }
                attempted = true
                guard Self.shouldOffer else { return }
                UserDefaults.standard.set(true, forKey: UserGuidePrompt.shownKey)
                showPrompt = true
            }
            // A link or intent arriving while it's up takes precedence.
            .onChange(of: AppCommandCenter.shared.openedFromOutside) {
                if AppCommandCenter.shared.openedFromOutside { showPrompt = false }
            }
            .sheet(isPresented: $showPrompt, onDismiss: {
                if readChosen {
                    readChosen = false
                    showGuide = true
                }
            }) {
                UserGuidePromptCard(read: {
                    readChosen = true
                    showPrompt = false
                }, skip: { showPrompt = false })
            }
            .sheet(isPresented: $showGuide) {
                UserGuideView().userGuideSheetSize()
            }
    }

    private static var shouldOffer: Bool {
        var automated = UserGuidePrompt.isTestRun
        #if DEBUG
        automated = automated || ScreenshotScene.current != nil || UITestMode.isOn
        #endif
        return UserGuidePrompt.shouldOffer(
            alreadyShown: UserDefaults.standard.bool(forKey: UserGuidePrompt.shownKey),
            openedForSomethingElse: AppCommandCenter.shared.openedFromOutside,
            automated: automated)
    }
}

/// The small first-launch card: what the guide is, and the way to it later.
struct UserGuidePromptCard: View {
    let read: () -> Void
    let skip: () -> Void
    @State private var height: CGFloat = 380

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "book.pages.fill")
                .font(.system(size: 30, weight: .regular))
                .foregroundStyle(GuideStyle.accent)
                .frame(width: 64, height: 64)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(GuideStyle.accentSoft))
                .accessibilityHidden(true)
                .padding(.bottom, 2)
            Text("Welcome to Scripture Alone", comment: "Title of the one-time card offering the User Guide")
                .font(.title2.bold())
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text("An illustrated guide shows everything the app can do — reading, study, notes and more.",
                 comment: "Body of the one-time card offering the User Guide")
                .font(GuideStyle.serif(.body))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            VStack(spacing: 6) {
                Button(action: read) {
                    Text("Read the Guide", comment: "Button on the one-time card that opens the User Guide")
                        .foregroundStyle(GuideStyle.onAccent)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .keyboardShortcut(.defaultAction)
                Button(action: skip) {
                    Text("Skip", comment: "Button on the one-time User Guide card that dismisses it")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderless)
                .controlSize(.large)
                .keyboardShortcut(.cancelAction)
            }
            .padding(.top, 6)
            Text("You can open it any time from Aa › User Guide.",
                 comment: "Footnote on the one-time card; “Aa” is the appearance button, “User Guide” the row in it")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 28)
        .padding(.top, 30)
        .padding(.bottom, 22)
        .frame(maxWidth: 440)
        .tint(GuideStyle.accent)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        #if os(iOS)
        .presentationDetents([.height(height)])
        .presentationDragIndicator(.hidden)
        #else
        .frame(width: 420)
        #endif
        .presentationSizing(.fitted)
    }
}

#if DEBUG
/// `-userGuideOnly` / `-userGuidePromptOnly`: the guide or its first-launch card with no reader
/// behind it, for checking their layout without any Bible text on screen.
enum UserGuideDebugRoot {
    static var guideOnly: Bool { ProcessInfo.processInfo.arguments.contains("-userGuideOnly") }
    static var promptOnly: Bool { ProcessInfo.processInfo.arguments.contains("-userGuidePromptOnly") }
}

struct UserGuideDebugRootView: View {
    @State private var showPrompt = true
    @State private var showGuide = false
    @State private var readChosen = false

    var body: some View {
        if UserGuideDebugRoot.guideOnly {
            UserGuideView()
        } else {
            Color.secondary.opacity(0.15)
                .ignoresSafeArea()
                .sheet(isPresented: $showPrompt, onDismiss: { showGuide = readChosen }) {
                    UserGuidePromptCard(read: { readChosen = true; showPrompt = false },
                                        skip: { showPrompt = false })
                }
                .sheet(isPresented: $showGuide) { UserGuideView().userGuideSheetSize() }
        }
    }
}
#endif
