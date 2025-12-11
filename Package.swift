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
         url: "https://apps.multipaz.org/xcf/Multipaz-0.96.0.xcframework.zip",
         checksum:"93946e1644f3ebd524014ae996a4d0f5c78b5be83adaeec6751a6be624d446e2")
   ]
)
