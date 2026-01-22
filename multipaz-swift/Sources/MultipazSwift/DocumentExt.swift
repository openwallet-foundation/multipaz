import SwiftUI
@preconcurrency import Multipaz

extension Document {
    public func edit(
        editActionFn: @escaping @Sendable (_ editor: Editor) async -> Void
    ) async throws -> Void {
        return try await edit(
            editAction: EditorHandler(f: editActionFn)
        )
    }
    
    /// Renders card art for a ``Document``.
    ///
    /// If no card art is set then fallback card art will be generated which will include ``Document.displayName`` and ``Document.typeDisplayName``
    /// on top of a generic base image.
    ///
    /// - Returns: A ``UIImage``.
    public func renderCardArt() -> UIImage {
        if cardArt != nil {
            return UIImage(data: cardArt!.toNSData())!
        }
        let fallbackBaseImage = UIImage(named: "default_card_art", in: .module, with: nil)!
        let size = fallbackBaseImage.size
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            fallbackBaseImage.draw(at: .zero)
            if let displayName = self.displayName {
                let titleAttributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: 70, weight: .bold),
                    .foregroundColor: UIColor.white
                ]
                let titleString = NSAttributedString(string: displayName, attributes: titleAttributes)
                let titlePoint = CGPoint(
                    x: size.width * 0.05,
                    y: size.height * 0.35
                )
                titleString.draw(at: titlePoint)
            }
            
            if let typeDisplayName = self.typeDisplayName {
                let typeAttributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: 45, weight: .regular),
                    .foregroundColor: UIColor.white
                ]
                let typeString = NSAttributedString(string: typeDisplayName, attributes: typeAttributes)
                let typePoint = CGPoint(
                    x: size.width * 0.05,
                    y: size.height * 0.55
                )
                typeString.draw(at: typePoint)
            }
        }
        return image
    }
}

private class EditorHandler: KotlinSuspendFunction1 {
    let f: @Sendable (
        _ editor: Document.Editor
    ) async -> Void
    
    init(f: @escaping @Sendable (_ editor: Document.Editor) async -> Void) {
        self.f = f
    }

    func __invoke(p1: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let editor = p1 as! Document.Editor
        let f = self.f
        Task {
            let value = await f(editor)
            completionHandler(nil, nil)
        }
    }
}


