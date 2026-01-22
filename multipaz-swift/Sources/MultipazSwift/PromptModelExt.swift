@preconcurrency import Multipaz

extension PromptModel {
    public func requestPassphrase(
        title: String,
        subtitle: String,
        passphraseConstraints: PassphraseConstraints,
        passphraseEvaluatorFn: @escaping @Sendable (
            _ enteredPassphrase: String,
        ) async -> PassphraseEvaluation?
    ) async throws -> String {
        return try await self.requestPassphrase(
            title: title,
            subtitle: subtitle,
            passphraseConstraints: passphraseConstraints,
            passphraseEvaluator: PassphraseEvalulatorHandler(f: passphraseEvaluatorFn)
        )
    }
}

private class PassphraseEvalulatorHandler: KotlinSuspendFunction1 {
    let f: @Sendable (
        _ enteredPassphrase: String
    ) async -> PassphraseEvaluation?
    
    init(f: @escaping @Sendable (_ enteredPassphrase: String) async -> PassphraseEvaluation?) {
        self.f = f
    }

    func __invoke(p1: Any?, completionHandler: @escaping @Sendable (Any?, (any Error)?) -> Void) {
        let enteredPassphrase = p1 as! String
        let f = self.f
        Task {
            let value = await f(enteredPassphrase)
            completionHandler(value, nil)
        }
    }
}
