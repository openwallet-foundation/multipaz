package org.multipaz.openid4vci.credential

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Instant
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.buildCborMap
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.util.Logger
import kotlin.time.Duration.Companion.days

/**
 * Factory for Age Verification Credential ID in mDoc format.
 */
internal class CredentialFactoryAgeVerification : CredentialFactoryBase() {
    override val offerId: String
        get() = "mDoc-AgeVerification"

    override val scope: String
        get() = "core"

    override val format: Openid4VciFormat
        get() = openId4VciFormatAv

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val cryptographicBindingMethods: List<String>
        get() = listOf("cose_key")

    override val name: String
        get() = "Age Verification Credential (mDoc)"

    override val logo: String
        get() = "card-age-verification.png"

    override suspend fun mint(
        data: DataItem,
        authenticationKey: EcPublicKey?,
        credentialIndex: Int,
        statusListUrl: String
    ): MintedCredential {
        val now = Clock.System.now()

        val coreData = data["core"]
        val dateOfBirth = coreData["birth_date"].asDateString
        val timeZone = TimeZone.currentSystemDefault()
        val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)

        // Create AuthKeys and MSOs, make sure they're valid for 30 days. Also make
        // sure to not use fractional seconds as 18013-5 calls for this (clauses 7.1
        // and 9.1.2.4)
        //
        val timeSigned = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validFrom = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validUntil = validFrom + 30.days

        // Generate an MSO and issuer-signed data for this authentication key.
        val msoGenerator = MobileSecurityObjectGenerator(
            Algorithm.SHA256,
            AgeVerification.AV_DOCTYPE,
            authenticationKey!!
        )
        msoGenerator.setValidityInfo(timeSigned, validFrom, validUntil, null)

        val mdocType = AgeVerification.getDocumentType()
            .mdocDocumentType!!.namespaces[AgeVerification.AV_NAMESPACE]!!

        val ageThresholdsToProvision = listOf(13, 15, 16, 18, 21, 23, 25, 27, 28, 40, 60, 65, 67)
        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(AgeVerification.AV_NAMESPACE) {
                for (ageNum in ageThresholdsToProvision) {
                    val age = ageNum.toString().padStart(2, '0')
                    // age over is calculated purely based on calendar date (not based on the birth
                    // time zone)
                    val over = now > dateOfBirthInstant.plus(ageNum, DateTimeUnit.YEAR, timeZone)
                    addDataElement("age_over_$age", over.toDataItem())
                }
            }
        }

        msoGenerator.addValueDigests(issuerNamespaces)

        val mso = msoGenerator.generate()
        val taggedEncodedMso = Cbor.encode(Tagged(24, Bstr(mso)))

        // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
        //
        // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
        //
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

        return MintedCredential(
            credential = issuerProvidedAuthenticationData.toBase64Url(),
            creation = validFrom,
            expiration = validUntil
        )
    }

    companion object Companion {
        const val TAG = "CredentialFactoryAgeVerification"
    }
}