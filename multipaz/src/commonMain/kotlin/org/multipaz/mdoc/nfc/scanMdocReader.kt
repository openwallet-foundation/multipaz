package org.multipaz.mdoc.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcHybridTransportMdocReader
import org.multipaz.mdoc.transport.NfcTransportMdocReader
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.nfc.NfcTagReader
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.util.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

private const val TAG = "scanMdocReader"

/**
 * Performs NFC engagement as a mdoc reader.
 *
 * This blocks until a connection has been established and on successful handover a [ScanMdocReaderResult]
 * instance is returned with the transport, device engagement, handover, and the time spent exchanging APDUs
 * with the remote mdoc.
 *
 * @param message the message to display in the NFC tag scanning dialog or `null` to not show a dialog. Not all
 *   platforms supports not showing a dialog, use [org.multipaz.nfc.nfcTagScanningSupportedWithoutDialog] to check at
 *   runtime if the platform supports this.
 * @param options the [MdocTransportOptions] used to create new [MdocTransport] instances.
 * @param handoverOptions the [MdocReaderNfcHandoverOptions] used to control handover behavior.
 * @param transportFactory the factory used to create [MdocTransport] instances.
 * @param selectConnectionMethod used to choose a connection method if the remote mdoc is using NFC static handover.
 * @param negotiatedHandoverConnectionMethods the connection methods to offer if the remote mdoc is using NFC
 * Negotiated Handover.
 * @param nfcScanOptions a [NfcScanOptions] with options to influence scanning.
 * @param context the [CoroutineContext] to use for calls to the tag which blocks the calling thread.
 * @return a [ScanMdocReaderResult] if successful handover was established, `null` if the user dismissed the dialog.
 */
suspend fun NfcTagReader.scanMdocReader(
    message: String?,
    options: MdocTransportOptions,
    handoverOptions: MdocReaderNfcHandoverOptions,
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
    selectConnectionMethod: suspend (connectionMethods: List<MdocConnectionMethod>) -> MdocConnectionMethod?,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>,
    nfcScanOptions: NfcScanOptions = NfcScanOptions(),
    context: CoroutineContext = Dispatchers.Default,
): ScanMdocReaderResult? {
    return scanMdocReader(
        message = message,
        options = options,
        handoverOptions = handoverOptions,
        transportFactory = transportFactory,
        selectConnectionMethod = selectConnectionMethod,
        negotiatedHandoverConnectionMethods = negotiatedHandoverConnectionMethods,
        nfcScanOptions = nfcScanOptions,
        context = context,
        onHandover = { scanResult -> scanResult }
    )
}

/**
 * Performs NFC engagement and reader transaction processing as a mdoc reader.
 *
 * Use this variant when performing reader transactions where the data transfer should be handled
 * inline with NFC scanning.
 *
 * For NFCv2 NFC-only engagements ([MdocConnectionMethodNfcV2]), [onHandover] is invoked while the
 * NFC scanning UI dialog remains active. If the holder removes their device prematurely or a transport
 * error occurs before [onHandover] returns a response, [scanMdocReader] closes the failed transport,
 * keeps the scanner dialog visible, and continues polling for tag re-taps. When a re-tap occurs,
 * [onHandover] is invoked again with the new engagement's [ScanMdocReaderResult] to complete the transaction.
 *
 * For all other engagement types (e.g. NFCv2 with BLE, NFC Negotiated Handover, or NFC Static Handover),
 * the NFC scanning dialog is dismissed immediately upon first tap handover, and [onHandover] is executed
 * after the dialog has been removed.
 *
 * @param message the message to display in the NFC tag scanning dialog or `null` to not show a dialog. Not all
 *   platforms support not showing a dialog, use [org.multipaz.nfc.nfcTagScanningSupportedWithoutDialog] to check at
 *   runtime if the platform supports this.
 * @param options the [MdocTransportOptions] used to create new [MdocTransport] instances.
 * @param handoverOptions the [MdocReaderNfcHandoverOptions] used to control handover behavior.
 * @param transportFactory the factory used to create [MdocTransport] instances.
 * @param selectConnectionMethod used to choose a connection method if the remote mdoc is using NFC static handover.
 * @param negotiatedHandoverConnectionMethods the connection methods to offer if the remote mdoc is using NFC
 *   Negotiated Handover.
 * @param nfcScanOptions a [NfcScanOptions] with options to influence scanning.
 * @param context the [CoroutineContext] to use for calls to the tag which blocks the calling thread.
 * @param onHandover callback invoked with the [ScanMdocReaderResult] upon handover completion. For NFCv2 NFC-only
 *   engagements, this executes inside the active tag scanning loop to support re-taps on disconnect. For all other
 *   handover types, it is invoked after the scanning dialog has been dismissed.
 * @return the non-null result of type [T] returned by [onHandover] when the transaction completes successfully, or
 *   `null` if the user dismissed the NFC scanning dialog.
 */
suspend fun <T> NfcTagReader.scanMdocReader(
    message: String?,
    options: MdocTransportOptions,
    handoverOptions: MdocReaderNfcHandoverOptions,
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
    selectConnectionMethod: suspend (connectionMethods: List<MdocConnectionMethod>) -> MdocConnectionMethod?,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>,
    nfcScanOptions: NfcScanOptions = NfcScanOptions(),
    context: CoroutineContext = Dispatchers.Default,
    onHandover: suspend (scanResult: ScanMdocReaderResult) -> T?
): T? {
    // Start creating transports for Negotiated Handover and start advertising these
    // immediately. This helps with connection time because the holder's device will
    // get a chance to opportunistically read the UUIDs which helps reduce scanning
    // time.
    //
    val negotiatedHandoverTransports = negotiatedHandoverConnectionMethods.map {
        val transport = transportFactory.createTransport(
            it,
            MdocRole.MDOC_READER,
            options
        )
        transport.advertise()
        transport
    }
    // Make sure we don't leak connections...
    val transportsToClose = negotiatedHandoverTransports.toMutableList()

    try {
        val result = scan(
            message = message,
            tagInteractionFunc = tagInteractionFunc@{ tag ->
                val t0 = Clock.System.now()
                val handoverResult = mdocReaderNfcHandover(
                    tag = tag,
                    negotiatedHandoverConnectionMethods = negotiatedHandoverTransports.map { it.connectionMethod },
                    options = handoverOptions
                )
                if (handoverResult == null) {
                    return@tagInteractionFunc null
                }
                val connectionMethod = if (handoverResult.connectionMethods.size == 1) {
                    handoverResult.connectionMethods[0]
                } else {
                    selectConnectionMethod(handoverResult.connectionMethods)
                }
                if (connectionMethod == null) {
                    return@tagInteractionFunc null
                }

                // Now that we're connected, close remaining transports and see if one of the warmed-up
                // transports was chosen (can happen for negotiated handover, never for static handover)
                //
                var transport: MdocTransport? = null
                transportsToClose.forEach {
                    if (it.connectionMethod == connectionMethod) {
                        transport = it
                    } else {
                        Logger.i(TAG, "Closing connection with CM ${it.connectionMethod}")
                        it.close()
                    }
                }
                transportsToClose.clear()
                if (transport == null) {
                    // For NFCv2, it's possible the mdoc only supports NFC in which case there is
                    // no transport to create
                    if (connectionMethod !is MdocConnectionMethodNfcV2) {
                        transport = transportFactory.createTransport(
                            connectionMethod,
                            MdocRole.MDOC_READER,
                            options
                        )
                    }
                }

                val scanResult = if (handoverResult.type == MdocHandoverType.V2_HANDOVER) {
                    ScanMdocReaderResult(
                        transport = NfcHybridTransportMdocReader(
                            nfcTag = tag,
                            negotiatedTransport = transport
                        ),
                        encodedDeviceEngagement = handoverResult.encodedDeviceEngagement,
                        handover = handoverResult.handover,
                        type = handoverResult.type,
                        processingDuration = Clock.System.now() - t0
                    )
                } else {
                    if (transport is NfcTransportMdocReader) {
                        transport.setTag(tag)
                    } else {
                        tag.close()
                    }
                    ScanMdocReaderResult(
                        transport = transport!!,
                        encodedDeviceEngagement = handoverResult.encodedDeviceEngagement,
                        handover = handoverResult.handover,
                        type = handoverResult.type,
                        processingDuration = Clock.System.now() - t0
                    )
                }

                val isNfcV2NfcOnly = handoverResult.type == MdocHandoverType.V2_HANDOVER &&
                        scanResult.transport.connectionMethod is MdocConnectionMethodNfcV2

                if (isNfcV2NfcOnly) {
                    try {
                        val res = onHandover(scanResult)
                        if (res == null) {
                            try { scanResult.transport.close() } catch (_: Throwable) {}
                            null
                        } else {
                            NfcV2NfcOnlyResult(res)
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        try { scanResult.transport.close() } catch (_: Throwable) {}
                        if (e.isTagLostOrTransportClosed()) {
                            Logger.i(TAG, "NFC tag lost or transport closed during NFCv2 NFC-only transaction, continuing scanning...", e)
                            null
                        } else {
                            throw e
                        }
                    }
                } else {
                    // For non-NFCv2 NFC-only engagements, return scanResult directly from tagInteractionFunc
                    // so the NFC scanning dialog is dismissed immediately upon first tap handover.
                    scanResult
                }
            },
            options = nfcScanOptions,
            context = context
        )
        if (result == null) {
            return null
        }
        if (result is NfcV2NfcOnlyResult<*>) {
            @Suppress("UNCHECKED_CAST")
            return result.value as T
        }
        if (result is ScanMdocReaderResult) {
            // For non-NFCv2 NFC-only engagements, run onHandover after the NFC dialog has been dismissed.
            return onHandover(result)
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    } catch (_: PromptDismissedException) {
        return null
    } finally {
        // Close listening transports that went unused.
        transportsToClose.forEach {
            Logger.i(TAG, "Closing connection with CM ${it.connectionMethod}")
            it.close()
        }
    }
}

private fun Throwable.isTagLostOrTransportClosed(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is NfcTagLostException ||
            current is MdocTransportClosedException ||
            current is MdocTransportException
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

private class NfcV2NfcOnlyResult<T>(val value: T)
