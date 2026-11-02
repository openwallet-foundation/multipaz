package org.multipaz.trustmanagement

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

/**
 * Metadata about an entity that can be trusted.
 *
 * @param displayName a name suitable to display to the end user, for example "Utopia Brewery",
 *   "Utopia-E-Mart", or "Utopia DMV". This should be kept short as it may be used in for
 *   example consent or verification dialogs.
 * @param displayIcon an icon suitable to display to the end user in a consent or verification dialog.
 *   PNG format is expected, transparency is supported and square aspect ratio is preferred.
 * @param displayIconUrl like [displayIcon] but instead an URL to where the icon can be
 *   retrieved or `null`
 * @param privacyPolicyUrl an URL to the trusted entity's privacy policy or `null`.
 * @param disclaimer a disclaimer about e.g. the trustworthiness of the data that should be shown
 *   to the user whenever this data is shown, or `null`.
 * @param testOnly `true` if this trusted entity is used for testing, `false` if not. Applications
 *   may use this if they support importing test certificates / VICALs and wish to convey in
 *   the user interface that the particular reader or issuer being authenticated is used
 *   only for testing.
 * @param extensions additional metadata which can be used by the application.
 * @param identifier a unique, stable identifier for the trusted entity (for example, the hex-encoded
 *   Subject Key Identifier of the trusted root certificate) or `null`.
 */
@CborSerializable
data class TrustMetadata(
    val displayName: String? = null,
    val displayIcon: ByteString? = null,
    val displayIconUrl: String? = null,
    val privacyPolicyUrl: String? = null,
    val disclaimer: String? = null,
    val testOnly: Boolean = false,
    val extensions: Map<String, String> = emptyMap(),
    val identifier: String? = null,
) {
    companion object
}