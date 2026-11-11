package org.multipaz.testapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import org.multipaz.compose.scrollbar.ColumnWithScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.multipaz.compose.items.SegmentedItemCenteredText
import org.multipaz.compose.items.SegmentedItemHeadingAndText
import org.multipaz.compose.items.SegmentedItemList
import org.multipaz.compose.text.fromMarkdown
import org.multipaz.nfc.ExternalNfcReaderStore

@Composable
fun NfcReadersScreen(
    externalNfcReaderStore: ExternalNfcReaderStore,
    showToast: (message: String) -> Unit,
    onReaderClicked: (readerId: String) -> Unit
) {
    ColumnWithScrollbar(
        contentPadding = PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {

        val readers = externalNfcReaderStore.readers.collectAsState().value
        SegmentedItemList(
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
            title = "External NFC Readers"
        ) {
            if (readers.isEmpty()) {
                SegmentedItemCenteredText(
                    text = "No external NFC readers configured",
                )
            } else {
                readers.forEach { reader ->
                    val state = reader.observeState().collectAsState(null)
                    SegmentedItemHeadingAndText(
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