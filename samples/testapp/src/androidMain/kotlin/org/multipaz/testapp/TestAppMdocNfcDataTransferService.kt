package org.multipaz.testapp

import android.content.Context
import org.multipaz.compose.mdoc.MdocNfcDataTransferService

class TestAppMdocNfcDataTransferService(
    applicationContext: Context,
    sendResponse: (ByteArray) -> Unit
): MdocNfcDataTransferService(applicationContext, sendResponse) {
}