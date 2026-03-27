import SwiftUI

/// A list item view that displays centered, italicized text in a secondary color.
///
/// This component is commonly used for informational footers, empty states, or subtle hints.
public struct FloatingItemCenteredText: View {

    /// The text to display, formatted as an `AttributedString`.
    public var text: AttributedString

    /// Creates a new centered text item using an `AttributedString`.
    ///
    /// - Parameter text: The formatted string to display centered and italicized.
    public init(text: AttributedString) {
        self.text = text
    }

    /// Creates a new centered text item using a standard `String`.
    ///
    /// - Parameter text: The string to display centered and italicized.
    public init(text: String) {
        self.text = AttributedString(text)
    }

    public var body: some View {
        FloatingItemContainer {
            Text(text)
                .font(.system(size: 15))
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .italic()
                .frame(maxWidth: .infinity, alignment: .center)
        }
    }
}
