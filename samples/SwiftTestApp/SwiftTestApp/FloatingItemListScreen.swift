import SwiftUI
import Multipaz

struct FloatingItemListScreen: View {
    
    private let fiveDaysAgo: Date = Calendar.current.date(byAdding: .day, value: -5, to: Date())!
    private let fiveDaysFromNow: Date = Calendar.current.date(byAdding: .day, value: 5, to: Date())!
    
    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                SegmentedItemList(title: "Lazy list variant") {
                    NavigationLink(destination: LazyFloatingItemListScreen()) {
                        SegmentedItemText(
                            text: "LazySegmentedItemList (50 items)",
                            showChevron: true,
                            secondary: "Tap to open lazy-loaded scrolling list"
                        )
                    }
                    .buttonStyle(.plain)
                }

                SegmentedItemList(title: "SegmentedItemText") {
                    SegmentedItemText(text: "Primary text")
                    SegmentedItemText(text: "Primary text", showChevron: true)
                    SegmentedItemText(text: "Primary text", secondary: "Secondary text")
                    SegmentedItemText(text: "Primary text", showChevron: true, secondary: "Secondary text")
                    SegmentedItemText(text: "Primary text", secondary: "Secondary text, pay attention", secondaryColor: .red)
                    SegmentedItemText(text: "Primary text and image", image: { Image(systemName: "star") })
                    SegmentedItemText(text: "Primary text and image", showChevron: true, image: { Image(systemName: "star") })
                    SegmentedItemText(text: "Primary text and image", secondary: "Secondary text", image: { Image(systemName: "star") })
                    SegmentedItemText(text: "Primary text and image", showChevron: true, secondary: "Secondary text", image: { Image(systemName: "star") })
                    SegmentedItemText(
                        text: "Primary text and trailing content",
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    SegmentedItemText(
                        text: "Primary text and trailing content",
                        showChevron: true,
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    SegmentedItemText(
                        text: "Primary text and trailing content",
                        secondary: "Secondary text",
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    SegmentedItemText(
                        text: "Primary text and trailing content",
                        showChevron: true,
                        secondary: "Secondary text",
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    SegmentedItemText(
                        text: "Primary + image + trailing content",
                        image: { Image(systemName: "star") },
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    SegmentedItemText(
                        text: "Primary + image + trailing content",
                        secondary: "Secondary text",
                        image: { Image(systemName: "star") },
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    SegmentedItemText(
                        text: "Primary + image + trailing content",
                        showChevron: true,
                        secondary: "Secondary text",
                        image: { Image(systemName: "star") },
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                }
                SegmentedItemList(title: "SegmentedItemHeadingAndText") {
                    SegmentedItemHeadingAndText(heading: "Heading", text: "Text")
                    SegmentedItemHeadingAndText(heading: "Heading", text: "Text", showChevron: true)
                    SegmentedItemHeadingAndText(heading: "Heading with image", text: "Text", image: { Image(systemName: "star") })
                    SegmentedItemHeadingAndText(heading: "Heading with image", text: "Text", showChevron: true, image: { Image(systemName: "star") })
                    SegmentedItemHeadingAndText(
                        heading: "Heading + trailing",
                        text: "Text",
                        trailingContent: { Button(action: {}) { Image(systemName: "folder.badge.plus") } }
                    )
                    SegmentedItemHeadingAndText(
                        heading: "Heading + trailing",
                        text: "Text",
                        showChevron: true,
                        trailingContent: { Button(action: {}) { Image(systemName: "folder.badge.plus") } }
                    )
                    SegmentedItemHeadingAndText(
                        heading: "Heading + image + trailing",
                        text: "Text",
                        image: { Image(systemName: "star") },
                        trailingContent: { Button(action: {}) { Image(systemName: "folder.badge.plus") } }
                    )
                    SegmentedItemHeadingAndText(
                        heading: "Heading + image + trailing",
                        text: "Text",
                        showChevron: true,
                        image: { Image(systemName: "star") },
                        trailingContent: { Button(action: {}) { Image(systemName: "folder.badge.plus") } }
                    )
                    SegmentedItemHeadingAndContent(
                        heading: "SegmentedItemHeadingAndContent",
                        content: {
                            Image(.boardingPassUtopiaAirlines)
                                .resizable()
                                .scaledToFit()
                                .frame(height: 200)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                    )
                    SegmentedItemHeadingAndContent(
                        heading: "SegmentedItemHeadingAndContent (with chevron)",
                        showChevron: true,
                        content: {
                            Image(.boardingPassUtopiaAirlines)
                                .resizable()
                                .scaledToFit()
                                .frame(height: 200)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                    )
                    SegmentedItemHeadingAndDate(heading: "SegmentedItemHeadingAndDate", date: fiveDaysAgo)
                    SegmentedItemHeadingAndDate(heading: "SegmentedItemHeadingAndDate (with chevron)", date: fiveDaysAgo, showChevron: true)
                    SegmentedItemHeadingAndDate(heading: "SegmentedItemHeadingAndDate (full)", date: fiveDaysAgo, dateStyle: .full)
                    SegmentedItemHeadingAndDateTime(heading: "SegmentedItemHeadingAndDateTime", dateTime: fiveDaysFromNow)
                    SegmentedItemHeadingAndDateTime(heading: "SegmentedItemHeadingAndDateTime (with chevron)", dateTime: fiveDaysFromNow, showChevron: true)
                    SegmentedItemHeadingAndDateTime(heading: "SegmentedItemHeadingAndDateTime (full)", dateTime: fiveDaysFromNow, dateStyle: .full, timeStyle: .full)
                }
                SegmentedItemList(title: "SegmentedItemCenteredText") {
                    SegmentedItemCenteredText(
                        text: "Nothing to see here, move along. " +
                        "This line is really long so should broken across at least two lines"
                    )
                    SegmentedItemCenteredText(
                        text: "Centered text with chevron",
                        showChevron: true
                    )
                }
                SegmentedItemList {
                    SegmentedItemCenteredText(
                        text: "Titleless SegmentedItemList"
                    )
                }
            }
            .padding(16)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("SegmentedItemList examples")
    }
}


