// swift-tools-version:6.2
import PackageDescription

let package = Package(
   name: "Multipaz",
   platforms: [
    .iOS(.v26),
   ],
   products: [
      .library(name: "Multipaz", targets: ["MultipazSwift"])
   ],
   targets: [
        .target(
            name: "MultipazSwift",
            dependencies: ["Multipaz"],
            path: "multipaz-swift/Sources/MultipazSwift",
            resources: [
                .process("Resources/default_card_art.png")
            ]
        ),
        .binaryTarget(
            name: "Multipaz",
            url: "https://apps.multipaz.org/xcf/Multipaz-0.97.0.xcframework.zip",
            checksum:"900a7d08e4931d0b5d462743bf5c6416409d3a36fb6c84f0fd453991ed121892"
        )
   ]
)
