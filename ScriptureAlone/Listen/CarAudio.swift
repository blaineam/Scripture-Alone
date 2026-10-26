#if os(iOS)
import AVFoundation

/// Whether the phone is playing into a car — a CarPlay head unit or a car's Bluetooth. In a car the audio is for
/// the car, so the Silent switch (which mutes Listen into the room) doesn't apply, and a route that goes away
/// there is the car being switched off, not headphones being pulled out.
@MainActor
enum CarAudio {
    /// Set by `CarPlaySceneDelegate` while a CarPlay screen is connected; the most reliable signal there is.
    static var carPlayConnected = false

    static func isCar(_ ports: [AVAudioSession.Port]) -> Bool { ports.contains(.carAudio) }

    static var isConnected: Bool {
        carPlayConnected || isCar(AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portType))
    }
}
#endif
