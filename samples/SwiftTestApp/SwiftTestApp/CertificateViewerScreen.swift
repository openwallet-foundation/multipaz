import SwiftUI
import Multipaz

struct CertificateViewerScreen: View {
    let certificates: [X509Cert]

    var body: some View {
        Group {
            if certificates.isEmpty {
                Text("No certificates available")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    X509CertViewer(certificates: certificates)
                        .padding(16)
                }
            }
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("Certificate Viewer")
    }
}


