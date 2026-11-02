import SwiftUI

/// Outer corner radius for the first and last item in a segmented list.
public let segmentOuterCornerRadius: CGFloat = 16

/// Inner corner radius where adjacent items in a segmented list meet.
public let segmentInnerCornerRadius: CGFloat = 4

/// Vertical gap between adjacent items in a segmented list.
public let segmentGap: CGFloat = 2

/// Returns the `UnevenRoundedRectangle` for an item at `index` in a segmented list of `count` items,
/// with `16pt` outer corners on the first/last item and `4pt` inner corners between adjacent items.
///
/// - Parameters:
///   - index: The zero-based index of the item in the list.
///   - count: The total number of items in the list.
public func segmentedItemShape(
    index: Int,
    count: Int
) -> UnevenRoundedRectangle {
    let isFirst = index == 0
    let isLast = index == count - 1
    return UnevenRoundedRectangle(
        topLeadingRadius: isFirst ? segmentOuterCornerRadius : segmentInnerCornerRadius,
        bottomLeadingRadius: isLast ? segmentOuterCornerRadius : segmentInnerCornerRadius,
        bottomTrailingRadius: isLast ? segmentOuterCornerRadius : segmentInnerCornerRadius,
        topTrailingRadius: isFirst ? segmentOuterCornerRadius : segmentInnerCornerRadius,
        style: .continuous
    )
}

/// A generic container that applies the standard segmented list item styling (rounded corners,
/// grouped cell surface background, full-width frame, and uniform padding).
///
/// Use this container to wrap custom content inside a ``SegmentedItemList`` or ``LazySegmentedItemList``,
/// or standalone with explicit `index` and `count` parameters.
public struct SegmentedItemContainer<Content: View>: View {

    /// Whether to show a right chevron icon on the right side.
    public let showChevron: Bool

    /// Whether to draw a selection border around the container.
    public let selected: Bool

    /// The shape used to clip the container.
    public let shape: UnevenRoundedRectangle

    /// A view builder that provides the content of the container.
    public let content: () -> Content

    /// Creates a new segmented item container for use inside a ``SegmentedItemList``.
    ///
    /// - Parameters:
    ///   - showChevron: Whether to show a right chevron icon on the right side. Defaults to `false`.
    ///   - selected: Whether to draw a selection border around the container. Defaults to `false`.
    ///   - shape: The shape used to clip the container. Defaults to `4pt` rounded corners, which
    ///     combines with ``SegmentedItemList``'s outer `16pt` clip to form outer/inner segmented corners.
    ///   - content: A view builder that generates the child views to display inside the container.
    public init(
        showChevron: Bool = false,
        selected: Bool = false,
        shape: UnevenRoundedRectangle = UnevenRoundedRectangle(
            topLeadingRadius: segmentInnerCornerRadius,
            bottomLeadingRadius: segmentInnerCornerRadius,
            bottomTrailingRadius: segmentInnerCornerRadius,
            topTrailingRadius: segmentInnerCornerRadius,
            style: .continuous
        ),
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.showChevron = showChevron
        self.selected = selected
        self.shape = shape
        self.content = content
    }

    /// Creates a new segmented item container for an item at `index` out of `count` items.
    ///
    /// - Parameters:
    ///   - index: The zero-based index of the item in the segmented group.
    ///   - count: The total number of items in the segmented group.
    ///   - showChevron: Whether to show a right chevron icon on the right side. Defaults to `false`.
    ///   - selected: Whether to draw a selection border around the container. Defaults to `false`.
    ///   - content: A view builder that generates the child views to display inside the container.
    public init(
        index: Int,
        count: Int,
        showChevron: Bool = false,
        selected: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.init(
            showChevron: showChevron,
            selected: selected,
            shape: segmentedItemShape(index: index, count: count),
            content: content
        )
    }

    public var body: some View {
        HStack(spacing: 8) {
            ZStack(alignment: .leading) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if showChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 16)
        .frame(minHeight: 44)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(shape)
        .overlay {
            if selected {
                shape.stroke(Color.accentColor, lineWidth: 1)
            }
        }
    }
}

/// A generic container that applies the standard list item styling.
@available(*, deprecated, renamed: "SegmentedItemContainer")
public struct FloatingItemContainer<Content: View>: View {

    /// Whether to show a right chevron icon on the right side.
    public let showChevron: Bool

    /// A view builder that provides the content of the container.
    public let content: () -> Content

    /// Creates a new floating item container.
    public init(
        showChevron: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.showChevron = showChevron
        self.content = content
    }

    public var body: some View {
        SegmentedItemContainer(showChevron: showChevron, content: content)
    }
}
