package org.multipaz.provisioning

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Settings for controlling provisioning of [org.multipaz.credential.Credential]s in a [org.multipaz.document.Document].
 *
 * This is used by [DocumentProvisioningHandler].
 *
 * @param minValidTime replace a credential if it's going to be valid for less than this amount of time.
 * @param keyBoundCredentialMaxUses replace a key-bound credential if its use-count is greater or equal than this number.
 * @param keyBoundCredentialNumPerDomain number of key-bound credentials to maintain, per domain.
 * @param keylessCredentialMaxUses replace a keyless credential if its use-count is greater or equal than this number.
 * @param keylessCredentialNumPerDomain number of keyless credentials to maintain, per domain.
 * @param userAuthTimeout the timeout to use for newly created keys, or 0 for authentication for every use.
 * @param requestUserAuth if true, will request [keyBoundCredentialNumPerDomain] credentials for user auth, in the domains
 *   given by [mdocUserAuthDomain] and [sdJwtUserAuthDomain].
 * @param requestNoUserAuth if true, will request [keyBoundCredentialNumPerDomain] credentials without user auth, in the
 *   domains given by [mdocNoUserAuthDomain] and [sdJwtNoUserAuthDomain].
 * @param mdocUserAuthDomain the domain to use when requesting ISO mdoc credentials with user auth required.
 * @param mdocNoUserAuthDomain the domain to use when requesting ISO mdoc credentials without user auth required.
 * @param sdJwtUserAuthDomain the domain to use when requesting key-bound IETF SD-JWT VC credentials with user auth required.
 * @param sdJwtNoUserAuthDomain the domain to use when requesting key-bound IETF SD-JWT VC credentials without user auth required.
 * @param sdJwtKeylessDomain the domain to use when requesting non-key-bound IETF SD-JWT VC credentials
 */
data class DocumentProvisioningSettings(
    val minValidTime: Duration = 5.days,
    val keyBoundCredentialMaxUses: Int = 1,
    val keyBoundCredentialNumPerDomain: Int = 5,
    val keylessCredentialMaxUses: Int = Int.MAX_VALUE,
    val keylessCredentialNumPerDomain: Int = 1,
    val userAuthTimeout: Duration = 0.seconds,
    val requestUserAuth: Boolean = true,
    val requestNoUserAuth: Boolean = true,
    val mdocUserAuthDomain: String = "mdoc_user_auth",
    val mdocNoUserAuthDomain: String = "mdoc_no_user_auth",
    val sdJwtUserAuthDomain: String = "sdjwt_user_auth",
    val sdJwtNoUserAuthDomain: String = "sdjwt_no_user_auth",
    val sdJwtKeylessDomain: String = "sdjwt_keyless"
)
