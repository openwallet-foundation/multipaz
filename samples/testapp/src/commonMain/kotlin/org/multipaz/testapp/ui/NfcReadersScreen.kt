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
import androidx.compose.ui.unit.dp
import org.multipaz.compose.items.FloatingItemCenteredText
import org.multipaz.compose.items.FloatingItemHeadingAndText
import org.multipaz.compose.items.FloatingItemList
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
        modifier = Modifier
            .padding(10.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {

        val readers = externalNfcReaderStore.readers.collectAsState().value
        FloatingItemList(
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
            title = "External NFC Readers"
        ) {
            if (readers.isEmpty()) {
                FloatingItemCenteredText(
                    text = "No external NFC readers configured",
                )
            } else {
                readers.forEach { reader ->
                    val state = reader.observeState().collectAsState(null)
                    FloatingItemHeadingAndText(
                        modifier = Modifier.clickable {
                            onReaderClicked(reader.id)
                        },
                        heading = reader.userDisplayName ?: reader.displayName,
                        text = "State: ${state.value}"
                    )
                }
            }
        }

        Text(
            text = AnnotatedString.fromMarkdown(
                """
                To use an external NFC reader connected via USB, simply plug it in. Any USB smart card reader
                supporting the standard CCID protocol (USB Class 11) will prompt for permission and be detected automatically.
            """.trimIndent().lines().joinToString(" ")
            )
        )
    }
}