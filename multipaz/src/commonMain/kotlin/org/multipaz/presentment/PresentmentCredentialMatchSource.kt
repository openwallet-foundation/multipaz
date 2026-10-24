package org.multipaz.presentment

import org.multipaz.mdoc.request.DocRequest
import org.multipaz.openid.dcql.DcqlCredentialQuery

/**
 * Base class for source of a match.
 */
sealed class PresentmentCredentialMatchSource

/**
 * OpenID4VP version for the source of a match.
 *
 * @property credentialQuery The [DcqlCredentialQuery] which was used for this match.
 */
data class PresentmentCredentialMatchSourceOpenID4VP(
    val credentialQuery: DcqlCredentialQuery
): PresentmentCredentialMatchSource()

/**
 * ISO 18013-5 version for the source of a match.
 *
 * @property docRequest The [DocRequest] which was used for this match.
 */
data class PresentmentCredentialMatchSourceIso18013(
    val docRequest: DocRequest
): PresentmentCredentialMatchSource()
