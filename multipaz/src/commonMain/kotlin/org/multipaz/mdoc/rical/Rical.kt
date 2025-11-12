package org.multipaz.mdoc.rical

import org.multipaz.cbor.DataItem
import kotlin.time.Instant

/**
 * The data in a RICAL according to ISO/IEC 18013-5 Second Edition Annex F..
 *
 * @property type type of the RICAL, e.g. [RICAL_TYPE_READER_AUTHENTICATION].
 * @property version the version of the RICAL data structure, e.g. "1.0".
 * @property provider the provider of the RICAL.
 * @property date the date the RICAL was issued.
 * @property nextUpdate the date an update is expected to be available, if available.
 * @property notAfter date after which the RICAL is not valid, if available.
 * @property certificateInfos CA certificates included in this RICAL.
 * @property id Uniquely identifies the specific issue of the RICAL.
 * @property latestRicalUrl HTTPS URL of the latest RICAL.
 * @property extensions proprietary extensions.
 */
data class Rical(
    val type: String,
    val version: String,
    val provider: String,
    val date: Instant,
    val nextUpdate: Instant?,
    val notAfter: Instant?,
    val certificateInfos: List<RicalCertificateInfo>,
    val id: Long?,
    val latestRicalUrl: String?,
    val extensions: Map<String, DataItem>?,
) {
    companion object {
        const val RICAL_TYPE_READER_AUTHENTICATION = "org.iso.18013.5.1.reader_authentication"
    }
}