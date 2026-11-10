package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import org.multipaz.compose.scrollbar.LazyColumnWithScrollbar
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.compose.PassphraseEntryField

@Composable
fun PassphraseEntryFieldScreen(
    showToast: (message: String) -> Unit
) {
    val showEntry = remember {
        mutableStateOf<Pair<PassphraseConstraints, Boolean>?>(null)
    }

    if (showEntry.value != null) {
        ShowEntry(
            showEntry.value!!.first,
            showEntry.value!!.second,
            onDismissRequest = {
                showEntry.value = null
            },
            onPassphraseEntered = { passphrase ->
                showToast("'$passphrase' was entered")
                showEntry.value = null
            })
    }

    LazyColumnWithScrollbar(
        modifier = Modifier.padding(8.dp)
    ) {

        for (checkWeakPassphrase in listOf(false, true)) {
            item {
                TextButton(
                    onClick = { showEntry.value = Pair(PassphraseConstraints.PIN_FOUR_DIGITS, checkWeakPassphrase)},
                    content = { Text("4-Digit PIN (checkWeak=$checkWeakPassphrase)") }
                )
            }
            item {
                TextButton(
                    onClick = { showEntry.value = Pair(PassphraseConstraints.PIN_FOUR_DIGITS_OR_LONGER, checkWeakPassphrase)},
                    content = { Text("4-Digit PIN or longer (checkWeak=$checkWeakPassphrase)") }
                )
            }
            item {
                TextButton(
                    onClick = { showEntry.value = Pair(PassphraseConstraints.PIN_SIX_DIGITS, checkWeakPassphrase)},
                    content = { Text("6-Digit PIN (checkWeak=$checkWeakPassphrase)") }
                )
            }
            item {
                TextButton(
                    onClick = { showEntry.value = Pair(PassphraseConstraints.PIN_SIX_DIGITS_OR_LONGER, checkWeakPassphrase)},
                    content = { Text("6-Digit PIN or longer (checkWeak=$checkWeakPassphrase)") }
                )
            }
            item {
                TextButton(
                    onClick = { showEntry.value = Pair(PassphraseConstraints.PASSPHRASE_SIX_CHARS, checkWeakPassphrase)},
                    content = { Text("6-Character Passphrase (checkWeak=$checkWeakPassphrase)") }
                )
            }
            item {
                TextButton(
                    onClick = {
                        showEntry.value = Pair(PassphraseConstraints.PASSPHRASE_SIX_CHARS_OR_LONGER, checkWeakPassphrase)},
                    content = { Text("6-Character Passphrase or longer (checkWeak=$checkWeakPassphrase)") }
                )
            }
            item {
                TextButton(
                    onClick = { showEntry.value = Pair(PassphraseConstraints.NONE, checkWeakPassphrase)},
                    content = { Text("No constraints (checkWeak=$checkWeakPassphrase)") }
                )
            }
        }

    }
}

@Composable
private fun ShowEntry(constraints: PassphraseConstraints,
                      checkWeakPassphrase: Boolean,
                      onDismissRequest: () -> Unit,
                      onPassphraseEntered: (passphrase: String) -> Unit) {
    // Is only non-null if the passphrase meets requirements.
    val curPassphrase = remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "PassphraseEntryField",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge
                )

                PassphraseEntryField(
                    constraints,
                    checkWeakPassphrase,
                    onChanged = { passphrase, meetsRequirements, donePressed ->
                        curPassphrase.value = if (meetsRequirements) passphrase else null
                        if (meetsRequirements) {
                            if (donePressed || constraints.minLength == constraints.maxLength) {
                                onPassphraseEntered(passphrase)
                            }
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onDismissRequest() },
                    ) {
                        Text("Cancel")
                    }
                    if (constraints.minLength != constraints.maxLength) {
                        TextButton(
                            onClick = { onPassphraseEntered(curPassphrase.value!!) },
                            enabled = ( curPassphrase.value != null )
                        ) {
                            Text("Next")
                        }
                    }
                }
            }
        }
    }
}