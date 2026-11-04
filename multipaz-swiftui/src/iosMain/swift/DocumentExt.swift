
extension MultipazDocument {
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
        _ editor: MultipazDocument.Editor
    ) async -> Void

    init(f: @escaping @Sendable (_ editor: MultipazDocument.Editor) async -> Void) {
        self.f = f
    }

    func __invoke(p1: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let editor = p1 as! MultipazDocument.Editor
        let f = self.f
        Task {
            let value = await f(editor)
            completionHandler(nil, nil)
        }
    }
}
