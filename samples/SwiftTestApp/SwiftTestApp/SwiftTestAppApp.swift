import SwiftUI

@main
struct SwiftTestAppApp: App {
    
    var body: some Scene {
        WindowGroup {
            if ConsentGoldenScreenshotRunner.isGoldenRun {
                ConsentGoldenScreenshotHostView()
            } else {
                ContentView()
            }
        }
    }
}
