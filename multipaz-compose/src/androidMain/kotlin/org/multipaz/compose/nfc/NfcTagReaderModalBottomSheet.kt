package org.multipaz.nfc

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.nfc_tag_reader_modal_bottom_sheet_cancel
import org.multipaz.multipaz_compose.generated.resources.nfc_tag_reader_modal_bottom_sheet_ready_to_scan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcTagReaderModalBottomSheet(
    dialogMessage: String,
    dialogIconPainter: Painter,
    onDismissed: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { onDismissed() },
        sheetState = sheetState,
        dragHandle = {},
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(Res.string.nfc_tag_reader_modal_bottom_sheet_ready_to_scan),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = dialogMessage,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Image(
                painter = dialogIconPainter,
                modifier = Modifier.size(128.dp),
                contentDescription = null
            )
            FilledTonalButton(
                onClick = { onDismissed() }
            ) {
                Text(text = stringResource(Res.string.nfc_tag_reader_modal_bottom_sheet_cancel))
            }
        }
    }
}