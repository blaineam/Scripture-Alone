import Foundation
import CloudKit
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Silent pushes for shared Bibles: one CKDatabaseSubscription on the shared database.
enum FamilySharePush {
    static let subscriptionID = "family-shared-bibles"

    static func register() {
        #if os(iOS)
        UIApplication.shared.registerForRemoteNotifications()
        #else
        NSApplication.shared.registerForRemoteNotifications()
        #endif
    }

    /// True when the notification is ours (and a refresh was run).
    static func handle(_ userInfo: [AnyHashable: Any]) async -> Bool {
        guard let notification = CKNotification(fromRemoteNotificationDictionary: userInfo),
              notification.subscriptionID == subscriptionID else { return false }
        await SharedBibleLibrary.shared.refresh()
        return true
    }
}

#if os(iOS)
/// SwiftUI has no scene hook for accepting a CloudKit share, so the app installs this delegate
/// (via `UIApplicationDelegateAdaptor`) and hands each scene `FamilyShareSceneDelegate`.
final class FamilyShareAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        // Cold launch from an invitation: the metadata arrives with the connection options.
        if let metadata = options.cloudKitShareMetadata { accept(metadata) }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = FamilyShareSceneDelegate.self
        return configuration
    }

    func application(_ application: UIApplication, userDidAcceptCloudKitShareWith cloudKitShareMetadata: CKShare.Metadata) {
        accept(cloudKitShareMetadata)
    }

    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        await FamilySharePush.handle(userInfo) ? .newData : .noData
    }

    private func accept(_ metadata: CKShare.Metadata) {
        Task { await SharedBibleLibrary.shared.accept(metadata) }
    }
}

final class FamilyShareSceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, userDidAcceptCloudKitShareWith cloudKitShareMetadata: CKShare.Metadata) {
        Task { await SharedBibleLibrary.shared.accept(cloudKitShareMetadata) }
    }
}
#else
/// The native macOS build (compiled, not shipped) accepts shares through the app delegate.
final class FamilyShareAppDelegate: NSObject, NSApplicationDelegate {
    func application(_ application: NSApplication, userDidAcceptCloudKitShareWith metadata: CKShare.Metadata) {
        Task { await SharedBibleLibrary.shared.accept(metadata) }
    }

    func application(_ application: NSApplication, didReceiveRemoteNotification userInfo: [String: Any]) {
        Task { _ = await FamilySharePush.handle(userInfo) }
    }
}
#endif
