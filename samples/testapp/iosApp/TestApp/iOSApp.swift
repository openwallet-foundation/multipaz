import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

// Hack to work around the "shared source" problem. This will break if we
// ever call any Swift functions to load resources in the MultipazSwift
// framework but that's unlikely since this is mostly Kotlin anyway.
//
extension Foundation.Bundle {
    static var module: Bundle = main
}
