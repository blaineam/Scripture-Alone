// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "ScriptureAloneCore",
    defaultLocalization: "en",
    platforms: [.iOS(.v26), .macOS(.v26), .watchOS(.v26)],
    products: [
        .library(name: "ScriptureAloneCore", targets: ["ScriptureAloneCore"])
    ],
    targets: [
        .target(
            name: "ScriptureAloneCore",
            resources: [.process("Resources")],
            swiftSettings: [.treatAllWarnings(as: .error)]
        ),
        .testTarget(
            name: "ScriptureAloneCoreTests",
            dependencies: ["ScriptureAloneCore"]
        ),
    ]
)
