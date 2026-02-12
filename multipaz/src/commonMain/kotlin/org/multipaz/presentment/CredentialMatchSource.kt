package org.multipaz.presentment

import org.multipaz.mdoc.request.DocRequest
import org.multipaz.openid.dcql.DcqlCredentialQuery

/**
 * Base class for source of a match.
 */
sealed class CredentialMatchSource

/**
 * OpenID4VP version for the source of a match.
 *
 * @property credentialQuery The [DcqlCredentialQuery] which was used for this match.
 */
data class CredentialMatchSourceOpenID4VP(
    val credentialQuery: DcqlCredentialQuery
): CredentialMatchSource()

/**
 * ISO 18013-5 version for the source of a match.
 *
 * @property docRequest The [DocRequest] which was used for this match.
 */
data class CredentialMatchSourceIso18013(
    val docRequest: DocRequest
): CredentialMatchSource()
