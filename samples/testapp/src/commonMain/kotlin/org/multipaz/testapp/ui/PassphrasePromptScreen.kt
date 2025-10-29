package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.securearea.PassphraseConstraints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.multipaz.compose.passphrase.PassphrasePromptBottomSheet
import org.multipaz.prompt.PassphraseEvaluation

@Composable
fun PassphrasePromptScreen(
    showToast: (message: String) -> Unit
) {
    val showPrompt = remember {
        mutableStateOf<Pair<PassphraseConstraints, String>?>(null)
    }

    if (showPrompt.value != null) {
        val constraints = showPrompt.value!!.first
        val expectedPassphrase = showPrompt.value!!.second
        val numWrongTries = remember { mutableStateOf(0) }
        showPassphrasePrompt(
            constraints = constraints,
            expectedPassphrase = expectedPassphrase,
            onDismissRequest = {
                showPrompt.value = null
            },
            onPassphraseEntered = { passphrase ->
                if (passphrase == expectedPassphrase) {
                    showPrompt.value = null
                    if (constraints.requireNumerical) {
                        showToast("The expected PIN was entered")
                    } else {
                        showToast("The expected passphrase was entered")
                    }
                    PassphraseEvaluation.OK
                } else {
                    val numAttemptsLeft = 3 - numWrongTries.value
                    if (numAttemptsLeft == 0) {
                        if (constraints.requireNumerical) {
                            showToast("Too many wrong PINs attempted")
                        } else {
                            showToast("Too many wrong passphrases attempted")
                        }
                        showPrompt.value = null
                        PassphraseEvaluation.OK
                    } else {
                        numWrongTries.value = numWrongTries.value + 1
                        PassphraseEvaluation.TryAgainAttemptsRemain(numAttemptsLeft)
                    }
                }
            })
    }
    
    LazyColumn(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            TextButton(
                onClick = { showPrompt.value = Pair(PassphraseConstraints.PIN_FOUR_DIGITS, "1111") },
                content = { Text("4-Digit PIN") }
            )
        }
        item {
            TextButton(
                onClick = { showPrompt.value = Pair(PassphraseConstraints.PIN_FOUR_DIGITS_OR_LONGER, "11111") },
                content = { Text("4-Digit PIN or longer") }
            )
        }
        item {
            TextButton(
                onClick = { showPrompt.value = Pair(PassphraseConstraints.PIN_SIX_DIGITS, "123456") },
                content = { Text("6-Digit PIN") }
            )
        }
        item {
            TextButton(
                onClick = { showPrompt.value = Pair(PassphraseConstraints.PIN_SIX_DIGITS_OR_LONGER, "1234567") },
                content = { Text("6-Digit PIN or longer") }
            )
        }
        item {
            TextButton(
                onClick = { showPrompt.value = Pair(PassphraseConstraints.PASSPHRASE_SIX_CHARS, "abcdef") },
                content = { Text("6-Character Passphrase") }
            )
        }
        item {
            TextButton(
                onClick = {
                    showPrompt.value = Pair(PassphraseConstraints.PASSPHRASE_SIX_CHARS_OR_LONGER, "axaxaxa") },
                content = { Text("6-Character Passphrase or longer") }
            )
        }
        item {
            TextButton(
                onClick = { showPrompt.value = Pair(PassphraseConstraints.NONE, "multipaz.org") },
                content = { Text("No constraints") }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun showPassphrasePrompt(
    constraints: PassphraseConstraints,
    expectedPassphrase: String,
    onDismissRequest: () -> Unit,
    onPassphraseEntered: (passphrase: String) -> PassphraseEvaluation
) {
    // To avoid jank, we request the keyboard to be shown only when the sheet is fully expanded.
    //
    val showKeyboard = MutableStateFlow<Boolean>(false)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            showKeyboard.value = true
            true
        }
    )
    PassphrasePromptBottomSheet(
        sheetState = sheetState,
        title = "Verifying Knowledge Factor",
        subtitle = if (constraints.requireNumerical) {
            "Enter your PIN to continue. " +
            "It's '$expectedPassphrase' but also try entering " +
            "something else to test see an error message"
        } else {
            "Enter your passphrase to continue. " +
                    "It's '$expectedPassphrase' but also try entering " +
                    "something else to test see an error message"
        },
        passphraseConstraints = constraints,
        showKeyboard = showKeyboard.asStateFlow(),
        onPassphraseEntered = onPassphraseEntered,
        onDismissed = onDismissRequest,
    )

}
