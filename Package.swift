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
             path: "xcframework/build/XCFrameworks/release/Multipaz.xcframework"
         )
   ]
)
