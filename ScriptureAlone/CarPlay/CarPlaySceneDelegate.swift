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
        installNowPlayingButtons()
        if interface.topTemplate !== now { interface.pushTemplate(now, animated: true, completion: nil) }
    }

    /// The speed button. The system's own rate button reads its label from a media player's rate, and a spoken
    /// reading has none (it showed "0×"), so this is an image button the app draws: it says the speed and steps it.
    /// Installed when the car connects too, since Siri can start a reading without any row being chosen.
    func installNowPlayingButtons() {
        let button = CPNowPlayingImageButton(image: Self.speedImage(player.speedLabel)) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.player.cycleSpeed()
                self?.installNowPlayingButtons()
            }
        }
        CPNowPlayingTemplate.shared.updateNowPlayingButtons([button])
    }

    /// "1.25×" as a template image, which CarPlay tints to match the other buttons. CarPlay scales the image to its
    /// button, so the canvas hugs the text: any room around it would shrink the letters.
    static func speedImage(_ label: String) -> UIImage {
        let text = NSAttributedString(string: label, attributes: [
            .font: UIFont.systemFont(ofSize: 60, weight: .bold), .foregroundColor: UIColor.black,
        ])
        let box = text.size()
        let size = CGSize(width: ceil(box.width) + 4, height: ceil(box.height))
        let image = UIGraphicsImageRenderer(size: size).image { _ in
            text.draw(at: CGPoint(x: 2, y: 0))
        }
        return image.withRenderingMode(.alwaysTemplate)
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
        templates.installNowPlayingButtons()
    }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        CarAudio.carPlayConnected = false
        templates = nil
    }
}
#endif
