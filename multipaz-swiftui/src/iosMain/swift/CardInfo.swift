import UIKit

/// An interface for information about a card to be displayed in a list.
public protocol CardInfo {
    /// Unique identifier for the card.
    var identifier: String { get }
    /// Card art for the card.
    var cardArt: UIImage { get }
    /// Badges for the card.
    var badges: [CardBadge] { get }
}
