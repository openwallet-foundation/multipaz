package org.multipaz.compose.presentment

import org.multipaz.mdoc.transport.MdocTransportOptions

/**
 * Settings to use when doing presentment with Reader QR engagement according to
 * ISO/IEC 18013-5 Second Edition section 9.7.
 *
 * @property preferredConnectionMethodOrder optional list of connection method string prefixes
 *   specifying preference order when the reader advertises multiple BLE modes.
 * @property bleDirectL2capEnabled whether `DirectL2capCentral` methods offered by the reader
 *   may be selected.
 * @property createTransportOptions the [MdocTransportOptions] to use when creating a new [org.multipaz.mdoc.transport.MdocTransport].
 */
data class MdocProximityReaderQrSettings(
    val preferredConnectionMethodOrder: List<String> = emptyList(),
    val bleDirectL2capEnabled: Boolean = true,
    val createTransportOptions: MdocTransportOptions = MdocTransportOptions(),
)
