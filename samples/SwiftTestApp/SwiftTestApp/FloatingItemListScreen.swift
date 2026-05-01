import SwiftUI
import Multipaz

struct FloatingItemListScreen: View {
    
    private let fiveDaysAgo: Date = Calendar.current.date(byAdding: .day, value: -5, to: Date())!
    private let fiveDaysFromNow: Date = Calendar.current.date(byAdding: .day, value: 5, to: Date())!
    
    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                FloatingItemList(title: "FloatingItemText") {
                    FloatingItemText(text: "Primary text")
                    FloatingItemText(text: "Primary text", secondary: "Secondary text")
                    FloatingItemText(text: "Primary text", secondary: "Secondary text, pay attention", secondaryColor: .red)
                    FloatingItemText(text: "Primary text and image", image: { Image(systemName: "star") })
                    FloatingItemText(text: "Primary text and image", secondary: "Secondary text", image: { Image(systemName: "star") })
                    FloatingItemText(
                        text: "Primary text and trailing content",
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    FloatingItemText(
                        text: "Primary text and trailing content",
                        secondary: "Secondary text",
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    FloatingItemText(
                        text: "Primary + image + trailing content",
                        image: { Image(systemName: "star") },
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                    FloatingItemText(
                        text: "Primary + image + trailing content",
                        secondary: "Secondary text",
                        image: { Image(systemName: "star") },
                        trailingContent: { Button(action: {}) { Image(systemName: "square.and.arrow.up") } }
                    )
                }
                FloatingItemList(title: "FloatingItemHeadingAndText") {
                    FloatingItemHeadingAndText(heading: "Heading", text: "Text")
                    FloatingItemHeadingAndText(heading: "Heading with image", text: "Text", image: { Image(systemName: "star") })
                    FloatingItemHeadingAndText(
                        heading: "Heading + trailing",
                        text: "Text",
                        trailingContent: { Button(action: {}) { Image(systemName: "folder.badge.plus") } }
                    )
                    FloatingItemHeadingAndText(
                        heading: "Heading + image + trailing",
                        text: "Text",
                        image: { Image(systemName: "star") },
                        trailingContent: { Button(action: {}) { Image(systemName: "folder.badge.plus") } }
                    )
                    FloatingItemHeadingAndContent(
                        heading: "FloatingItemHeadingAndContent",
                        content: {
                            Image(.boardingPassUtopiaAirlines)
                                .resizable()
                                .scaledToFit()
                                .frame(height: 200)
                            .clipShape(RoundedRectangle(cornerRadius: 10))                    }
                    )
                    FloatingItemHeadingAndDate(heading: "FloatingItemHeadingAndDate", date: fiveDaysAgo)
                    FloatingItemHeadingAndDate(heading: "FloatingItemHeadingAndDate (full)", date: fiveDaysAgo, dateStyle: .full)
                    FloatingItemHeadingAndDateTime(heading: "FloatingItemHeadingAndDateTime", dateTime: fiveDaysFromNow)
                    FloatingItemHeadingAndDateTime(heading: "FloatingItemHeadingAndDateTime (full)", dateTime: fiveDaysFromNow, dateStyle: .full, timeStyle: .full)
                }
                FloatingItemList(title: "FloatingItemCenteredText") {
                    FloatingItemCenteredText(
                        text: "Nothing to see here, move along. " +
                        "This line is really long so should broken across at least two lines"
                    )
                }
                FloatingItemList {
                    FloatingItemCenteredText(
                        text: "Titleless FloatingItemList"
                    )
                }
            }
            .padding(10)
        }
        .navigationTitle("FloatingItemList examples")
    }
}

