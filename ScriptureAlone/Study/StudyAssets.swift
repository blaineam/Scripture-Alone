import Foundation
import ScriptureAloneCore

/// The two study databases, which ship inside the app.
///
/// **Why they are not downloaded.** They were On-Demand Resources, and ODR ties a pack to an app
/// *version*: Apple's own guidance is that "resources are tied to a specific app version as part of
/// the app's submission, and the system does not have any notion of understanding whether the files
/// in an asset pack are identical across app versions." So every update re-downloaded 55 MB of
/// databases that had not changed a byte.
///
/// Apple-hosted Background Assets fixes that, and is the mechanism Apple intends — but it costs a
/// downloader extension, a shared app group, three `BA*` Info.plist keys, an asset-pack manifest
/// per database and an upload pipeline separate from the build, and every one of those is validated
/// only at App Store delivery. Bundling the files instead costs about 48 MB of download once. The
/// App Store patches updates, so a database that has not changed is not sent again — which was the
/// whole point. Two files in a Resources folder buy the same outcome as all of that machinery.
///
/// Reading never depended on these anyway: all three translations, the maps, the timeline and the
/// cross references are in the app, so a fresh install reads scripture offline with no network.
enum StudyPack: String, CaseIterable, Sendable {
    case commentary
    case interlinear

    /// The database's base name in the app bundle.
    var file: String {
        switch self {
        case .commentary: "Study"
        case .interlinear: "Interlinear"
        }
    }

    var title: String {
        switch self {
        case .commentary: "Commentary"
        case .interlinear: "Original Languages"
        }
    }

    /// The database inside the app bundle. Nil only if the resource was dropped from the target,
    /// which is a build error rather than anything a reader can act on.
    var url: URL? { Bundle.main.url(forResource: file, withExtension: "sqlite") }
}
