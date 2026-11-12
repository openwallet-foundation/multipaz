package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.multipaz.nfc.NfcApduService
import kotlin.concurrent.Volatile

/**
 * Base class for NFC APDU services that initiate and manage an ISO/IEC 18013-5 mdoc
 * presentment transaction ([MdocNdefService] and [MdocNfcCceService]).
 */
abstract class MdocNfcPresentmentService : NfcApduService() {
    companion object {
        // Shared across all MdocNfcPresentmentService subclasses (MdocNdefService and
        // MdocNfcCceService) so that a subsequent NFC tap on either service (or across
        // recreated service instances) can cancel and join any previous active proximity
        // transaction.
        @Volatile
        internal var transactionJob: Job? = null
    }

    internal class TransactionSupersededException :
        CancellationException("Transaction superseded by a new NFC tap")

    /**
     * Triggers haptic feedback indicating an NFC engagement error, if supported by the platform.
     */
    protected fun vibrateError() {
        vibrateMdocNfcError()
    }

    /**
     * Triggers haptic feedback indicating successful NFC engagement, if supported by the platform.
     */
    protected fun vibrateSuccess() {
        vibrateMdocNfcSuccess()
    }
}

internal expect fun vibrateMdocNfcError()
internal expect fun vibrateMdocNfcSuccess()
