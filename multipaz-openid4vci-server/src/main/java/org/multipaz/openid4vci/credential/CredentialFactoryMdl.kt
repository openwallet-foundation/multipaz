package org.multipaz.openid4vci.credential

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
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearsUntil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.revocation.RevocationStatus
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.rpc.backend.Configuration
import org.multipaz.server.common.getBaseUrl
import org.multipaz.util.Logger
import org.multipaz.util.truncateToWholeSeconds
import kotlin.time.Duration.Companion.days

/**
 * Factory for Driver's License credentials.
 */
internal class CredentialFactoryMdl : CredentialFactory {
    override val offerId: String
        get() = "mDL"

    override val scope: String
        get() = "mDL"

    override val format
        get() = credentialFormatMdl

    override val proofSigningAlgorithms: List<String>
        get() = CredentialFactory.DEFAULT_PROOF_SIGNING_ALGORITHMS

    override val cryptographicBindingMethods: List<String>
        get() = listOf("cose_key")

    override val name: String
        get() = "Driver License (mDL)"

    override val logo: String
        get() = "card-mdl.png"

    override suspend fun mint(
        data: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId
    ): MintedCredential {
        val now = Clock.System.now()

        val coreData = data["core"]
        val dateOfBirth = coreData["birth_date"].asDateString
        val records = data["records"]
        if (!records.hasKey("mDL")) {
            throw IllegalArgumentException("No driver's license was issued to this person")
        }
        val mdlData = records["mDL"].asMap.values.firstOrNull() ?: buildCborMap { }

        // Create AuthKeys and MSOs, make sure they're valid for 30 days. Also make
        // sure to not use fractional seconds as 18013-5 calls for this (clauses 7.1
        // and 9.1.2.4)
        //
        val timeSigned = now.truncateToWholeSeconds()
        val validFrom = now.truncateToWholeSeconds()
        val validUntil = validFrom + 30.days

        val resources = BackendEnvironment.getInterface(Resources::class)!!

        val mdocType = DrivingLicense.getDocumentType()
            .mdocDocumentType!!.namespaces[DrivingLicense.MDL_NAMESPACE]!!

        val timeZone = TimeZone.currentSystemDefault()
        val dateOfBirthInstant = dateOfBirth.atStartOfDayIn(timeZone)
        // over 18/21 is calculated purely based on calendar date (not based on the birth time zone)
        val ageOver18 = now > dateOfBirthInstant.plus(18, DateTimeUnit.YEAR, timeZone)
        val ageOver21 = now > dateOfBirthInstant.plus(21, DateTimeUnit.YEAR, timeZone)

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(DrivingLicense.MDL_NAMESPACE) {
                val added = mutableSetOf("birth_date")
                addDataElement("birth_date", dateOfBirth.toDataItemFullDate())

                // Transfer fields from mDL record that have counterparts in the mDL credential
                for ((nameItem, value) in mdlData.asMap) {
                    val name = nameItem.asTstr
                    if (!added.contains(name) && mdocType.dataElements.contains(name)) {
                        addDataElement(name, value)
                        added.add(name)
                    }
                }

                if (coreData.hasKey("given_name_unicode") && !added.contains("given_name_national_character")) {
                    addDataElement("given_name_national_character", coreData["given_name_unicode"])
                    added.add("given_name_national_character")
                }

                if (coreData.hasKey("family_name_unicode") && !added.contains("family_name_national_character")) {
                    addDataElement("family_name_national_character", coreData["family_name_unicode"])
                    added.add("family_name_national_character")
                }

                if (coreData.hasKey("address")) {
                    val addressObject = coreData["address"]
                    if (addressObject.hasKey("formatted")) {
                        addDataElement("resident_address", addressObject["formatted"])
                        added.add("resident_address")
                    }
                    if (addressObject.hasKey("country")) {
                        addDataElement("resident_country", addressObject["country"])
                        added.add("resident_country")
                    }
                    if (addressObject.hasKey("region")) {
                        addDataElement("resident_state", addressObject["region"])
                        added.add("resident_state")
                    }
                    if (addressObject.hasKey("locality")) {
                        addDataElement("resident_city", addressObject["locality"])
                        added.add("resident_city")
                    }
                    if (addressObject.hasKey("postal_code")) {
                        addDataElement("resident_postal_code", addressObject["postal_code"])
                        added.add("postal_code")
                    }
                    // TODO: enable this once resident_street is added to mDL
                    /*
                    if (addressObject.hasKey("street")) {
                        // For mDL this is street address, format is using American conventions
                        // for now
                        val value = buildString {
                            if (addressObject.hasKey("house_number")) {
                                append(addressObject["house_number"].asTstr)
                                append(' ')
                            }
                            append(addressObject["street"].asTstr)
                            if (addressObject.hasKey("unit")) {
                                append(" #")
                                append(addressObject["unit"].asTstr)
                            }
                        }
                        addDataElement("resident_street", value.toDataItem())
                        added.add("resident_street")
                    }
                     */
                }

                // Transfer core fields that have counterparts in the mDL credential
                for ((nameItem, value) in coreData.asMap) {
                    val name = nameItem.asTstr
                    if (!added.contains(name) && mdocType.dataElements.contains(name)) {
                        addDataElement(name, value)
                        added.add(name)
                    }
                }

                if (!added.contains("portrait")) {
                    addDataElement(
                        dataElementName = "portrait",
                        value = Bstr(resources.getRawResource("female.jpg")!!.toByteArray())
                    )
                    added.add("portrait")
                }

                // Values derived from the birth_date
                addDataElement("age_in_years",
                    Uint(dateOfBirth.yearsUntil(now.toLocalDateTime(timeZone).date).toULong()))
                addDataElement("age_birth_year", Uint(dateOfBirth.year.toULong()))
                addDataElement("age_over_18", if (ageOver18) Simple.TRUE else Simple.FALSE)
                addDataElement( "age_over_21", if (ageOver21) Simple.TRUE else Simple.FALSE)

                // Add all mandatory elements for completeness if they are missing.
                for ((elementName, data) in mdocType.dataElements) {
                    if (!data.mandatory || added.contains(elementName)) {
                        continue
                    }
                    val value = data.attribute.sampleValueMdoc
                    if (value != null) {
                        addDataElement(elementName, value)
                    } else {
                        Logger.e(TAG, "Could not fill '$elementName': no sample data")
                    }
                }
            }
            if (mdlData.hasKey("issuing_country") && mdlData["issuing_country"].asTstr == "US") {
                addNamespace(DrivingLicense.AAMVA_NAMESPACE) {
                    if (coreData.hasKey("address")) {
                        val addressObject = coreData["address"]
                        if (addressObject.hasKey("us_county_code")) {
                            addDataElement("resident_county", addressObject["us_county_code"])
                        }
                    }
                    // Add other US-specific values, just make them up for now
                    addDataElement("DHS_compliance", Tstr("F"))
                    addDataElement("EDL_credential", 1.toDataItem())
                }
            }
        }

        val baseUrl = BackendEnvironment.getBaseUrl()
        val revocationStatus = RevocationStatus.IdentifierList(
            id = CredentialState.indexToIdentifier(credentialId.index),
            uri = "$baseUrl/identifier_list/${credentialId.bucket}",
            certificate = null
        )

        // Generate an MSO and issuer-signed data for this authentication key.
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = DrivingLicense.MDL_DOCTYPE,
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

    override suspend fun display(systemOfRecordData: DataItem): CredentialDisplay =
        CredentialDisplay.create(systemOfRecordData, "credential_mdl")

    companion object {
        private const val TAG = "CredentialFactoryMdl"
    }
}