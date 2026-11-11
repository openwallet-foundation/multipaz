package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import org.multipaz.compose.scrollbar.LazyColumnWithScrollbar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.testapp.ui.ShowQrCodeDialog
import org.multipaz.testapp.ui.ScanQrCodeDialog

@Composable
fun QrCodesScreen(
    showToast: (message: String) -> Unit
) {
    val showMdocQrCodeDialog = remember { mutableStateOf(false) }
    val showUrlQrCodeDialog = remember { mutableStateOf(false) }
    val showQrScanDialog = remember { mutableStateOf(false) }

    if (showMdocQrCodeDialog.value) {
        ShowQrCodeDialog(
            title = { Text(text = "Scan code on reader") },
            text = { Text(text = "This is a QR code from ISO/IEC 18013-5:2021 Annex D.") },
            dismissButton = "Close",
            // This is the DeviceEngagement test vector from ISO/IEC 18013-5:2021 Annex D encoded
            // as specified in clause 8.2.2.3.
            data = "mdoc:owBjMS4wAYIB2BhYS6QBAiABIVggWojRgrzl9C76WZQ/MzWdAuipaP8onZPl" +
                    "+kRLYkNDFn/iJYILFujPhY3cdpBAe6YdTDOCNwqM/PPeaqZy/GClV6oy/GcCgYMCAaMA9AH1C1BF7+90KyxIN6kKOw4dBaaRBw==",
            onDismiss = { showMdocQrCodeDialog.value = false }
        )
    }

    if (showUrlQrCodeDialog.value) {
        ShowQrCodeDialog(
            title = { Text(text = "Scan code with phone") },
            text = { Text(text = "This is a QR code for https://github.com/openwallet-foundation-labs/identity-credential") },
            dismissButton = "Close",
            data = "https://github.com/openwallet-foundation-labs/identity-credential",
            onDismiss = { showUrlQrCodeDialog.value = false }
        )
    }

    if (showQrScanDialog.value) {
        val qrCode = remember { mutableStateOf<String?>(null) }
        ScanQrCodeDialog(
            title = { Text ("Scan code") },
            text = { Text ("If a QR code is detected, it is printed out at the bottom of the dialog") },
            dismissButton = "Close",
            onCodeScanned = { data ->
                qrCode.value = data
                false
            },
            onNoCodeDetected = {
                qrCode.value = null
            },
            additionalContent = {
                if (qrCode.value == null) {
                    Text("No QR Code detected")
                } else {
                    Text("QR: ${qrCode.value}")
                }

            },
            onDismiss = { showQrScanDialog.value = false }
        )
    }

    LazyColumnWithScrollbar(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            TextButton(
                onClick = { showMdocQrCodeDialog.value = true },
                content = { Text("Show mdoc QR code") }
            )
        }

        item {
            TextButton(
                onClick = { showUrlQrCodeDialog.value = true },
                content = { Text("Show URL QR code") }
            )
        }

        item {
            TextButton(
                onClick = { showQrScanDialog.value = true },
                content = { Text("Scan QR code") }
            )
        }
    }

}

