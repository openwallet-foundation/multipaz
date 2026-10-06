
public enum PromptModelError: Error {
    case unexpectedType(expected: String, actual: Any.Type)
}

extension PromptModel {
    public func requestPassphrase(
        reason: Reason,
        passphraseConstraints: PassphraseConstraints,
        passphraseEvaluatorFn: @escaping @Sendable (
            _ enteredPassphrase: String
        ) async -> PassphraseEvaluation?
    ) async throws -> String {
        return try await self.requestPassphrase(
            reason: reason,
            passphraseConstraints: passphraseConstraints,
            passphraseEvaluator: PassphraseEvalulatorHandler(f: passphraseEvaluatorFn)
        )
    }

    @MainActor
    public func convertToHumanReadable(
        reason: Reason,
        passphraseConstraints: PassphraseConstraints?
    ) async throws -> ReasonHumanReadable {
        let result = try await self.toHumanReadable.invoke(
            p1: reason,
            p2: passphraseConstraints
        )
        guard let humanReadable = result as? ReasonHumanReadable else {
            throw PromptModelError.unexpectedType(
                expected: "ReasonHumanReadable",
                actual: type(of: result)
            )
        }
        return humanReadable
    }

    public func showFaceMatcherPrompt(
        matcher: any FaceMatcher,
        referencePortrait: ByteString
    ) async throws -> KotlinBoolean {
        return try await self.showFaceMatcherPrompt(
            matcher: matcher,
            referencePortrait: referencePortrait,
            reason: FaceMatchingReason.shared
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
