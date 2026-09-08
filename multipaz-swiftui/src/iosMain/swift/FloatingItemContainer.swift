import SwiftUI

/// A generic container that applies the standard list item styling.
///
/// Use this container to wrap custom content so it visually aligns with other `FloatingItem`
/// components. It applies a standard background color, full-width frame, and uniform padding.
public struct FloatingItemContainer<Content: View>: View {

    /// Whether to show a right chevron icon on the right side.
    public let showChevron: Bool

    /// A view builder that provides the content of the container.
    public let content: () -> Content

    /// Creates a new floating item container.
    ///
    /// - Parameters:
    ///   - showChevron: Whether to show a right chevron icon on the right side. Defaults to `false`.
    ///   - content: A view builder that generates the child views to display inside the container.
    public init(
        showChevron: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.showChevron = showChevron
        self.content = content
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
        // Equivalent to MaterialTheme.colorScheme.surfaceContainerLowest / iOS grouped cell background
        .background(Color(UIColor.secondarySystemGroupedBackground))
    }
}