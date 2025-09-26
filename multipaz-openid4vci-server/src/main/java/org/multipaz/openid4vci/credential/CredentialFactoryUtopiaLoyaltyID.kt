package org.multipaz.openid4vci.credential

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearsUntil
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
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
import org.multipaz.documenttype.knowntypes.LoyaltyID
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Factory for LoyaltyID credentials according to ISO/IEC TS 23220-4 (E) operational phase - Annex C Photo ID v2
 */
internal class CredentialFactoryUtopiaLoyaltyID : CredentialFactoryBase() {
    override val offerId: String
        get() = "utopia_wholesale"

    override val scope: String
        get() = "wholesale"

    override val format: Openid4VciFormat
        get() = openId4VciFormatLoyaltyId

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
        val dateOfBirth = coreData["birth_date"].asDateString
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
        val docType = LoyaltyID.LOYALTY_ID_DOCTYPE
        val msoGenerator = MobileSecurityObjectGenerator(
            Algorithm.SHA256,
            docType,
            authenticationKey!!
        )
        msoGenerator.setValidityInfo(timeSigned, validFrom, validUntil, null)

        val timeZone = TimeZone.currentSystemDefault()
        val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)
        // Calculate age-based flags
        val ageOver18 = now > dateOfBirthInstant.plus(18, DateTimeUnit.YEAR, timeZone)
        val ageOver21 = now > dateOfBirthInstant.plus(21, DateTimeUnit.YEAR, timeZone)
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
            addNamespace(LoyaltyID.LOYALTY_ID_NAMESPACE) {
                // Core personal data
                addDataElement("family_name", coreData["family_name"])
                addDataElement("given_name", coreData["given_name"])
                addDataElement("portrait", Bstr(portrait))
                addDataElement("birth_date", dateOfBirth.toDataItemFullDate())

                // Age-based flags
                addDataElement("age_over_18", if (ageOver18) Simple.TRUE else Simple.FALSE)
                addDataElement("age_over_21", if (ageOver21) Simple.TRUE else Simple.FALSE)

                // LoyaltyID specific data
                addDataElement("membership_number", Tstr(membershipId))
                addDataElement("issue_date", issueDate.toDataItemFullDate())
                addDataElement("expiry_date", expiryDate.toDataItemFullDate())
            }
        }

        msoGenerator.addValueDigests(issuerNamespaces)

        val documentSigningKeyCert = X509Cert.fromPem(
            resources.getStringResource("ds_certificate.pem")!!
        )
        val documentSigningKey = EcPrivateKey.fromPem(
            resources.getStringResource("ds_private_key.pem")!!,
            documentSigningKeyCert.ecPublicKey
        )

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
                X509CertChain(listOf(documentSigningKeyCert)).toDataItem()
            )
        )
        val encodedIssuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                documentSigningKey,
                taggedEncodedMso,
                true,
                documentSigningKey.publicKey.curve.defaultSigningAlgorithm,
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
        const val TAG = "CredentialFactoryUtopiaLoyaltyID"
    }
}