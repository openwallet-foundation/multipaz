package org.multipaz.openid4vci.credential

import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.knowntypes.UtopiaNaturalization
import org.multipaz.rpc.backend.BackendEnvironment
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.sdjwt.SdJwt
import org.multipaz.server.getBaseUrl
import kotlin.time.Duration.Companion.days

internal class CredentialFactoryUtopiaNaturatization : CredentialFactoryBase() {
    override val offerId: String
        get() = "utopia_naturalization"

    override val scope: String
        get() = "naturalization"

    override val format: Openid4VciFormat
        get() = FORMAT

    override val requireKeyAttestation: Boolean get() = false

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val cryptographicBindingMethods: List<String>
        get() = listOf("jwk")

    override val name: String
        get() = "Utopia Naturalization Certificate"

    override val logo: String
        get() = "naturalization.png"

    override suspend fun mint(
        data: DataItem,
        authenticationKey: EcPublicKey?,
        credentialIndex: Int,
        statusListUrl: String
    ): MintedCredential {
        check(authenticationKey != null)
        val issuer = BackendEnvironment.getBaseUrl()
        val coreData = data["core"]

        val records = data["records"]
        if (!records.hasKey("naturalization")) {
            throw IllegalArgumentException("No naturalization record for this person")
        }
        val nzData = records["naturalization"].asMap.values.firstOrNull() ?: buildCborMap { }

        val identityAttributes = buildJsonObject {
            put("given_name", coreData["given_name"].asTstr)
            put("family_name", coreData["family_name"].asTstr)
            put("birth_date", coreData["birth_date"].asDateString.toString())
            if (nzData.hasKey("naturalization_date")) {
                put("naturalization_date", nzData["naturalization_date"].asDateString.toString())
            }
        }

        val now = Clock.System.now()

        val timeSigned = now
        val validFrom = now
        val validUntil = now + 30.days

        val sdJwt = SdJwt.create(
            issuerKey = signingKey,
            kbKey = authenticationKey,
            claims = identityAttributes,
            nonSdClaims = buildJsonObject {
                put("iss", issuer)
                put("vct", UtopiaNaturalization.VCT)
                put("iat", timeSigned.epochSeconds)
                put("nbf", validFrom.epochSeconds)
                put("exp", validUntil.epochSeconds)
                putJsonObject("status") {
                    putJsonObject("status_list") {
                        put("idx", credentialIndex)
                        put("uri", statusListUrl)
                    }
                }
            }
        )

        return MintedCredential(
            credential = sdJwt.compactSerialization,
            creation = validFrom,
            expiration = validUntil
        )
    }

    companion object {
        private val FORMAT = Openid4VciFormatSdJwt(UtopiaNaturalization.VCT)
    }
}