@file:OptIn(ExperimentalMaterial3Api::class)

package org.multipaz.compose.passphrase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.multipaz.securearea.PassphraseConstraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.passphrase_prompt_too_many_attempts
import org.multipaz.multipaz_compose.generated.resources.passphrase_prompt_try_again
import org.multipaz.multipaz_compose.generated.resources.passphrase_prompt_try_again_attempts_remain
import org.multipaz.multipaz_compose.generated.resources.pin_prompt_too_many_attempts
import org.multipaz.multipaz_compose.generated.resources.pin_prompt_try_again
import org.multipaz.multipaz_compose.generated.resources.pin_prompt_try_again_attempts_remain
import org.multipaz.prompt.PassphraseEvaluation
import kotlin.time.Duration.Companion.seconds

/**
 * A [ModalBottomSheet] used for obtaining a passphrase.
 *
 * @param sheetState a [SheetState] for state.
 * @param title the title in the sheet.
 * @param subtitle the subtitle to display in the sheet.
 * @param passphraseConstraints a [PassphraseConstraints] describing the passphrase to be collected.
 * @param showKeyboard a [StateFlow] that should be set to `true` once the bottom sheet is fully shown.
 * @param onPassphraseEntered called when the passphrase has been collected. Return `null` to indicate success
 *   otherwise a message to display in the prompt conveying the user entered the wrong passphrase.
 * @param onDismissed called if the user dismisses the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassphrasePromptBottomSheet(
    sheetState: SheetState,
    title: String,
    subtitle: String,
    passphraseConstraints: PassphraseConstraints,
    showKeyboard: StateFlow<Boolean>,
    onPassphraseEntered: suspend (passphrase: String) -> PassphraseEvaluation,
    onDismissed: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        modifier = Modifier.imePadding(),
        onDismissRequest = { onDismissed() },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets.ime },
    ) {
        val evaluationResult = remember {
            mutableStateOf<PassphraseEvaluation>(PassphraseEvaluation.OK)
        }
        val hideWrongPassphraseMessageJob = remember { mutableStateOf<Job?>(null) }
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                onClick = { onDismissed() }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                )
            }
        }

        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Column(
                modifier = Modifier
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = title,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        text = subtitle,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                PassphrasePromptInputField(
                    constraints = passphraseConstraints,
                    showKeyboard = showKeyboard,
                    onChanged = { passphrase, donePressed ->
                        // Note: onChanged is invoked in a coroutine because onPassphraseEntered
                        // might need to perform suspendable calls when checking the passphrase.
                        var matchResult: PassphraseEvaluation = PassphraseEvaluation.OK
                        if (!passphraseConstraints.isFixedLength()) {
                            // notify of the typed passphrase when user taps 'Done' on the keyboard
                            if (donePressed) {
                                matchResult = onPassphraseEntered(passphrase)
                            }
                        } else {
                            // when the user enters the maximum numbers of characters, send
                            if (passphrase.length == passphraseConstraints.maxLength) {
                                matchResult = onPassphraseEntered(passphrase)
                            }
                        }
                        evaluationResult.value = matchResult
                        if (matchResult != PassphraseEvaluation.OK) {
                            hideWrongPassphraseMessageJob.value?.cancel()
                            hideWrongPassphraseMessageJob.value = coroutineScope.launch {
                                delay(3.seconds)
                                evaluationResult.value = PassphraseEvaluation.OK
                                hideWrongPassphraseMessageJob.value = null
                            }
                            true  // Signals that the input field should be cleared
                        } else {
                            false
                        }
                    }
                )

                val text = when (val ev = evaluationResult.value) {
                    is PassphraseEvaluation.OK -> ""
                    is PassphraseEvaluation.TryAgain ->
                        if (passphraseConstraints.requireNumerical) {
                            stringResource(Res.string.pin_prompt_try_again)
                        } else {
                            stringResource(Res.string.passphrase_prompt_try_again)
                        }
                    is PassphraseEvaluation.TryAgainAttemptsRemain ->
                        pluralStringResource(
                            if (passphraseConstraints.requireNumerical) {
                                Res.plurals.pin_prompt_try_again_attempts_remain
                            } else {
                                Res.plurals.passphrase_prompt_try_again_attempts_remain
                            },
                            ev.remainingAttempts,
                            ev.remainingAttempts
                        )
                    is PassphraseEvaluation.TooManyAttempts ->
                        if (passphraseConstraints.requireNumerical) {
                            stringResource(Res.string.pin_prompt_too_many_attempts)
                        } else {
                            stringResource(Res.string.passphrase_prompt_too_many_attempts)
                        }
                }

                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    text = text,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
