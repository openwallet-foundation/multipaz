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
            url: "https://apps.multipaz.org/xcf/Multipaz-0.100.0.xcframework.zip",
            checksum:"6098070b02dfe416f27146b9ca43d7867182caf93d5f872aaf560c1af9764452"
         )
   ]
)
