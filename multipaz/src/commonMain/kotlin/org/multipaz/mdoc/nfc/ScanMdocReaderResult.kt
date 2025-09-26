package org.multipaz.mdoc.nfc

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.mdoc.transport.MdocTransport
import kotlin.time.Duration

/**
 * Result from [scanMdocReader].
 *
 * @property transport The [MdocTransport] which to hand over to.
 * @property encodedDeviceEngagement the device engagement that was used.
 * @property handover the handover that was used.
 * @property processingDuration the amount of time spent exchanging APDUs to set up the handover.
 */
data class ScanMdocReaderResult(
    val transport: MdocTransport,
    val encodedDeviceEngagement: ByteString,
    val handover: DataItem,
    val processingDuration: Duration
)
