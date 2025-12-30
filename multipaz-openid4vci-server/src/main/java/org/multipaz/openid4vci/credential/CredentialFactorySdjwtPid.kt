package org.multipaz.openid4vci.credential

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearsUntil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborDouble
import org.multipaz.cbor.CborFloat
import org.multipaz.cbor.CborInt
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.IndefLengthBstr
import org.multipaz.cbor.IndefLengthTstr
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.revocation.RevocationStatus
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources
import org.multipaz.sdjwt.SdJwt
import org.multipaz.server.common.getBaseUrl
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import kotlin.collections.List
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** EU PID credential */
internal class CredentialFactorySdjwtPid : CredentialFactory {
    override val offerId: String
        get() = "pid_sd_jwt"

    override val scope: String
        get() = "core"

    override val format
        get() = FORMAT

    override val requireKeyAttestation: Boolean get() = true

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val cryptographicBindingMethods: List<String>
        get() = listOf("jwk")

    override val name: String
        get() = "Personal ID (SD-JWT)"

    override val logo: String
        get() = "card-pid.png"

    override suspend fun mint(
        data: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId,
    ): MintedCredential {
        check(authenticationKey != null)

        val coreData = data["core"]
        val dateOfBirth = coreData["birth_date"].asDateString

        val documentType = EUPersonalID.getDocumentType().jsonDocumentType!!

        val resources = BackendEnvironment.getInterface(Resources::class)!!
        val now = Clock.System.now()

        val timeZone = TimeZone.currentSystemDefault()
        val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)

        val identityAttributes = buildJsonObject {
            put("given_name", coreData["given_name"].asTstr)
            put("family_name", coreData["family_name"].asTstr)

            val added = mutableSetOf("birthdate", "given_name", "family_name", "picture")
            put("birthdate", dateOfBirth.toString())

            if (coreData.hasKey("portrait")) {
                put("picture", coreData["portrait"].asBstr.toBase64Url())
            } else {
                val bytes = resources.getRawResource("female.jpg")!!
                put("picture", bytes.toByteArray().toBase64Url())
            }

            if (coreData.hasKey("utopia_id_number")) {
                put("personal_administrative_number",
                    coreData["utopia_id_number"].asTstr)
                added.add("personal_administrative_number")
            }

            // Transfer core fields that have counterparts in the PID credential
            for ((nameItem, value) in coreData.asMap) {
                val name = nameItem.asTstr
                val attribute = documentType.claims[name]
                if (!added.contains(name) && attribute != null) {
                    val typeMatches = when (attribute.type) {
                        DocumentAttributeType.Blob, DocumentAttributeType.Picture ->
                            value is Bstr || value is IndefLengthBstr
                        DocumentAttributeType.Boolean ->
                            value == Simple.TRUE || value == Simple.FALSE
                        DocumentAttributeType.String, is DocumentAttributeType.StringOptions ->
                            value is Tstr || value is IndefLengthTstr
                        DocumentAttributeType.Number, is DocumentAttributeType.IntegerOptions ->
                            value is CborInt || value is CborFloat || value is CborDouble
                        DocumentAttributeType.Date ->
                            value is Tagged && value.tagNumber == Tagged.FULL_DATE_STRING
                        DocumentAttributeType.DateTime ->
                            value is Tagged && value.tagNumber == Tagged.DATE_TIME_STRING
                        is DocumentAttributeType.ComplexType -> true
                    }
                    if (typeMatches) {
                        put(name, value.toJson())
                    } else {
                        Logger.e(TAG, "Skipped '$name': type mismatch")
                    }
                }
            }

            // Values derived from the birth_date
            put("age_in_years", dateOfBirth.yearsUntil(now.toLocalDateTime(timeZone).date))
            put("age_birth_year", dateOfBirth.year)
            putJsonObject("age_equal_or_over") {
                for (age in listOf(14, 18, 21)) {
                    val isOver = now > dateOfBirthInstant.plus(age, DateTimeUnit.YEAR, timeZone)
                    put(age.toString(), isOver)
                }
            }
            added.addAll(listOf("age_in_years", "age_birth_year", "age_equal_or_over"))

            // Add all mandatory elements for completeness.
            for ((elementName, data) in documentType.claims) {
                if (!added.contains(elementName)) {
                    data.sampleValueJson?.let { put(elementName, it) }
                }
            }

        }

        val timeSigned = now
        val validFrom = Clock.System.now()
        val validUntil = validFrom + 30.days
        val issuer = BackendEnvironment.getBaseUrl()

        val baseUrl = BackendEnvironment.getBaseUrl()
        val revocationStatus = RevocationStatus.StatusList(
            idx = credentialId.index,
            uri = "$baseUrl/status_list/${credentialId.bucket}",
            certificate = null
        )

        val sdJwt = SdJwt.create(
            issuerKey = getSigningKey(),
            kbKey = authenticationKey,
            claims = identityAttributes,
            nonSdClaims = buildJsonObject {
                put("iss", issuer)
                put("vct", documentType.vct)
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
        CredentialDisplay.create(systemOfRecordData, "credential_pid")

    companion object {
        private val FORMAT = CredentialFormat.SdJwt(EUPersonalID.EUPID_VCT)
        private const val TAG = "CredentialFactorySdjwtPid"
    }
}