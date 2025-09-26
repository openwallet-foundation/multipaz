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
         url: "https://apps.multipaz.org/xcf/Multipaz-0.94.0.xcframework.zip",
         checksum:"be13ce53e69b0788956241d136623e65fb5cd49965ac5afd68db9e878acf8211")
   ]
)
