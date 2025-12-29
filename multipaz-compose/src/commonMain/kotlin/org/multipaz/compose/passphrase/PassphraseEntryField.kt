package org.multipaz.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.passphrase_entry_field_passphrase_is_weak
import org.multipaz.multipaz_compose.generated.resources.passphrase_entry_field_pin_is_weak
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString


/**
 * A composable for entering a passphrase or PIN.
 *
 * Three parameters are passed to the [onChanged] callback, the first is the current passphrase,
 * the second is whether this meets requirements, and they third is a boolean which is set to
 * `true` only if the user pressed the "Done" button on the virtual keyboard.
 *
 * Note that [onChanged] will be fired right after the composable is first shown. This is to
 * enable the calling code to update e.g. a "Next" button based on whether the currently
 * entered passphrase meets requirements.
 *
 * @param constraints the constraints for the passphrase.
 * @param checkWeakPassphrase if true, checks and disallows for weak passphrase/PINs and also
 *   shows a hint if one is input
 * @param onChanged called when the user is entering text or pressing the "Done" IME action
 */
@Composable
fun PassphraseEntryField(
    constraints: PassphraseConstraints,
    checkWeakPassphrase: Boolean = false,
    onChanged: (passphrase: String, meetsRequirements: Boolean, donePressed: Boolean) -> Unit,
) {
    // if no imeAction specified define the default to be ImeAction.Done
    val focusRequester = remember { FocusRequester() }

    var inputText by remember { mutableStateOf("") }
    var passphraseAnalysis by remember {
        mutableStateOf(PassphraseAnalysis(false, null))
    }
    var obfuscateAll by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    // Put boxes around the entered chars for fixed length and six or fewer characters
    var decorationBox: @Composable (@Composable () -> Unit) -> Unit =
        @Composable { innerTextField -> innerTextField() }
    if (constraints.isFixedLength() && constraints.minLength <= 6) {
        decorationBox = {
            Row(
                horizontalArrangement = Arrangement.Center,
            ) {
                // Obfuscate all but the last characters ... note `visualTransformation` isn't used.
                repeat(constraints.maxLength) { digitIndex ->
                    val digit =
                        if (digitIndex == inputText.length - 1) {
                            if (obfuscateAll) {
                                "\u2022"  // U+2022 Bullet
                            } else {
                                inputText[digitIndex].toString()
                            }
                        } else if (digitIndex < inputText.length) {
                            "\u2022"  // U+2022 Bullet
                        } else {
                            ""
                        }
                    Text(
                        text = digit,
                        modifier = Modifier
                            .width(40.dp)
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                            .padding(2.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            color = MaterialTheme.colorScheme.primary,
                            // make the "dot" \u2022 be a bit bolder/bigger
                            fontWeight = if (digit == "\u2022") {
                                FontWeight.Black
                            } else {
                                MaterialTheme.typography.headlineLarge.fontWeight
                            }
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        BasicTextField(
            value = inputText,
            modifier = Modifier
                .padding(8.dp)
                .focusRequester(focusRequester),
            onValueChange = {
                if (it.length > constraints.maxLength) {
                    return@BasicTextField
                }
                if (constraints.requireNumerical) {
                    if (!(it.all { it.isDigit() })) {
                        return@BasicTextField
                    }
                }

                coroutineScope.launch {
                    inputText = it
                    passphraseAnalysis = analyzePassphrase(inputText, constraints, checkWeakPassphrase)
                    onChanged(inputText, passphraseAnalysis.meetsRequirements, false)
                    obfuscateAll = false
                    delay(750)
                    obfuscateAll = true
                    val value = inputText
                    inputText = ""
                    inputText = value
                }

            },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.primary),
            decorationBox = decorationBox,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (constraints.requireNumerical) KeyboardType.NumberPassword else KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    onChanged(inputText, passphraseAnalysis.meetsRequirements, true)
                }
            ),
            visualTransformation = { text ->
                val mask = '\u2022'
                val result = if (text.isNotEmpty()) {
                    if (obfuscateAll) {
                        mask.toString().repeat(text.text.length)
                    } else {
                        mask.toString().repeat(text.text.length - 1) + text.last()
                    }
                } else {
                    ""
                }
                TransformedText(
                    AnnotatedString(result),
                    OffsetMapping.Identity
                )
            }
        )
    }

    if (!constraints.isFixedLength()) {
        HorizontalDivider(
            color = Color.Blue,
            thickness = 2.dp,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp)
        )
    }

    passphraseAnalysis.weakHint?.let {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    LaunchedEffect(Unit) {
        // Bring up keyboard when entering screen
        focusRequester.requestFocus()
        passphraseAnalysis = analyzePassphrase(inputText, constraints, checkWeakPassphrase)
        // Fire initially so caller can adjust e.g. sensitivity of a possible "Next" button
        onChanged(inputText, passphraseAnalysis.meetsRequirements, false)
    }
}

private data class PassphraseAnalysis(
    val meetsRequirements: Boolean,
    val weakHint: String?,
)

private suspend fun analyzePassphrase(
    passphrase: String,
    constraints: PassphraseConstraints,
    checkWeakPassphrase: Boolean
): PassphraseAnalysis {
    // For a fixed-length passphrase, never give any hints until user has typed it in.
    if (constraints.isFixedLength()) {
        if (passphrase.length < constraints.minLength) {
            return PassphraseAnalysis(meetsRequirements = false, weakHint = null)
        }
    }

    if (passphrase.length < constraints.minLength) {
        return PassphraseAnalysis(meetsRequirements = false, weakHint = null)
    }

    if (checkWeakPassphrase && isWeakPassphrase(passphrase)) {
        return PassphraseAnalysis(
            meetsRequirements = false,
            weakHint = if (constraints.requireNumerical)
                getString(Res.string.passphrase_entry_field_pin_is_weak)
            else
                getString(Res.string.passphrase_entry_field_passphrase_is_weak),
        )
    }

    return PassphraseAnalysis(
        meetsRequirements = true,
        weakHint = null
    )
}

private fun isWeakPassphrase(passphrase: String): Boolean {
    if (passphrase.isEmpty()) {
        return true
    }

    // Check all characters being the same
    if (passphrase.all { it.equals(passphrase.first()) }) {
        return true
    }

    // Check consecutive characters (e.g. 1234 or abcd)
    var nextChar = passphrase.first().inc()
    for (n in IntRange(1, passphrase.length - 1)) {
        if (passphrase[n] != nextChar) {
            return false
        }
        nextChar = nextChar.inc()
    }

    // TODO: add more checks

    return true
}
