package org.multipaz.prompt

/** A value that encodes the result of a PIN or passphrase attempt. */
sealed class PassphraseEvaluation {
    /** Correct PIN or passphrase entered. */
    data object OK: PassphraseEvaluation()
    /** Incorrect PIN or passphrase entered, user can try again. */
    data object TryAgain: PassphraseEvaluation()
    /** Incorrect PIN or passphrase entered, user can try again given number of times. */
    data class TryAgainAttemptsRemain(val remainingAttempts: Int): PassphraseEvaluation()
    /** Incorrect PIN or passphrase entered, no more attempts possible. */
    data object TooManyAttempts: PassphraseEvaluation()
}