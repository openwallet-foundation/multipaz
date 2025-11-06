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
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.knowntypes.Loyalty
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Factory for LoyaltyID credentials according to ISO/IEC TS 23220-4 (E) operational phase - Annex C Photo ID v2
 */
internal class CredentialFactoryUtopiaLoyalty : CredentialFactoryBase() {
    override val offerId: String
        get() = "utopia_wholesale"

    override val scope: String
        get() = "wholesale"

    override val format: Openid4VciFormat
        get() = openId4VciFormatLoyalty

    override val requireClientAttestation: Boolean get() = false

    override val requireKeyAttestation: Boolean get() = false

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val cryptographicBindingMethods: List<String>
        get() = listOf("cose_key")

    override val name: String
        get() = "Utopia Wholesale Loyalty ID"

    override val logo: String
        get() = "card_utopia_wholesale.png"

    override suspend fun makeCredential(
        data: DataItem,
        authenticationKey: EcPublicKey?
    ): String {
        val now = Clock.System.now()

        val resources = BackendEnvironment.getInterface(Resources::class)!!

        val coreData = data["core"]
        val portrait = if (coreData.hasKey("portrait")) {
            coreData["portrait"].asBstr
        } else {
            resources.getRawResource("female.jpg")!!.toByteArray()
        }

        // Create AuthKeys and MSOs, make sure they're valid for 30 days
        val timeSigned = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validFrom = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validUntil = validFrom + 30.days

        // Generate an MSO and issuer-signed data for this authentication key.
        val docType = Loyalty.LOYALTY_DOCTYPE
        val msoGenerator = MobileSecurityObjectGenerator(
            Algorithm.SHA256,
            docType,
            authenticationKey!!
        )
        msoGenerator.setValidityInfo(timeSigned, validFrom, validUntil, null)

        val records = data["records"]
        if (!records.hasKey("wholesale")) {
            throw IllegalArgumentException("No wholesale membership card is issued to this person")
        }
        val loyaltyIDData = records["wholesale"].asMap.values.firstOrNull() ?: buildCborMap { }
        val membershipId = if (loyaltyIDData.hasKey("membership_number")) {
            loyaltyIDData["membership_number"].asTstr
        } else {
            (1000000 + Random.nextInt(9000000)).toString()
        }
        val tier = if (loyaltyIDData.hasKey("tier")) {
            loyaltyIDData["tier"].asTstr
        } else {
            "basic"
        }
        val issueDate = if (loyaltyIDData.hasKey("issue_date")) {
            loyaltyIDData["issue_date"].asDateString
        } else {
            LocalDate.parse("2024-04-01")
        }
        val expiryDate = if (loyaltyIDData.hasKey("expiry_date")) {
            loyaltyIDData["expiry_date"].asDateString
        } else {
            LocalDate.parse("2034-04-01")
        }

        val issuerNamespaces = buildIssuerNamespaces {
            // Combined LoyaltyID namespace (all data elements)
            addNamespace(Loyalty.LOYALTY_NAMESPACE) {
                // Core personal data
                addDataElement("family_name", coreData["family_name"])
                addDataElement("given_name", coreData["given_name"])
                addDataElement("portrait", Bstr(portrait))

                // LoyaltyID specific data
                addDataElement("membership_number", Tstr(membershipId))
                addDataElement("tier", Tstr(tier))
                addDataElement("issue_date", issueDate.toDataItemFullDate())
                addDataElement("expiry_date", expiryDate.toDataItemFullDate())
            }
        }

        msoGenerator.addValueDigests(issuerNamespaces)

        val mso = msoGenerator.generate()
        val taggedEncodedMso = Cbor.encode(Tagged(24, Bstr(mso)))

        // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_ALG),
                Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
            )
        )
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

        return issuerProvidedAuthenticationData.toBase64Url()
    }

    companion object Companion {
        const val TAG = "CredentialFactoryUtopiaLoyalty"
    }
}