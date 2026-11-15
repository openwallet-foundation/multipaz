package org.multipaz.openid4vci.credential

import kotlinx.serialization.json.JsonObject
import org.multipaz.crypto.PublicKey
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
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

/** [CredentialFactory] for [UtopiaMovieTicket] credentials in IETF SD-JWT format. */
class CredentialFactoryUtopiaMovieTicket : CredentialFactory {
    override val configurationId: String
        get() = "utopia_movie_ticket_sd_jwt"

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
        get() = "Movie ticket"

    override val logo: String
        get() = "movie_ticket.jpg"

    override suspend fun mint(
        systemOfRecordData: DataItem,
        authenticationKey: PublicKey?,
        credentialId: CredentialId
    ): MintedCredential {
        check(authenticationKey == null)
        val issuer = BackendEnvironment.getBaseUrl()

        val records = systemOfRecordData["records"]
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

        val ticketJson = ticket.toJson() as JsonObject
        val claims = buildJsonObject {
            for ((k, v) in ticketJson) {
                if (k != "instance_title") {
                    put(k, v)
                }
            }
            if (!ticketJson.containsKey("movie") && ticketJson.containsKey("movie_title")) {
                put("movie", ticketJson["movie_title"]!!)
            }
        }

        val sdJwt = SdJwt.create(
            issuerKey = getSigningKey(),
            kbKey = null,
            claims = claims,
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