import SwiftUI

/// A view that presents a lazy-loaded floating list of items.
///
/// Each item is lazily rendered as it scrolls into the viewport.
/// The first item has rounded top corners, the last item has rounded bottom corners,
/// and intermediate items have square corners.
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
        VStack(alignment: .leading, spacing: 0) {
            if let title = title {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 8)
            }

            LazyVStack(spacing: 0) {
                let count = data.count
                ForEach(Array(data.enumerated()), id: \.offset) { index, item in
                    let isFirst = index == 0
                    let isLast = index == count - 1
                    let shape = UnevenRoundedRectangle(
                        topLeadingRadius: isFirst ? 16 : 0,
                        bottomLeadingRadius: isLast ? 16 : 0,
                        bottomTrailingRadius: isLast ? 16 : 0,
                        topTrailingRadius: isFirst ? 16 : 0,
                        style: .continuous
                    )

                    VStack(spacing: 0) {
                        content(item)

                        if !isLast {
                            Divider()
                                .background(Color(UIColor.separator))
                        }
                    }
                    .background(Color(UIColor.secondarySystemGroupedBackground))
                    .clipShape(shape)
                    .background {
                        shape
                            .fill(Color(UIColor.secondarySystemGroupedBackground))
                            .shadow(
                                color: Color.black.opacity(0.12),
                                radius: 12,
                                x: 0,
                                y: 3
                            )
                            .mask(PerimeterShadowMask(isFirst: isFirst, isLast: isLast))
                    }
                }
            }
        }
    }
}

private struct PerimeterShadowMask: Shape {
    let isFirst: Bool
    let isLast: Bool

    func path(in rect: CGRect) -> SwiftUI.Path {
        let top: CGFloat = isFirst ? -500 : 0
        let bottom: CGFloat = isLast ? rect.height + 500 : rect.height
        let left: CGFloat = -500
        let right: CGFloat = rect.width + 500

        var path = SwiftUI.Path()
        path.addRect(CGRect(x: left, y: top, width: right - left, height: bottom - top))
        return path
    }
}

extension LazyFloatingItemList where ID == Data.Element.ID, Data.Element: Identifiable {
    public init(
        _ data: Data,
        title: String? = nil,
        @ViewBuilder content: @escaping (Data.Element) -> RowContent
    ) {
        self.init(data, id: \.id, title: title, content: content)
    }
}

extension LazyFloatingItemList where Data == Range<Int>, ID == Int {
    public init(
        count: Int,
        title: String? = nil,
        @ViewBuilder content: @escaping (Int) -> RowContent
    ) {
        self.init(0..<count, id: \.self, title: title, content: content)
    }
}
