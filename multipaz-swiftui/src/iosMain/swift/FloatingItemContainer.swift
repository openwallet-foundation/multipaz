import SwiftUI

/// A generic container that applies the standard list item styling.
///
/// Use this container to wrap custom content so it visually aligns with other `FloatingItem`
/// components. It applies a standard background color, full-width frame, and uniform padding.
public struct FloatingItemContainer<Content: View>: View {

    /// A view builder that provides the content of the container.
    public let content: () -> Content

    /// Creates a new floating item container.
    ///
    /// - Parameter content: A view builder that generates the child views to display inside the container.
    public init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    public var body: some View {
        ZStack(alignment: .leading) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        // Equivalent to MaterialTheme.colorScheme.surfaceContainerLowest
        .background(Color(UIColor.systemBackground))
    }
}