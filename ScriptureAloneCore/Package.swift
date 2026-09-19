// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "ScriptureAloneCore",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "ScriptureAloneCore", targets: ["ScriptureAloneCore"])
    ],
    targets: [
        .target(
            name: "ScriptureAloneCore",
            swiftSettings: [.treatAllWarnings(as: .error)]
        ),
        .testTarget(
            name: "ScriptureAloneCoreTests",
            dependencies: ["ScriptureAloneCore"]
        ),
    ]
)
