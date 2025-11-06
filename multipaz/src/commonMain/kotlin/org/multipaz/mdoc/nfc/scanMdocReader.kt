package org.multipaz.mdoc.nfc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.multipaz.cbor.DataItem
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.NfcTransportMdocReader
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.util.Logger
import kotlinx.io.bytestring.ByteString
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagReader
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration

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
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
    selectConnectionMethod: suspend (connectionMethods: List<MdocConnectionMethod>) -> MdocConnectionMethod?,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>,
    nfcScanOptions: NfcScanOptions = NfcScanOptions(),
    context: CoroutineContext = Dispatchers.IO,
): ScanMdocReaderResult? {
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
                    transport = transportFactory.createTransport(
                        connectionMethod,
                        MdocRole.MDOC_READER,
                        options
                    )
                }

                if (transport is NfcTransportMdocReader) {
                    transport.setTag(tag)
                } else {
                    tag.close()
                }

                ScanMdocReaderResult(
                    transport = transport,
                    encodedDeviceEngagement = handoverResult.encodedDeviceEngagement,
                    handover = handoverResult.handover,
                    processingDuration = Clock.System.now() - t0
                )
            },
            options = nfcScanOptions,
            context = context
        )
        return result
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
