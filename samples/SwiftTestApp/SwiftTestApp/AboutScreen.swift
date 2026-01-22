import SwiftUI
import Multipaz
import MultipazSwift

struct AboutScreen: View {
    @Environment(ViewModel.self) private var viewModel
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("This is a test app for the multipaz-swift library.")
                Text("Multipaz version \(Platform.shared.version).")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding()
        .navigationTitle("About")
    }
}
