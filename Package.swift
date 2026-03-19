// swift-tools-version:6.2
import PackageDescription

let package = Package(
   name: "Multipaz",
   platforms: [
    .iOS(.v26),
   ],
   products: [
      .library(name: "Multipaz", targets: ["MultipazSwift"]),
      .library(name: "MultipazCore", targets: ["Multipaz"])
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
            url: "https://apps.multipaz.org/xcf/Multipaz-0.98.0.xcframework.zip",
            checksum:"cf31660f72b010a4ef9ee1c3e2c6e86759be6be60f008357637e43bb5387e1f5"
         )
   ]
)
