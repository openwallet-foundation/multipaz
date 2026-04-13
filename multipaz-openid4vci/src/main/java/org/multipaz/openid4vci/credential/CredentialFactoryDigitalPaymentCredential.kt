package org.multipaz.openid4vci.credential

import kotlinx.datetime.LocalDate
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.revocation.RevocationStatus
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import org.multipaz.util.toBase64Url
import org.multipaz.util.truncateToWholeSeconds
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * [CredentialFactory] for [DigitalPaymentCredential] credentials in ISO mdoc format.
 */
class CredentialFactoryDigitalPaymentCredential : CredentialFactory {
    override val configurationId: String
        get() = "payment_sca_mdoc"

    override val scope: String
        get() = "payment"

    override val format
        get() = FORMAT

    override val requireKeyAttestation: Boolean
        get() = false

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val cryptographicBindingMethods: List<String>
        get() = listOf("cose_key")

    override val name: String
        get() = "Payment card"

    override val logo: String
        get() = "card_payment_sca_v2.png"

    override suspend fun mint(
        systemOfRecordData: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId
    ): MintedCredential {
        val now = Clock.System.now()
        val timeSigned = now.truncateToWholeSeconds()
        val validFrom = now.truncateToWholeSeconds()
        val validUntil = validFrom + 30.days

        val coreData = systemOfRecordData["core"]
        val records = systemOfRecordData["records"]
        val paymentData = records["payment"].asMap.values.firstOrNull() ?: buildCborMap {}

        val holderName = if (paymentData.hasKey("holder_name")) {
            paymentData["holder_name"].asTstr
        } else {
            val given = coreData["given_name"].asTstr
            val family = coreData["family_name"].asTstr
            "$given $family"
        }
        val issueDate = if (paymentData.hasKey("issue_date")) {
            paymentData["issue_date"].asDateString
        } else {
            LocalDate.parse("2026-01-01")
        }
        val expiryDate = if (paymentData.hasKey("expiry_date")) {
            paymentData["expiry_date"].asDateString
        } else {
            LocalDate.parse("2031-01-01")
        }

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(DigitalPaymentCredential.CARD_NAMESPACE) {
                addDataElement("issuer_name", paymentData.getValueOrDefault("issuer_name", "Utopia Bank"))
                addDataElement(
                    "payment_instrument_id",
                    paymentData.getValueOrDefault("payment_instrument_id", "pi-77AABBCC")
                )
                addDataElement(
                    "masked_account_reference",
                    paymentData.getValueOrDefault("masked_account_reference", "****1234")
                )
                addDataElement("holder_name", Tstr(holderName))
                addDataElement("issue_date", issueDate.toDataItemFullDate())
                addDataElement("expiry_date", expiryDate.toDataItemFullDate())
            }
        }

        val baseUrl = BackendEnvironment.getBaseUrl()
        val revocationStatus = RevocationStatus.StatusList(
            idx = credentialId.index,
            uri = "$baseUrl/status_list/${credentialId.bucket}",
            certificate = null
        )

        val mso = MobileSecurityObject(
            version = "1.0",
            docType = DigitalPaymentCredential.CARD_DOCTYPE,
            signedAt = timeSigned,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = authenticationKey!!,
            revocationStatus = revocationStatus
        )
        val taggedEncodedMso = Cbor.encode(
            Tagged(
                Tagged.ENCODED_CBOR,
                Bstr(Cbor.encode(mso.toDataItem()))
            )
        )

        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_ALG),
                Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
            )
        )
        val signingKey = getSigningKey()
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                signingKey.certChain.toDataItem()
            )
        )
        val encodedIssuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                signingKey,
                taggedEncodedMso,
                true,
                protectedHeaders,
                unprotectedHeaders
            ).toDataItem()
        )
        val issuerProvidedAuthenticationData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", RawCbor(encodedIssuerAuth))
            }
        )

        return MintedCredential(
            credential = issuerProvidedAuthenticationData.toBase64Url(),
            creation = validFrom,
            expiration = validUntil
        )
    }

    override suspend fun display(systemOfRecordData: DataItem): CredentialDisplay =
        CredentialDisplay.create(
            enrichDisplayData(systemOfRecordData),
            "credential_payment"
        )

    private fun DataItem.getValueOrDefault(name: String, defaultValue: String): DataItem {
        if (this.hasKey(name)) {
            return this[name]
        }
        return Tstr(defaultValue)
    }

    private fun enrichDisplayData(systemOfRecordData: DataItem): DataItem {
        val records = if (systemOfRecordData.hasKey("records")) {
            systemOfRecordData["records"]
        } else {
            buildCborMap {}
        }
        val paymentRecords = if (records.hasKey("payment")) {
            records["payment"]
        } else {
            buildCborMap {}
        }
        val paymentData = paymentRecords.asMap.values.firstOrNull() ?: buildCborMap {}
        val maskedAccountReference = if (paymentData.hasKey("masked_account_reference")) {
            paymentData["masked_account_reference"].asTstr
        } else {
            "**** 5678"
        }
        val expiryShort = if (paymentData.hasKey("expiry_date")) {
            formatExpiryMonthYear(paymentData["expiry_date"].asDateString.toString())
        } else {
            "12/31"
        }

        return buildCborMap {
            put("core", systemOfRecordData["core"])
            put("records", records)
            put(
                "display",
                buildCborMap {
                    put("masked_account_reference", Tstr(maskedAccountReference))
                    put("expiry_short", Tstr(expiryShort))
                }
            )
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
        private val FORMAT = CredentialFormat.Mdoc(DigitalPaymentCredential.CARD_DOCTYPE)
    }
}
