package org.multipaz.openid4vci.credential

import kotlinx.serialization.json.JsonObject
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.knowntypes.UtopiaMovieTicket
import kotlin.time.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.revocation.RevocationStatus
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.sdjwt.SdJwt
import org.multipaz.server.common.getBaseUrl
import kotlin.time.Duration.Companion.days

internal class CredentialFactoryUtopiaMovieTicket : CredentialFactory {
    override val offerId: String
        get() = "utopia_movie_ticket"

    override val scope: String
        get() = "movie"

    override val format
        get() = FORMAT

    override val requireKeyAttestation: Boolean get() = false

    override val proofSigningAlgorithms: List<String>
        get() = listOf()  // keyless

    override val cryptographicBindingMethods: List<String>
        get() = listOf()  // keyless

    override val name: String
        get() = "Utopia Movie Ticket"

    override val logo: String
        get() = "movie_ticket.png"

    override suspend fun mint(
        data: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId
    ): MintedCredential {
        check(authenticationKey == null)
        val issuer = BackendEnvironment.getBaseUrl()

        val records = data["records"]
        if (!records.hasKey("movie")) {
            throw IllegalArgumentException("No movie ticket for this person")
        }
        val ticket = records["movie"].asMap.values.firstOrNull() ?: buildCborMap { }

        val now = Clock.System.now()

        val timeSigned = now
        val validFrom = now
        val validUntil = now + 30.days

        val baseUrl = BackendEnvironment.getBaseUrl()
        val revocationStatus = RevocationStatus.StatusList(
            idx = credentialId.index,
            uri = "$baseUrl/status_list/${credentialId.bucket}",
            certificate = null
        )

        val sdJwt = SdJwt.create(
            issuerKey = getSigningKey(),
            kbKey = null,
            claims = ticket.toJson() as JsonObject,
            nonSdClaims = buildJsonObject {
                put("iss", issuer)
                put("vct", UtopiaMovieTicket.MOVIE_TICKET_VCT)
                put("iat", timeSigned.epochSeconds)
                put("nbf", validFrom.epochSeconds)
                put("exp", validUntil.epochSeconds)
                put("status", revocationStatus.toJson())
            }
        )

        return MintedCredential(
            credential = sdJwt.compactSerialization,
            creation = validFrom,
            expiration = validUntil
        )
    }

    companion object {
        private val FORMAT = CredentialFormat.SdJwt(UtopiaMovieTicket.MOVIE_TICKET_VCT)
    }
}