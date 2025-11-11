// swift-tools-version:5.3
import PackageDescription

let package = Package(
   name: "Multipaz",
   platforms: [
     .iOS(.v14),
   ],
   products: [
      .library(name: "Multipaz", targets: ["Multipaz"])
   ],
   targets: [
      .binaryTarget(
         name: "Multipaz",
         url: "https://apps.multipaz.org/xcf/Multipaz-0.95.0.xcframework.zip",
         checksum:"5fcb46fc71b3e1895d2884a45b42ab6eff84f6459750ab285b987eebe8fccacb")
   ]
)
