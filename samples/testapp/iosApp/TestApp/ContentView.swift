import UIKit
import SwiftUI
import Multipaz

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
                .ignoresSafeArea(.keyboard) // Compose has own keyboard handler
                .onOpenURL(perform: { url in
                    if url.isFileURL {
                        if url.pathExtension.lowercased() == "mpzpass" {
                            processMpzpassFile(url)
                        } else {
                            print("Unhandled file extension: \(url.pathExtension)")
                        }
                        return
                    }
                    MainViewControllerKt.HandleUrl(url: url.absoluteString)
                })
    }
}

private func processMpzpassFile(_ url: URL) {
    guard url.startAccessingSecurityScopedResource() else {
        print("Error: Permission denied to access the mpzpass file at \(url.lastPathComponent)")
        return
    }
    
    defer {
        url.stopAccessingSecurityScopedResource()
    }
    
    do {
        let fileData = try Data(contentsOf: url)
        MainViewControllerKt.processMpzPassPayload(encodedMpzPass: fileData.toByteArray())
    } catch {
        print("Error reading mpzpass file: \(error.localizedDescription)")
    }
}




