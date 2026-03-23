
extension Document {
    public func edit(
        editActionFn: @escaping @Sendable (_ editor: Editor) async -> Void
    ) async throws -> Void {
        return try await edit(
            editAction: EditorHandler(f: editActionFn)
        )
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
