// swift-tools-version: 5.7
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "VisionSDK",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        // Products define the executables and libraries a package produces, and make them visible to other packages.
        .library(
            name: "VisionSDK",
            targets: ["PXVisionSDK", "VisionSDK", "TensorFlow"]),
    ],
    dependencies: [
        // Dependencies declare other packages that this package depends on.
        // .package(url: /* package url */, from: "1.0.0"),
    ],
    targets: [
        // Targets are the basic building blocks of a package. A target can define a module or a test suite.
        // Targets can depend on other targets in this package, and on products in packages this package depends on.
        .target(
            name: "PXVisionSDK",
            dependencies: ["VisionSDK", "TensorFlow"],
            linkerSettings: [
                .linkedLibrary("c++")
            ]
        ),
        .binaryTarget(
                    name: "VisionSDK",
                    path: "./Sources/VisionSDK.xcframework"),
        .binaryTarget(
                    name: "TensorFlow",
                    path: "./Sources/TensorFlowLiteC.xcframework")
    ]
)
