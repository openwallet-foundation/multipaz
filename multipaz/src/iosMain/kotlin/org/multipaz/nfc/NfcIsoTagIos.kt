package org.multipaz.nfc

import org.multipaz.util.toByteArray
import org.multipaz.util.toKotlinError
import org.multipaz.util.toNSData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.util.Logger
import platform.CoreNFC.NFCISO7816APDU
import platform.CoreNFC.NFCISO7816TagProtocol
import platform.CoreNFC.NFCErrorDomain
import platform.CoreNFC.NFCReaderTransceiveErrorTagResponseError
import platform.CoreNFC.NFCTagReaderSession
import kotlin.coroutines.resumeWithException

internal class NfcIsoTagIos(
    val tag: NFCISO7816TagProtocol,
    private val session: NFCTagReaderSession
): NfcIsoTag() {
    companion object {
        private const val TAG = "NfcIsoTagIos"
    }

    // Note: there's currently no API to obtain this value on iOS.
    //
    override val maxTransceiveLength: Int
        get() = 0xfeff

    internal var closeCalled = false
    internal var invalidateSessionOnClose = false

    override suspend fun close() {
        if (invalidateSessionOnClose) {
            Logger.i(TAG, "close: Invalidating session")
            session.invalidateSession()
        }
        closeCalled = true
    }

    override suspend fun updateDialogMessage(message: String) {
        session.alertMessage = message
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun transceive(command: CommandApdu): ResponseApdu {
        return suspendCancellableCoroutine<ResponseApdu> { continuation ->
            val encodedCommand = command.encode()
            check(encodedCommand.size <= maxTransceiveLength) {
                "APDU is ${encodedCommand.size} bytes which exceeds maxTransceiveLength of $maxTransceiveLength bytes"
            }
            val apdu = NFCISO7816APDU(encodedCommand.toNSData())
            tag.sendCommandAPDU(apdu, { responseData, sw1, sw2, error ->
                if (error != null) {
                    val kotlinError = if (error.domain == NFCErrorDomain &&
                        error.code == NFCReaderTransceiveErrorTagResponseError) {
                        NfcTagLostException("Tag was lost during transceive", error.toKotlinError())
                    } else {
                        error.toKotlinError()
                    }
                    continuation.resumeWithException(kotlinError)
                } else {
                    val responseApduData = responseData!!.toByteArray() + byteArrayOf(sw1.toByte(), sw2.toByte())
                    val responseApdu = ResponseApdu.decode(responseApduData)
                    continuation.resume(responseApdu, null)
                }
            })
        }
    }
}
