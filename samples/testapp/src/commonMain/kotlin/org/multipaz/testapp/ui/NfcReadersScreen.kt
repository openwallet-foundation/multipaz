package org.multipaz.testapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.multipaz.compose.items.Item
import org.multipaz.compose.items.ItemList
import org.multipaz.compose.text.fromMarkdown
import org.multipaz.nfc.ExternalNfcReaderStore


@Composable
fun NfcReadersScreen(
    externalNfcReaderStore: ExternalNfcReaderStore,
    showToast: (message: String) -> Unit,
    onReaderClicked: (readerId: String) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.padding(8.dp).verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {

        val readers = externalNfcReaderStore.readers.collectAsState().value
        val items = mutableListOf<@Composable () -> Unit>()
        if (readers.isEmpty()) {
            items.add {
                Text(
                    text = "No external NFC readers configured",
                    fontStyle = FontStyle.Italic
                )
            }
        } else {
            readers.forEach { reader ->
                val state = reader.observeState().collectAsState(null)
                items.add {
                    Item(
                        modifier = Modifier.clickable {
                            onReaderClicked(reader.id)
                        },
                        key = reader.displayName,
                        valueText = "State: ${state.value}"
                    )
                }
            }
        }

        Text(
            text = AnnotatedString.fromMarkdown(
                """
                To use an external NFC reader connected via USB, simply plug it in. If the device is supported, a
                prompt will appear asking for permission to use it with the app. For a list of supported devices see
                [usb_nfc_readers.xml](https://github.com/openwallet-foundation/multipaz/blob/main/samples/testapp/src/androidMain/res/xml/usb_nfc_readers.xml)
                file in the Multipaz GitHub repository.
            """.trimIndent().lines().joinToString(" ")
            )
        )
        ItemList(
            items = items,
            title = "External NFC Readers",
        )
    }
}