// swift-tools-version:6.2
import PackageDescription

let package = Package(
   name: "Multipaz",
   platforms: [
    .iOS(.v26),
   ],
   products: [
      .library(name: "Multipaz", targets: ["Multipaz"]),
   ],
   targets: [
        .binaryTarget(
            name: "Multipaz",
            path: "xcframework/build/XCFrameworks/release/Multipaz.xcframework"
         )
   ]
)
