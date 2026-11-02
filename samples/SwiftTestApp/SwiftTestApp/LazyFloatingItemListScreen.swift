import SwiftUI
import Multipaz

struct LazyFloatingItemListScreen: View {
    @State private var showingAlert = false
    @State private var alertMessage = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("This screen contains text at the top, a segmented list in the middle with 50 items, and a button at the bottom to illustrate that the whole screen scrolls.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)

                LazySegmentedItemList(
                    count: 50,
                    title: "LazySegmentedItemList (50 items)"
                ) { index in
                    if index % 3 == 0 {
                        SegmentedItemHeadingAndText(
                            heading: "Heading #\(index)",
                            text: "This is item \(index) in a LazySegmentedItemList with a total of 50 items.",
                            showChevron: true
                        )
                    } else if index % 3 == 1 {
                        SegmentedItemText(
                            text: "Item #\(index)",
                            showChevron: true,
                            secondary: "Secondary detail for item \(index)",
                            image: { Image(systemName: "star") }
                        )
                    } else {
                        SegmentedItemText(
                            text: "Item #\(index)",
                            showChevron: false
                        )
                    }
                }

                Button(action: {
                    alertMessage = "Button clicked!"
                    showingAlert = true
                }) {
                    Text("Button at bottom of screen")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
                .padding(.bottom, 16)
            }
            .padding(16)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("LazySegmentedItemList")
        .alert("Alert", isPresented: $showingAlert) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(alertMessage)
        }
    }
}

