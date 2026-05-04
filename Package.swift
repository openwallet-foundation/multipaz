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
            url: "https://apps.multipaz.org/xcf/Multipaz-0.99.0.xcframework.zip",
            checksum:"6ced70ab39b040dd8e2b887e92f8a82afeb3b8ac61b9f93c7cec345c949a762e"
         )
   ]
)
