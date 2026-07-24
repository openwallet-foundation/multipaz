import SwiftUI

/// A list item view that displays centered, italicized text in a secondary color.
///
/// This component is commonly used for informational footers, empty states, or subtle hints.
public struct FloatingItemCenteredText: View {

    /// The text to display, formatted as an `AttributedString`.
    public var text: AttributedString

    /// Whether to show a right chevron icon on the right side.
    public var showChevron: Bool

    /// Creates a new centered text item using an `AttributedString`.
    ///
    /// - Parameters:
    ///   - text: The formatted string to display centered and italicized.
    ///   - showChevron: Whether to show a right chevron icon on the right side. Defaults to `false`.
    public init(text: AttributedString, showChevron: Bool = false) {
        self.text = text
        self.showChevron = showChevron
    }

    /// Creates a new centered text item using a standard `String`.
    ///
    /// - Parameters:
    ///   - text: The string to display centered and italicized.
    ///   - showChevron: Whether to show a right chevron icon on the right side. Defaults to `false`.
    public init(text: String, showChevron: Bool = false) {
        self.init(text: AttributedString(text), showChevron: showChevron)
    }

    public var body: some View {
        FloatingItemContainer(showChevron: showChevron) {
            Text(text)
                .font(.system(size: 15))
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .italic()
                .frame(maxWidth: .infinity, alignment: .center)
        }
    }
}
