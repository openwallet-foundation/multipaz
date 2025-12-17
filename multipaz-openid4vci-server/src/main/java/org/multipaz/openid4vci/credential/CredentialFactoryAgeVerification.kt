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
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Instant
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.buildCborMap
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.revocation.RevocationStatus
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import kotlin.time.Duration.Companion.days

/**
 * Factory for Age Verification Credential ID in ISO mdoc format.
 */
internal class CredentialFactoryAgeVerification : CredentialFactory {
    override val offerId: String
        get() = "mDoc-AgeVerification"

    override val scope: String
        get() = "core"

    override val format
        get() = credentialFormatAv

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
        credentialId: CredentialId
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

        val baseUrl = BackendEnvironment.getBaseUrl()
        val revocationStatus = RevocationStatus.StatusList(
            idx = credentialId.index,
            uri = "$baseUrl/status_list/${credentialId.bucket}",
            certificate = null
        )

        // Generate an MSO and issuer-signed data for this authentication key.
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = AgeVerification.AV_DOCTYPE,
            signedAt = timeSigned,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = authenticationKey!!,
            revocationStatus = revocationStatus
        )
        val taggedEncodedMso = Cbor.encode(Tagged(
            Tagged.ENCODED_CBOR,
            Bstr(Cbor.encode(mso.toDataItem())))
        )

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

    companion object Companion {
        private const val TAG = "CredentialFactoryAgeVerification"
    }
}