import SwiftUI
import Multipaz

struct FloatingItemListScreen: View {
    var body: some View {
        ScrollView {
            FloatingItemList(title: "FloatingItemText") {
                FloatingItemText(text: "Primary text")
                FloatingItemText(text: "Primary text", secondary: "Secondary text")
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
            }
            FloatingItemList(title: "FloatingItemCenteredText") {
                FloatingItemCenteredText(
                    text: "Nothing to see here, move along. " +
                          "This line is really long so should broken across at least two lines"
                )
            }
        }
    }
}

