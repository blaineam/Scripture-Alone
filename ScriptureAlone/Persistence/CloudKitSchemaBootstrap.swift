#if DEBUG && os(iOS) && !targetEnvironment(simulator)
import CloudKit
import CoreData
import SwiftData
import ScriptureAloneCore

/// Creates every CloudKit record type the app uses in the container's DEVELOPMENT environment,
/// so the schema can be deployed to production in the CloudKit Console. Record types otherwise
/// only appear after a development-signed build happens to save one of each — which a fresh
/// project never has.
///
/// Runs once per install on a development build run from Xcode on a real device (debug builds
/// talk to the development environment; TestFlight and App Store builds use production). Pass
/// `-bootstrapCloudKitSchema` to run it again. Results go to the Xcode console.
nonisolated enum CloudKitSchemaBootstrap {
    private static let doneKey = "debug.cloudKitSchemaBootstrapped.v1"

    static func runIfNeeded() {
        let forced = ProcessInfo.processInfo.arguments.contains("-bootstrapCloudKitSchema")
        guard forced || !UserDefaults.standard.bool(forKey: doneKey) else { return }
        Task.detached(priority: .utility) {
            let swiftData = initializeSwiftDataSchema()
            let family = await initializeFamilySchema()
            print("CloudKit schema bootstrap — SwiftData: \(swiftData), family sharing: \(family)")
            if swiftData == "ok", family == "ok" {
                UserDefaults.standard.set(true, forKey: doneKey)
                print("CloudKit schema bootstrap — done. Deploy the schema to production in the CloudKit Console.")
            }
        }
    }

    /// Apple's documented route for SwiftData: build the equivalent Core Data model and ask
    /// NSPersistentCloudKitContainer to push its CD_ record types, using a throwaway store.
    private static func initializeSwiftDataSchema() -> String {
        guard let model = NSManagedObjectModel.makeManagedObjectModel(for: [Highlight.self, Note.self, Favorite.self]) else {
            return "could not build the Core Data model"
        }
        let url = FileManager.default.temporaryDirectory.appending(path: "schema-bootstrap-\(UUID().uuidString).store")
        let description = NSPersistentStoreDescription(url: url)
        description.cloudKitContainerOptions = NSPersistentCloudKitContainerOptions(containerIdentifier: FamilyCloud.containerID)
        description.shouldAddStoreAsynchronously = false
        let container = NSPersistentCloudKitContainer(name: "SchemaBootstrap", managedObjectModel: model)
        container.persistentStoreDescriptions = [description]
        var loadError: (any Error)?
        container.loadPersistentStores { _, error in loadError = error }
        if let loadError { return "load failed: \(loadError.localizedDescription)" }
        defer {
            for store in container.persistentStoreCoordinator.persistentStores {
                try? container.persistentStoreCoordinator.remove(store)
            }
            try? FileManager.default.removeItem(at: url)
        }
        do {
            try container.initializeCloudKitSchema(options: [])
            return "ok"
        } catch {
            return "failed: \(error.localizedDescription)"
        }
    }

    /// Family sharing uses raw CloudKit record types; saving one fully-populated record of each
    /// in a throwaway zone creates them (with every field), then the zone is deleted.
    private static func initializeFamilySchema() async -> String {
        guard let database = FamilyCloud.container?.privateCloudDatabase else { return "not entitled" }
        let zoneID = CKRecordZone.ID(zoneName: "SchemaBootstrap", ownerName: CKCurrentUserDefaultName)
        let now = Date()
        let samples: [FamilyMirrorRecord] = [
            FamilyMirror.record(for: FamilyOwnerProfile(bibleID: UUID(), ownerName: "Schema", dedication: "Schema",
                                                        preferredTranslation: "ASV")),
            FamilyMirror.record(for: KeepsakeHighlight(verse: 43_003_016, color: "yellow", createdAt: now)),
            FamilyMirror.record(for: KeepsakeNote(title: "Schema", body: "Schema",
                                                  anchors: [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))],
                                                  createdAt: now, updatedAt: now, origin: "manual")),
            FamilyMirror.record(for: FamilyFavorite(id: UUID(), start: 43_003_016, end: 43_003_016, createdAt: now)),
        ]
        do {
            _ = try await database.modifyRecordZones(saving: [CKRecordZone(zoneID: zoneID)], deleting: [])
            let records = samples.map { sample -> CKRecord in
                let record = sample.ckRecord(in: zoneID)
                record[FamilyMirror.fingerprintField] = "schema" as NSString
                return record
            }
            let result = try await database.modifyRecords(saving: records, deleting: [], savePolicy: .allKeys)
            for (_, outcome) in result.saveResults { _ = try outcome.get() }
            _ = try await database.modifyRecordZones(saving: [], deleting: [zoneID])
            return "ok"
        } catch {
            return "failed: \(error.localizedDescription)"
        }
    }
}
#endif
