import SwiftUI

/// A list item view that displays a bold heading with primary text below it,
/// an optional leading image, and an optional trailing view.
public struct FloatingItemHeadingAndText<ImageView: View, TrailingView: View>: View {

    /// The heading string displayed in a bold font above the primary text.
    public var heading: String

    /// The primary text to display below the heading, formatted as an `AttributedString`.
    public var text: AttributedString

    /// A view builder that creates the leading image or icon.
    @ViewBuilder public var image: () -> ImageView

    /// A view builder that creates the trailing content, pushed to the rightmost edge.
    @ViewBuilder public var trailingContent: () -> TrailingView

    /// Creates a new floating item with a heading and an `AttributedString` body.
    ///
    /// - Parameters:
    ///   - heading: The bold heading to display.
    ///   - text: The primary text to display below the heading.
    ///   - image: A view builder that provides a leading image or icon. Defaults to an `EmptyView`.
    ///   - trailingContent: A view builder that provides a trailing view. Defaults to an `EmptyView`.
    public init(
        heading: String,
        text: AttributedString,
        @ViewBuilder image: @escaping () -> ImageView = { EmptyView() },
        @ViewBuilder trailingContent: @escaping () -> TrailingView = { EmptyView() }
    ) {
        self.heading = heading
        self.text = text
        self.image = image
        self.trailingContent = trailingContent
    }

    /// Creates a new floating item with a heading and a standard `String` body.
    ///
    /// - Parameters:
    ///   - heading: The bold heading to display.
    ///   - text: The primary string to display below the heading.
    ///   - image: A view builder that provides a leading image or icon. Defaults to an `EmptyView`.
    ///   - trailingContent: A view builder that provides a trailing view. Defaults to an `EmptyView`.
    public init(
        heading: String,
        text: String,
        @ViewBuilder image: @escaping () -> ImageView = { EmptyView() },
        @ViewBuilder trailingContent: @escaping () -> TrailingView = { EmptyView() }
    ) {
        self.init(heading: heading, text: AttributedString(text), image: image, trailingContent: trailingContent)
    }

    public var body: some View {
        FloatingItemContainer {
            HStack(alignment: .center, spacing: 16) {
                image()

                VStack(alignment: .leading, spacing: 4) {
                    Text(heading)
                        .font(.system(size: 15))
                        .fontWeight(.bold)

                    Text(text)
                        .font(.system(size: 15))
                        .textSelection(.enabled)
                }

                // Pushes the trailing content to the rightmost edge
                Spacer(minLength: 0)

                trailingContent()
            }
        }
    }
}
