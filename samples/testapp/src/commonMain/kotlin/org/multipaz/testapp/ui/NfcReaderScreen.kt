package org.multipaz.testapp.ui

import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.compose.items.FloatingItemHeadingAndText
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.nfc.ExternalNfcReaderState
import org.multipaz.nfc.ExternalNfcReaderStore
import org.multipaz.nfc.ExternalNfcReaderUsb

@Composable
fun NfcReaderScreen(
    externalNfcReaderStore: ExternalNfcReaderStore,
    readerId: String,
    showToast: (message: String) -> Unit,
    onReaderRemoved: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    val reader = externalNfcReaderStore.readers.collectAsState().value.find { it.id == readerId }
    if (reader == null) {
        return
    }

    var showEditDialog by remember { mutableStateOf(false) }
    var nameInput by remember(reader.userDisplayName) { mutableStateOf(reader.userDisplayName ?: "") }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Reader Name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Hardware name: ${reader.displayName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Custom Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = nameInput.trim().ifEmpty { null }
                        coroutineScope.launch {
                            reader.setUserDisplayName(newName)
                        }
                        showEditDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val hexFormat = HexFormat {
        number.prefix = "0x"
        number.minLength = 4
        number.removeLeadingZeros = true
    }

    val state = reader.observeState().collectAsState(initial = null).value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FloatingItemList(
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
            title = "External NFC Reader"
        ) {
            FloatingItemHeadingAndText("Name", reader.userDisplayName ?: reader.displayName)
            if (reader.userDisplayName != null) {
                FloatingItemHeadingAndText("Original Name", reader.displayName)
            }
            if (reader is ExternalNfcReaderUsb) {
                FloatingItemHeadingAndText("Connection", "USB")
                FloatingItemHeadingAndText("Vendor ID", reader.vendorId.toHexString(hexFormat))
                FloatingItemHeadingAndText("Product ID", reader.productId.toHexString(hexFormat))
                FloatingItemHeadingAndText("Interface Index", reader.interfaceIndex.toString())
            }
            FloatingItemHeadingAndText("State", state.toString())
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    nameInput = reader.userDisplayName ?: ""
                    showEditDialog = true
                }
            ) {
                Text(
                    modifier = Modifier.padding(vertical = 8.dp),
                    text = "Edit Name"
                )
            }

            Button(
                enabled = (state == ExternalNfcReaderState.CONNECTED_NO_PERMISSION),
                onClick = {
                    coroutineScope.launch {
                        try {
                            if (!reader.requestPermission()) {
                                showToast("User did not grant permission")
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            showToast("Error requesting permission: ${e.message}")
                        }
                    }
                },
            ) {
                Text(
                    modifier = Modifier.padding(vertical = 8.dp),
                    text = "Grant permission"
                )
            }

            Button(
                onClick = {
                    coroutineScope.launch {
                        externalNfcReaderStore.removeReader(reader)
                        onReaderRemoved()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red,
                    contentColor = Color.White
                )
            ) {
                Text(
                    modifier = Modifier.padding(vertical = 8.dp),
                    text = "Remove"
                )
            }
        }
    }
}