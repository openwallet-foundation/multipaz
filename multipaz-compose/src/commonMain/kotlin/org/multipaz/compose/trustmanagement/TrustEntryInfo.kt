package org.multipaz.compose.trustmanagement

import androidx.compose.runtime.Composable
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.trustmanagement.TrustEntry
import org.multipaz.trustmanagement.TrustManager

/**
 * A UI-friendly data container that holds a [TrustEntry] alongside its parsed
 * representation and resolved display attributes.
 *
 * This class is typically emitted by the [TrustManagerModel] to provide Compose
 * components with pre-parsed data, preventing the UI layer from having to repeatedly
 * parse CBOR/ASN.1 structures during recomposition.
 *
 * @property entry The underlying raw [TrustEntry] (e.g., X.509, VICAL, or RICAL).
 * @property manager The [TrustManager] instance managing this entry.
 * @property signedVical The parsed [SignedVical] if the entry is a VICAL, otherwise null.
 * @property signedRical The parsed [SignedRical] if the entry is a RICAL, otherwise null.
 */
data class TrustEntryInfo(
    val entry: TrustEntry,
    val manager: TrustManager,
    val signedVical: SignedVical?,
    val signedRical: SignedRical?
) {
    /**
     * Resolves the display name for a [TrustEntry].
     *
     * If the entry's metadata contains a `displayName`, it is prioritized.
     * Otherwise, it falls back to a generated name via [getFallbackName].
     */
    @Composable
    fun getDisplayName(): String {
        return entry.metadata.displayName ?: entry.getFallbackName(signedVical, signedRical)
    }
}