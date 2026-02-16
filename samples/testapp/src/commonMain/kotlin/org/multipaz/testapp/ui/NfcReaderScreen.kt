package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.compose.items.Item
import org.multipaz.compose.items.ItemList
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

    val hexFormat = HexFormat {
        number.prefix = "0x"
        number.minLength = 4
        number.removeLeadingZeros = true
    }

    val items = mutableListOf<@Composable () -> Unit>()
    items.add {
        Item("Name", reader.displayName)
    }
    if (reader is ExternalNfcReaderUsb) {
        items.add {
            Item("Connection", "USB")
        }
        items.add {
            Item("Vendor ID", reader.vendorId.toHexString(hexFormat))
        }
        items.add {
            Item("Product ID", reader.productId.toHexString(hexFormat))
        }
    }
    val state = reader.observeState().collectAsState(initial = null).value
    items.add {
        Item("State", state.toString())
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ItemList(
            items = items,
            title = "External NFC Reader",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = (state == ExternalNfcReaderState.CONNECTED_NO_PERMISSION),
                onClick = {
                    coroutineScope.launch {
                        try {
                            if (!reader.requestPermission()) {
                                showToast("User did not grant permission")
                            }
                        } catch (e: Throwable) {
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