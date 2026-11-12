import SwiftUI

/// A view that presents a lazy-loaded segmented list of items.
///
/// Each item is lazily rendered as it scrolls into the viewport.
/// The first item has `16pt` top corners, the last item has `16pt` bottom corners,
/// and intermediate corners use `4pt` inner corners with a `2pt` gap.
public struct LazySegmentedItemList<Data: RandomAccessCollection, ID: Hashable, RowContent: View>: View {
    public var title: String?
    public var data: Data
    public var id: KeyPath<Data.Element, ID>
    @ViewBuilder public var content: (Data.Element) -> RowContent

    public init(
        _ data: Data,
        id: KeyPath<Data.Element, ID>,
        title: String? = nil,
        @ViewBuilder content: @escaping (Data.Element) -> RowContent
    ) {
        self.data = data
        self.id = id
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

            LazyVStack(spacing: segmentGap) {
                let count = data.count
                ForEach(Array(data.enumerated()), id: \.offset) { index, item in
                    let shape = segmentedItemShape(index: index, count: count)
                    content(item)
                        .background(Color(UIColor.secondarySystemGroupedBackground))
                        .clipShape(shape)
                }
            }
        }
    }
}

extension LazySegmentedItemList where ID == Data.Element.ID, Data.Element: Identifiable {
    public init(
        _ data: Data,
        title: String? = nil,
        @ViewBuilder content: @escaping (Data.Element) -> RowContent
    ) {
        self.init(data, id: \.id, title: title, content: content)
    }
}

extension LazySegmentedItemList where Data == Range<Int>, ID == Int {
    public init(
        count: Int,
        title: String? = nil,
        @ViewBuilder content: @escaping (Int) -> RowContent
    ) {
        self.init(0..<count, id: \.self, title: title, content: content)
    }
}

/// A view that presents a lazy-loaded floating list of items.
@available(*, deprecated, renamed: "LazySegmentedItemList")
public struct LazyFloatingItemList<Data: RandomAccessCollection, ID: Hashable, RowContent: View>: View {
    public var title: String?
    public var data: Data
    public var id: KeyPath<Data.Element, ID>
    @ViewBuilder public var content: (Data.Element) -> RowContent

    public init(
        _ data: Data,
        id: KeyPath<Data.Element, ID>,
        title: String? = nil,
        @ViewBuilder content: @escaping (Data.Element) -> RowContent
    ) {
        self.data = data
        self.id = id
        self.title = title
        self.content = content
    }

    public var body: some View {
        LazySegmentedItemList(data, id: id, title: title, content: content)
    }
}

@available(*, deprecated, renamed: "LazySegmentedItemList")
extension LazyFloatingItemList where ID == Data.Element.ID, Data.Element: Identifiable {
    public init(
        _ data: Data,
        title: String? = nil,
        @ViewBuilder content: @escaping (Data.Element) -> RowContent
    ) {
        self.init(data, id: \.id, title: title, content: content)
    }
}

@available(*, deprecated, renamed: "LazySegmentedItemList")
extension LazyFloatingItemList where Data == Range<Int>, ID == Int {
    public init(
        count: Int,
        title: String? = nil,
        @ViewBuilder content: @escaping (Int) -> RowContent
    ) {
        self.init(0..<count, id: \.self, title: title, content: content)
    }
}

