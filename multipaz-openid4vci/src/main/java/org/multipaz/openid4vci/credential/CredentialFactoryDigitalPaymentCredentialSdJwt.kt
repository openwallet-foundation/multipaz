package org.multipaz.openid4vci.credential

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.revocation.RevocationStatus
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.sdjwt.SdJwt
import org.multipaz.server.common.getBaseUrl
import org.multipaz.utopia.knowntypes.DigitalPaymentCredentialSdJwt
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** [CredentialFactory] for [DigitalPaymentCredentialSdJwt] credentials in EMVCo DPC SD-JWT format. */
class CredentialFactoryDigitalPaymentCredentialSdJwt : CredentialFactory {
    override val configurationId: String
        get() = "payment_sca_sd_jwt"

    override val scope: String
        get() = "payment"

    override val format
        get() = FORMAT

    override val requireKeyAttestation: Boolean
        get() = true

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val cryptographicBindingMethods: List<String>
        get() = listOf("jwk")

    override val name: String
        get() = "Payment card"

    override val logo: String
        get() = "card_payment_sca_v2.jpg"

    override suspend fun mint(
        systemOfRecordData: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId
    ): MintedCredential {
        check(authenticationKey != null)
        val issuer = BackendEnvironment.getBaseUrl()

        val records = systemOfRecordData["records"]
        if (!records.hasKey("payment")) {
            throw IllegalArgumentException("No payment card for this person")
        }
        val paymentData = records["payment"].asMap.values.firstOrNull() ?: buildCborMap {}

        val accountNumber = if (paymentData.hasKey("account_number")) {
            paymentData["account_number"].asTstr
        } else {
            "01234567"
        }
        val maskedLength = max(0, accountNumber.length - 4)
        val lastFour = accountNumber.substring(maskedLength)

        val network = if (paymentData.hasKey("network")) {
            paymentData["network"].asTstr
        } else {
            "multipaz"
        }

        val now = Clock.System.now()
        val timeSigned = now
        val validFrom = now
        val validUntil = validFrom + 30.days

        val baseUrl = BackendEnvironment.getBaseUrl()
        val revocationStatus = RevocationStatus.StatusList(
            idx = credentialId.index,
            uri = "$baseUrl/status_list/${credentialId.bucket}",
            certificate = null
        )

        val claimsObj = buildJsonObject {
            put("credential_id", "urn:multipaz:cred:${credentialId.bucket}:${credentialId.index}")
            put("network", network)
            put("last_four", lastFour)
        }

        val sdJwt = SdJwt.create(
            issuerKey = getSigningKey(),
            kbKey = authenticationKey,
            claims = claimsObj,
            nonSdClaims = buildJsonObject {
                put("iss", issuer)
                put("vct", DigitalPaymentCredentialSdJwt.CARD_VCT)
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

    override suspend fun display(systemOfRecordData: DataItem): CredentialDisplay =
        CredentialDisplay.create(
            extractData(systemOfRecordData),
            "credential_payment"
        )

    private fun extractData(systemOfRecordData: DataItem): DataItem {
        val records = systemOfRecordData["records"]
        val paymentData = records["payment"].asMap.values.firstOrNull() ?: buildCborMap {}

        val instanceTitle = if (paymentData.hasKey("instance_title")) {
            paymentData["instance_title"].asTstr
        } else {
            "Pay Card"
        }
        val issuerName = if (paymentData.hasKey("issuer_name")) {
            paymentData["issuer_name"].asTstr
        } else {
            "Bank of Utopia"
        }
        val holderName = if (paymentData.hasKey("holder_name")) {
            paymentData["holder_name"].asTstr
        } else {
            "Utopia User"
        }
        val issueDate = if (paymentData.hasKey("issue_date")) {
            paymentData["issue_date"]
        } else {
            LocalDate.parse("2026-01-01").toDataItemFullDate()
        }
        val expiryDate = if (paymentData.hasKey("expiry_date")) {
            paymentData["expiry_date"]
        } else {
            LocalDate.parse("2031-01-01").toDataItemFullDate()
        }
        val accountNumber = if (paymentData.hasKey("account_number")) {
            paymentData["account_number"].asTstr
        } else {
            "01234567"
        }

        val maskedLength = max(0, accountNumber.length - 4)
        val lastFour = accountNumber.substring(maskedLength)
        val maskedAccountReference = lastFour.padStart(accountNumber.length, '*')

        val expiryShort = formatExpiryMonthYear(expiryDate.asDateString.toString())

        return buildCborMap {
            put("instance_title", instanceTitle)
            put("issuer_name", issuerName)
            put("holder_name", holderName)
            put("given_name", "Utopia")
            put("account_number", accountNumber)
            put("issue_date", issueDate)
            put("expiry_date", expiryDate)
            put("masked_account_reference", maskedAccountReference)
            put("expiry_short", expiryShort)
        }
    }

    private fun formatExpiryMonthYear(isoDate: String): String {
        // Convert YYYY-MM-DD to MM/YY for payment-card display.
        if (isoDate.length >= 10 && isoDate[4] == '-' && isoDate[7] == '-') {
            return "${isoDate.substring(5, 7)}/${isoDate.substring(2, 4)}"
        }
        return isoDate
    }

    companion object {
        private val FORMAT = CredentialFormat.SdJwt(DigitalPaymentCredentialSdJwt.CARD_VCT)
    }
}
