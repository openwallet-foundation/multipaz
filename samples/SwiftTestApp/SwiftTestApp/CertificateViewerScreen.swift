import SwiftUI
import Multipaz
import MultipazSwift

struct CertificateViewerScreen: View {
    let certificates: [X509Cert]
        
    // State to track the current page in the TabView
    @State private var currentPage: Int = 0
    
    var body: some View {
        CertificateViewerInternal(
            certificates: certificates,
            currentPage: $currentPage
        )
        .background(Color(uiColor: .secondarySystemFill))
    }
}

private struct CertificateViewerInternal: View {
    let certificates: [X509Cert]
    @Binding var currentPage: Int
    
    var body: some View {
        ZStack(alignment: .bottom) {
            VStack {
                if certificates.isEmpty {
                    Text("No certificates available")
                } else {
                    TabView(selection: $currentPage) {
                        ForEach(0..<certificates.count, id: \.self) { index in
                            ScrollView {
                                X509CertViewer(certificate: certificates[index])
                                    .tag(index)
                            }
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                }
            }
            .padding(
                .bottom,
                certificates.count > 1 ? (30 + 8) : 0
            )
            if certificates.count > 1 {
                HStack(spacing: 4) {
                    ForEach(0..<certificates.count, id: \.self) { index in
                        Circle()
                            .fill(
                                index == currentPage
                                ? Color.blue
                                : Color.primary.opacity(0.2)
                            )
                            .frame(width: 8, height: 8)
                    }
                }
                .frame(height: 30)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 8)
            }
        }
    }
}

