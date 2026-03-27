import SwiftUI

/// A view that presents a floating list of items, optionally accompanied by a title.
///
/// The list is styled as a rounded card with a subtle shadow. The spacing between the items
/// exposes the background color, creating a natural divider effect between each row.
public struct FloatingItemList<Content: View>: View {

    /// An optional title displayed above the list container.
    public var title: String?

    /// A view builder that provides the content of the list.
    @ViewBuilder public var content: () -> Content

    /// Creates a new floating item list.
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
                    .font(.subheadline) // Equivalent to bodyMedium
                    .fontWeight(.bold)
                    .foregroundColor(.secondary)
                    .padding(.leading, 10)
                    .padding(.top, 10)
                    .padding(.trailing, 10)
                    .padding(.bottom, 5) // Adjusted slightly to flow into the list naturally
            }

            VStack(spacing: 1) { // Spacing creates the divider effect
                content()
            }
                // The container background acting as the divider color (outlineVariant)
            .background(Color(UIColor.separator))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .shadow(
                color: Color.black.opacity(0.10),
                radius: 10,
                x: 0,
                y: 2
            )
            .padding(.leading, 10)
            .padding(.trailing, 10)
            .padding(.top, title == nil ? 10 : 5)
            .padding(.bottom, 15)
        }
    }
}
