#if os(iOS)
import CarPlay
import ScriptureAloneCore
import SwiftData
import UIKit

/// Turns `CarPlayEntry` lists into CarPlay's templates, and carries out what a row does. Apple's audio templates
/// only: tabs, lists and Now Playing — nothing custom, nothing to read while driving.
@MainActor
final class CarPlayTemplates {
    private let interface: CPInterfaceController
    private let player: CarPlayPlayer
    private let limit: Int
    private let favorites: () -> [VerseRange]

    init(interface: CPInterfaceController, player: CarPlayPlayer = .shared,
         limit: Int = CPListTemplate.maximumItemCount, favorites: (() -> [VerseRange])? = nil) {
        self.interface = interface
        self.player = player
        self.limit = limit
        self.favorites = favorites ?? { IntentLibrary.favorites().compactMap(\.range) }
    }

    // MARK: Templates

    func root() -> CPTabBarTemplate {
        let listen = list(title: String(localized: "Listen", comment: "CarPlay tab: what to read aloud now"),
                          entries: CarPlayCatalog.listen(today: player.todaysReading, location: player.location),
                          image: "headphones")
        let books = list(title: String(localized: "Books", comment: "CarPlay tab: the Bible's books"),
                         entries: CarPlayCatalog.testaments(limit: limit), image: "books.vertical")
        let display: (VerseRange) -> String = { range in
            (self.player.model.numbering.nativeRange(range) ?? range).display
        }
        let saved = list(title: String(localized: "Favorites", comment: "CarPlay tab: the reader's favorite verses"),
                         entries: CarPlayCatalog.favorites(favorites(), display: display, limit: limit), image: "heart")
        return CPTabBarTemplate(templates: [listen, books, saved])
    }

    func list(title: String, entries: [CarPlayEntry], image: String? = nil) -> CPListTemplate {
        let items: [CPListItem] = entries.map { entry in
            let item = CPListItem(text: entry.title, detailText: entry.detail)
            if case .open = entry.action { item.accessoryType = .disclosureIndicator }
            item.handler = { [weak self] _, completion in
                MainActor.assumeIsolated { self?.choose(entry) }
                completion()
            }
            return item
        }
        let section = CPListSection(items: items)
        let template = CPListTemplate(title: title, sections: entries.isEmpty ? [] : [section])
        template.tabTitle = title
        if let image { template.tabImage = UIImage(systemName: image) }
        if entries.isEmpty {
            template.emptyViewSubtitleVariants = [String(localized: "Nothing here yet", comment: "CarPlay list with no rows, such as no favorites")]
        }
        return template
    }

    // MARK: Choosing

    func choose(_ entry: CarPlayEntry) {
        switch entry.action {
        case .open(let title, let entries):
            interface.pushTemplate(list(title: title, entries: entries), animated: true, completion: nil)
        default:
            switch player.perform(entry.action) {
            case .started:
                showNowPlaying()
            case .unavailable(let message):
                interface.presentTemplate(
                    CPAlertTemplate(titleVariants: [message],
                                    actions: [CPAlertAction(title: String(localized: "OK", comment: "CarPlay alert button"), style: .default) { [weak self] _ in
                                        self?.interface.dismissTemplate(animated: true, completion: nil)
                                    }]),
                    animated: true, completion: nil)
            }
        }
    }

    func showNowPlaying() {
        let now = CPNowPlayingTemplate.shared
        now.updateNowPlayingButtons([CPNowPlayingPlaybackRateButton { [weak self] _ in
            MainActor.assumeIsolated { self?.player.cycleSpeed() }
        }])
        if interface.topTemplate !== now { interface.pushTemplate(now, animated: true, completion: nil) }
    }
}

/// CarPlay's scene: when the phone is connected to a car, the app shows its audio screens there.
@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var templates: CarPlayTemplates?

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didConnect interfaceController: CPInterfaceController) {
        CarAudio.carPlayConnected = true
        let templates = CarPlayTemplates(interface: interfaceController)
        self.templates = templates
        interfaceController.setRootTemplate(templates.root(), animated: false, completion: nil)
    }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        CarAudio.carPlayConnected = false
        templates = nil
    }
}
#endif
