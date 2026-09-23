import SwiftUI
import ScriptureAloneCore

/// The compact glass bar shown while listening: transport, speed, voice and sleep timer.
struct NowPlayingBar: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var voices: [VoiceOption] = []
    #if os(iOS)
    @State private var studioVoices: [MiSpeaksClient.Voice] = []
    @State private var studioAvailability: MiSpeaksClient.Availability = .notInstalled
    #endif

    private var listen: ListenController { ListenController.shared }

    static let speeds: [Double] = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]

    var body: some View {
        VStack(spacing: 6) {
            if let notice = listen.notice {
                noticeRow(notice)
            }
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(listen.nowPlayingTitle)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(status)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)

                transport
                speedMenu
                optionsMenu
                Button { listen.stop() } label: {
                    Image(systemName: "xmark").font(.subheadline.weight(.semibold)).frame(width: 28, height: 32)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel("Stop Listening")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: 560)
        .glassEffect(.regular, in: .rect(cornerRadius: 24))
        .onAppear(perform: refreshVoices)
        .onChange(of: scenePhase) { if scenePhase == .active { refreshVoices() } }
    }

    private var status: String {
        switch listen.phase {
        case .preparing(let message): return message
        case .paused: return String(localized: "Paused", comment: "Read-aloud status")
        case .playing, .idle:
            let voice = listen.engine == .miSpeaks ? "Mi Speaks" : (voices.first { $0.id == listen.voiceID }?.name ?? String(localized: "System Voice", comment: "Read-aloud engine using the device's built-in voices"))
            return String(localized: "\(voice) · \(Self.speedLabel(listen.speed))", comment: "Read-aloud status. %1$@ is a voice name; %2$@ is the speed, e.g. “1×”.")
        }
    }

    private func noticeRow(_ notice: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "info.circle").foregroundStyle(.secondary)
            Text(notice).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
            #if os(iOS)
            if studioAvailability == .notInstalled, listen.engine == .miSpeaks {
                Button("Get Mi Speaks") { openURL(MiSpeaksClient.appStoreURL) }
                    .font(.caption.weight(.semibold))
            }
            #endif
            Button { listen.notice = nil } label: { Image(systemName: "xmark.circle.fill") }
                .buttonStyle(.plain)
                .foregroundStyle(.tertiary)
                .accessibilityLabel("Dismiss")
        }
    }

    private var transport: some View {
        HStack(spacing: 10) {
            Button { listen.previousVerse() } label: { Image(systemName: "backward.fill").frame(width: 28, height: 32) }
                .accessibilityLabel("Previous Verse")
            Group {
                if case .preparing = listen.phase {
                    ProgressView().controlSize(.small).frame(width: 32, height: 32)
                        .accessibilityLabel("Preparing")
                } else {
                    Button { listen.togglePlayPause() } label: {
                        Image(systemName: listen.isPlaying ? "pause.fill" : "play.fill")
                            .font(.title3)
                            .contentTransition(.symbolEffect(.replace))
                            .frame(width: 32, height: 32)
                    }
                    .accessibilityLabel(listen.isPlaying ? "Pause" : "Play")
                }
            }
            Button { listen.nextVerse() } label: { Image(systemName: "forward.fill").frame(width: 28, height: 32) }
                .accessibilityLabel("Next Verse")
        }
        .buttonStyle(.plain)
    }

    private var speedMenu: some View {
        Menu {
            Picker("Speed", selection: Binding(get: { listen.speed }, set: { listen.speed = $0 })) {
                ForEach(Self.speeds, id: \.self) { Text(Self.speedLabel($0)).tag($0) }
            }
        } label: {
            Text(Self.speedLabel(listen.speed))
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .frame(minWidth: 36, minHeight: 32)
        }
        .menuIndicator(.hidden)
        .fixedSize()
        .accessibilityLabel("Speed, \(Self.speedLabel(listen.speed))")
    }

    private var optionsMenu: some View {
        Menu {
            #if os(iOS)
            Picker("Engine", selection: Binding(get: { listen.engine }, set: { listen.engine = $0 })) {
                ForEach(ListenEngine.allCases) { Text($0.title).tag($0) }
            }
            if listen.engine == .miSpeaks {
                studioSection
            } else {
                systemVoiceSection
            }
            #else
            systemVoiceSection
            #endif
            Toggle("Continue to Next Chapter", isOn: Binding(get: { listen.continueChapters },
                                                             set: { listen.continueChapters = $0 }))
            Picker("Sleep Timer", selection: Binding(get: { listen.sleepTimer }, set: { listen.setSleepTimer($0) })) {
                ForEach(SleepTimer.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.menu)
        } label: {
            Image(systemName: listen.sleepTimer == .off ? "ellipsis.circle" : "moon.zzz.fill")
                .font(.title3)
                .frame(width: 32, height: 32)
        }
        .menuIndicator(.hidden)
        .fixedSize()
        .accessibilityLabel("Voice and Options")
    }

    @ViewBuilder private var systemVoiceSection: some View {
        Picker("Voice", selection: Binding(get: { listen.voiceID ?? "" }, set: { pick($0) })) {
            Text("Automatic").tag("")
            ForEach(voices) { Text($0.title).tag($0.id) }
        }
        .pickerStyle(.menu)
        if !SpeechVoices.personalVoiceAuthorized {
            Button {
                Task {
                    if await SpeechVoices.requestPersonalVoice() {
                        voices = await SpeechVoices.available()
                        if let personal = voices.first(where: { $0.kind == .personal }) { listen.voiceID = personal.id }
                    } else {
                        listen.notice = String(localized: "Personal Voice wasn’t allowed. You can change this in Settings › Accessibility › Personal Voice.", comment: "“Settings › Accessibility › Personal Voice” should match the system Settings app's menu names.")
                    }
                }
            } label: {
                Label("Use My Personal Voice…", systemImage: "person.wave.2")
            }
        }
    }

    #if os(iOS)
    @ViewBuilder private var studioSection: some View {
        switch studioAvailability {
        case .ready:
            Picker("Studio Voice", selection: Binding(get: { listen.studioVoiceID ?? "" },
                                                      set: { listen.studioVoiceID = $0.isEmpty ? nil : $0 })) {
                Text("Mi Speaks Default").tag("")
                ForEach(studioVoices) { Text($0.name).tag($0.id) }
            }
            .pickerStyle(.menu)
        case .notInstalled:
            Button { openURL(MiSpeaksClient.appStoreURL) } label: {
                Label("Get Mi Speaks on the App Store", systemImage: "arrow.down.app")
            }
        case .notSubscribed, .noSharedContainer, .translationNotPermitted:
            Text(studioAvailability.explanation)
        }
    }
    #endif

    private func pick(_ id: String) {
        listen.voiceID = id.isEmpty ? nil : id
    }

    private func refreshVoices() {
        Task { voices = await SpeechVoices.available() }
        #if os(iOS)
        studioAvailability = MiSpeaksClient.availability(for: model.source?.info)
        studioVoices = MiSpeaksClient.publishedVoices()
        #endif
    }

    static func speedLabel(_ speed: Double) -> String {
        speed.formatted(.number.precision(.fractionLength(0...2))) + "×"
    }
}
