// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "AmemeIOS",
    platforms: [
        .iOS(.v18),
        .macOS(.v14),
    ],
    products: [
        .library(name: "AmemeShared", targets: ["AmemeShared"]),
        .executable(name: "AmemeApp", targets: ["AmemeApp"]),
        .executable(name: "AmemeSharedSmoke", targets: ["AmemeSharedSmoke"]),
        .executable(name: "AmemeLocalNodeSmoke", targets: ["AmemeLocalNodeSmoke"]),
    ],
    targets: [
        .target(
            name: "AmemeShared",
            path: "Ameme/Shared"
        ),
        .executableTarget(
            name: "AmemeApp",
            dependencies: ["AmemeShared"],
            path: "AmemeApp"
        ),
        .executableTarget(
            name: "AmemeSharedSmoke",
            dependencies: ["AmemeShared"],
            path: "Smoke"
        ),
        .executableTarget(
            name: "AmemeLocalNodeSmoke",
            dependencies: ["AmemeShared"],
            path: "LocalNodeSmoke"
        ),
        .testTarget(
            name: "AmemeSharedTests",
            dependencies: ["AmemeShared"],
            path: "Tests/AmemeSharedTests"
        ),
    ]
)
