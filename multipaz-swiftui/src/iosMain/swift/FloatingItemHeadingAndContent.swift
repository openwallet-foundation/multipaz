import SwiftUI

/// A list item view that displays a bold heading with content below it,
/// an optional leading image, and an optional trailing view.
public struct FloatingItemHeadingAndContent<ContentView: View, ImageView: View, TrailingView: View>: View {

    /// The heading string displayed in a bold font above the primary text.
    public var heading: String

    /// Whether to show a right chevron icon on the right side.
    public var showChevron: Bool

    /// A view builder that creates the content.
    @ViewBuilder public var content: () -> ContentView

    /// A view builder that creates the leading image or icon.
    @ViewBuilder public var image: () -> ImageView

    /// A view builder that creates the trailing content, pushed to the rightmost edge.
    @ViewBuilder public var trailingContent: () -> TrailingView

    /// Creates a new floating item with a heading and content.
    ///
    /// - Parameters:
    ///   - heading: The bold heading to display.
    ///   - showChevron: Whether to show a right chevron icon on the right side. Defaults to `false`.
    ///   - content: A view builder that provides the content.
    ///   - image: A view builder that provides a leading image or icon. Defaults to an `EmptyView`.
    ///   - trailingContent: A view builder that provides a trailing view. Defaults to an `EmptyView`.
    public init(
        heading: String,
        showChevron: Bool = false,
        @ViewBuilder content: @escaping () -> ContentView,
        @ViewBuilder image: @escaping () -> ImageView = { EmptyView() },
        @ViewBuilder trailingContent: @escaping () -> TrailingView = { EmptyView() }
    ) {
        self.heading = heading
        self.showChevron = showChevron
        self.content = content
        self.image = image
        self.trailingContent = trailingContent
    }

    public var body: some View {
        FloatingItemContainer(showChevron: showChevron) {
            HStack(alignment: .center, spacing: 16) {
                image()

                VStack(alignment: .leading, spacing: 4) {
                    Text(heading)
                        .font(.system(size: 15))
                        .fontWeight(.bold)

                    content()
                }

                // Pushes the trailing content to the rightmost edge
                Spacer(minLength: 0)

                trailingContent()
            }
        }
    }
}
