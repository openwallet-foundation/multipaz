package org.multipaz.presentment

import kotlin.concurrent.Volatile
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.KeyUnlockData

/**
 * A selection of credentials and claims from a [PresentmentData].
 *
 * This object represents the result of selecting a concrete solution for each use-case
 * from a [PresentmentData] object.
 *
 * This is typically returned from a consent prompt user interface.
 *
 * @property matches the list of credentials and claims to return to the relying party.
 * @property preloadedKeyUnlockData optional list of [KeyUnlockData] for credentials that were
 *   already unlocked during the consent flow (for example via an embedded biometric prompt).
 */
data class PresentmentSelection(
    val matches: List<PresentmentCredentialMatch>,
    val preloadedKeyUnlockData: List<KeyUnlockData> = emptyList(),
) {
    @Volatile
    internal var resolvedSource: PresentmentSource? = null

    @Volatile
    internal var resolvedDeviceRequest: DeviceRequest? = null

    @Volatile
    internal var resolvedRequester: Requester? = null

    @Volatile
    internal var resolvedTrustedRequesterIdentity: TrustedRequesterIdentity? = null

    /**
     * Returns the highest sensitivity level of the claims in all the credentials in this object.
     *
     * @return The highest sensitivity level or `null` if one or more of the claims are unknown.
     */
    fun getMaxSensitivity(): DocumentAttributeSensitivity? {
        var maxSensitivity: DocumentAttributeSensitivity? = null
        matches.forEach { match ->
            match.claims.forEach { (_, claim) ->
                claim.attribute?.let { attribute ->
                    if (maxSensitivity == null) {
                        maxSensitivity = attribute.sensitivity
                    } else {
                        if (attribute.sensitivity > maxSensitivity!!) {
                            maxSensitivity = attribute.sensitivity
                        }
                    }
                } ?: return null
            }
        }
        return maxSensitivity
    }
}