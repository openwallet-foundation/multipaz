package org.multipaz.testapp

import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.mdoc.CombinedNfcService
import org.multipaz.compose.mdoc.NfcApduService
import org.multipaz.nfc.Nfc

class TestAppCombinedNfcService: CombinedNfcService() {
    override fun buildServices(): Map<ByteString, NfcApduService> {
        return mapOf(
            Nfc.NDEF_APPLICATION_ID to TestAppMdocNdefService(this, ::sendResponseApdu),
            Nfc.MDOC_NFC_ENGAGEMENT_V2_AID to TestAppMdocNfcV2Service(this, ::sendResponseApdu),
            Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID to TestAppMdocNfcDataTransferService(this, ::sendResponseApdu)
        )
    }
}