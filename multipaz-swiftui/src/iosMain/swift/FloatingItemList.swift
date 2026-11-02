import SwiftUI

/// A view that presents a segmented list of items, optionally accompanied by a title.
///
/// The list is displayed as a vertical stack of segmented containers with `16pt` outer corners,
/// `4pt` inner corners between adjacent items, and a `2pt` vertical gap.
///
/// See ``SegmentedItemHeadingAndText``, ``SegmentedItemText``, and ``SegmentedItemCenteredText``
/// for items that are normally used inside this container. For a custom view, wrap it in
/// ``SegmentedItemContainer``.
public struct SegmentedItemList<Content: View>: View {

    /// An optional title displayed above the list container.
    public var title: String?

    /// A view builder that provides the content of the list.
    @ViewBuilder public var content: () -> Content

    /// Creates a new segmented item list.
    ///
    /// - Parameters:
    ///   - title: An optional title string to display above the list. Defaults to `nil`.
    ///   - content: A view builder that provides the items to be displayed in the list.
    public init(title: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let title = title {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .padding(.bottom, 8)
            }

            VStack(spacing: segmentGap) {
                content()
            }
            .clipShape(RoundedRectangle(cornerRadius: segmentOuterCornerRadius, style: .continuous))
        }
    }
}

/// A view that presents a floating list of items, optionally accompanied by a title.
@available(*, deprecated, renamed: "SegmentedItemList")
public struct FloatingItemList<Content: View>: View {

    /// An optional title displayed above the list container.
    public var title: String?

    /// A view builder that provides the content of the list.
    @ViewBuilder public var content: () -> Content

    /// Creates a new floating item list.
    public init(title: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.content = content
    }

    public var body: some View {
        SegmentedItemList(title: title, content: content)
    }
}

