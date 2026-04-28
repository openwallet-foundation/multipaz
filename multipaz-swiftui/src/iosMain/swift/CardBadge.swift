import SwiftUI
import UIKit

/// A badge to be displayed on a card.
public struct CardBadge: Hashable, Sendable {
    /// the text to display.
    public let text: String
    /// the color of the badge.
    public let color: Color
    
    public init(text: String, color: Color) {
        self.text = text
        self.color = color
    }
}

/// A view that renders a list of ``CardBadge``s.
public struct CardBadgesView: View {
    let badges: [CardBadge]
    
    public init(badges: [CardBadge]) {
        self.badges = badges
    }
    
    public var body: some View {
        VStack(alignment: .trailing, spacing: 8) {
            ForEach(badges, id: \.text) { badge in
                Text(badge.text)
                    .font(Font.system(.footnote).weight(.bold).smallCaps())
                    .foregroundColor(badge.color.isLight ? .black : .white)
                    .shadow(color: .black.opacity(0.4), radius: 1, x: 0, y: 1)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(
                        Capsule()
                            .fill(badge.color)
                            .shadow(color: .black.opacity(0.4), radius: 8, x: 0, y: 4)
                    )
            }
        }
        .padding(12)
    }
}

extension Color {
    var isLight: Bool {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        
        // Use UIColor to get components as SwiftUI.Color doesn't provide them easily across platforms/versions
        UIColor(self).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        
        let luminance = 0.299 * red + 0.587 * green + 0.114 * blue
        return luminance > 0.5
    }
}
