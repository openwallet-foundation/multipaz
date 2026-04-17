package org.multipaz.server.presentment

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.multipaz.trustmanagement.TrustResult

/**
 * Verification result for a single SD-JWT credential within a presentation.
 *
 * @property id credential identifier from the VP token.
 * @property trustResult result of verifying the issuer certificate chain.
 * @property vct verifiable credential type identifier.
 * @property claimMap disclosed claims from the SD-JWT after selective disclosure verification.
 * @property transactionResults verified transaction data responses keyed by transaction type
 *  identifier, or `null` if no transaction response was sent. Note that transaction
 *  data in the request is always verified when [PresentmentRecord.verify] is called.
 */
data class PresentmentResultSdJwt(
    override val id: String?,
    override val trustResult: TrustResult,
    val vct: String,
    val claimMap: JsonObject,
    val transactionResults: Map<String, JsonElement>?
): PresentmentResult()