import SwiftUI

/// A list item view that displays a bold heading with a date below it,
/// an optional leading image, and an optional trailing view.
public struct FloatingItemHeadingAndDate<ImageView: View, TrailingView: View>: View {

    /// The heading string displayed in a bold font above the primary text.
    public var heading: String

    /// The date to show or `nil`.
    public var date: Date?

    /// Whether to show a right chevron icon on the right side.
    public var showChevron: Bool

    private let text: String

    /// The time zone to use.
    public var timeZone: Foundation.TimeZone

    /// The style of formatting to apply.
    public var dateStyle: DateFormatter.Style

    /// A view builder that creates the leading image or icon.
    @ViewBuilder public var image: () -> ImageView

    /// A view builder that creates the trailing content, pushed to the rightmost edge.
    @ViewBuilder public var trailingContent: () -> TrailingView

    /// Creates a new floating item with a heading and date.
    ///
    /// - Parameters:
    ///   - heading: The bold heading to display.
    ///   - date: The date to display below the heading.
    ///   - showChevron: Whether to show a right chevron icon on the right side. Defaults to `false`.
    ///   - timeZone: The time zone to use.
    ///   - dateStyle: The style of formatting to apply.
    ///   - image: A view builder that provides a leading image or icon. Defaults to an `EmptyView`.
    ///   - trailingContent: A view builder that provides a trailing view. Defaults to an `EmptyView`.
    public init(
        heading: String,
        date: Date?,
        showChevron: Bool = false,
        timeZone: Foundation.TimeZone = TimeZone.current,
        dateStyle: DateFormatter.Style = .medium,
        @ViewBuilder image: @escaping () -> ImageView = { EmptyView() },
        @ViewBuilder trailingContent: @escaping () -> TrailingView = { EmptyView() }
    ) {
        self.heading = heading
        self.date = date
        self.showChevron = showChevron
        self.timeZone = timeZone
        self.dateStyle = dateStyle
        self.image = image
        self.trailingContent = trailingContent

        if date == nil {
            text = "Not set"
        } else {
            let formatter = DateFormatter()
            formatter.dateStyle = dateStyle
            formatter.timeZone = timeZone
            text = formatter.string(from: date!)
        }
    }

    public var body: some View {
        FloatingItemHeadingAndText(
            heading: heading,
            text: text,
            showChevron: showChevron,
            image: image,
            trailingContent: trailingContent
        )
    }
}
