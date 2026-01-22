import SwiftUI
import Multipaz
import MultipazSwift

struct PassphrasePromptScreen: View {
    @Environment(ViewModel.self) private var viewModel

    @State private var numTries = 0
    
    private func showPassphrasePrompt(
        constraint: PassphraseConstraints,
        expectedPassphrase: String,
        unlimitedAttempts: Bool = false,
        checkDelay: Duration = .zero
    ) {
        let kfType = if constraint.requireNumerical { "PIN" } else { "passphrase" }
        self.numTries = 0
        
        Task {
            do {
                let promptModel = try await PromptModel.Companion.shared.get()
                print("PromptModel.get() from Swift: \(promptModel)")
                let passphrase = try await promptModel.requestPassphrase(
                    title: "Verify knowledge factor",
                    subtitle: "Enter your \(kfType) to continue. It's '\(expectedPassphrase)' but also try entering something else to see an error message",
                    passphraseConstraints: constraint,
                    passphraseEvaluatorFn: { enteredPassphrase in
                        print("numTries=\(await numTries) and passphrase: \(enteredPassphrase)")
                        try! await Task.sleep(nanoseconds: UInt64(checkDelay.toMilliseconds()) * 1_000_000)
                        if enteredPassphrase == expectedPassphrase {
                            return PassphraseEvaluation.OK()
                        }
                        if unlimitedAttempts {
                            return PassphraseEvaluation.TryAgain()
                        } else {
                            if await self.numTries == 3 {
                                return PassphraseEvaluation.TooManyAttempts()
                            }
                            let numRemain = await 3 - self.numTries
                            self.numTries = await self.numTries + 1
                            return PassphraseEvaluation.TryAgainAttemptsRemain(remainingAttempts: Int32(numRemain))
                            
                        }
                    })
                print("Knowledge factor entered: \(passphrase)")
            } catch {
                print("Prompt dismissed!")
            }
        }
    }
    
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PIN_FOUR_DIGITS,
                        expectedPassphrase: "1234"
                    )
                }) {
                    Text("4-digit PIN")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PIN_FOUR_DIGITS,
                        expectedPassphrase: "1234",
                        checkDelay: .seconds(2)
                    )
                }) {
                    Text("4-digit PIN (two secs per check)")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PIN_FOUR_DIGITS,
                        expectedPassphrase: "1234",
                        unlimitedAttempts: true
                    )
                }) {
                    Text("4-digit PIN (unlimited attempts)")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PIN_FOUR_DIGITS_OR_LONGER,
                        expectedPassphrase: "12345"
                    )
                }) {
                    Text("4-digit PIN or longer")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PIN_SIX_DIGITS,
                        expectedPassphrase: "123456"
                    )
                }) {
                    Text("6-digit PIN")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PIN_SIX_DIGITS_OR_LONGER,
                        expectedPassphrase: "1234567"
                    )
                }) {
                    Text("6-digit PIN or longer")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PASSPHRASE_FOUR_CHARS,
                        expectedPassphrase: "abcd"
                    )
                }) {
                    Text("4-character passphrase")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PASSPHRASE_FOUR_CHARS_OR_LONGER,
                        expectedPassphrase: "abcde"
                    )
                }) {
                    Text("4-character passphrase or longer")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PASSPHRASE_SIX_CHARS,
                        expectedPassphrase: "abcdef"
                    )
                }) {
                    Text("6-character passphrase")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.PASSPHRASE_SIX_CHARS_OR_LONGER,
                        expectedPassphrase: "abcdefg"
                    )
                }) {
                    Text("6-character passphrase or longer")
                }
                
                Button(action: {
                    showPassphrasePrompt(
                        constraint: PassphraseConstraints.companion.NONE,
                        expectedPassphrase: "multipaz.org"
                    )
                }) {
                    Text("No constraints")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Passphrase Prompt")
        .padding()
    }
}
