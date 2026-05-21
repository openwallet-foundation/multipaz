package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.util.fromBase64Url
import kotlinx.datetime.LocalDate
import org.multipaz.documenttype.DocumentAttributeSensitivity

/**
 * Object containing the metadata of the Aadhaar Document Type.
 * Reference: https://docs.uidai.gov.in/readme/verifiable-credential-specifications/iso-18013-5-aadhaar-mdoc-specs
 */
object Aadhaar {
    /** The ISO mdoc docType used for Aadhaar. */
    const val AADHAAR_DOCTYPE = "in.gov.uidai.aadhaar.1"

    /** The ISO mdoc namespace used for Aadhaar. */
    const val AADHAAR_NAMESPACE = "in.gov.uidai.aadhaar.1"

    /**
     * Build the Aadhaar Document Type.
     */
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Aadhaar")
            .addMdocDocumentType(AADHAAR_DOCTYPE)
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "CredentialIssuingDate",
                displayName = "Credential issuing date",
                description = "Date of credential issuance",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = LocalDate.parse("2023-01-01").toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "EnrolmentDate",
                displayName = "Enrollment date",
                description = "Date of enrollment",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.TODAY,
                sampleValue = LocalDate.parse("2023-01-01").toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "EnrolmentNumber",
                displayName = "Enrollment number",
                description = "Enrollment number",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValue = "1234567890".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "IsNRI",
                displayName = "Is NRI",
                description = "Resident is NRI",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.GLOBE,
                sampleValue = false.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Picture,
                identifier = "ResidentImage",
                displayName = "Photo",
                description = "Photo of the resident",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.PORTRAIT_IMAGE,
                icon = Icon.ACCOUNT_BOX,
                sampleValue = SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "ResidentName",
                displayName = "Name",
                description = "Resident name",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = SampleData.GIVEN_NAME.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalResidentName",
                displayName = "Local name",
                description = "Resident name in local language",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = SampleData.GIVEN_NAME.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "AgeAbove18",
                displayName = "Age above 18",
                description = "Age above 18",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = true.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "AgeAbove50",
                displayName = "Age above 50",
                description = "Age above 50",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = true.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "AgeAbove60",
                displayName = "Age above 60",
                description = "Age above 60",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = true.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "AgeAbove75",
                displayName = "Age above 75",
                description = "Age above 75",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.AGE_INFORMATION,
                icon = Icon.TODAY,
                sampleValue = true.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "Dob",
                displayName = "Date of birth",
                description = "Date of birth",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.TODAY,
                sampleValue = LocalDate.parse("1990-01-01").toDataItemFullDate()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Gender",
                displayName = "Gender",
                description = "Gender",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PERSON,
                sampleValue = "M".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Building",
                displayName = "Building",
                description = "Building",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Building 1".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalBuilding",
                displayName = "Local building",
                description = "Local building",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Building 1".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Locality",
                displayName = "Locality",
                description = "Locality",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Locality 1".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalLocality",
                displayName = "Local locality",
                description = "Local locality",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Locality 1".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Street",
                displayName = "Street",
                description = "Street",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Street 1".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalStreet",
                displayName = "Local street",
                description = "Local street",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Street 1".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Landmark",
                displayName = "Landmark",
                description = "Landmark",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Landmark 1".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalLandmark",
                displayName = "Local landmark",
                description = "Local landmark",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Landmark 1".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Vtc",
                displayName = "VTC",
                description = "Village/town/city",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "VTC".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalVtc",
                displayName = "Local VTC",
                description = "Local village/town/city",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "VTC".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "SubDistrict",
                displayName = "Sub-district",
                description = "Sub-district",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Sub-District".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalSubDistrict",
                displayName = "Local Sub-district",
                description = "Local Sub-district",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "Sub-District".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "District",
                displayName = "District",
                description = "District",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "District".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalDistrict",
                displayName = "Local district",
                description = "Local district",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "District".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "State",
                displayName = "State",
                description = "State",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_STATE.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalState",
                displayName = "Local state",
                description = "Local state",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_STATE.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "PoName",
                displayName = "PO name",
                description = "Post office name",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "PO Name".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalPoName",
                displayName = "Local PO name",
                description = "Local post office name",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = "PO Name".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "PinCode",
                displayName = "Pincode",
                description = "Pincode",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_POSTAL_CODE.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Address",
                displayName = "Address",
                description = "Address",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_ADDRESS.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "LocalAddress",
                displayName = "Local address",
                description = "Local address",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PLACE,
                sampleValue = SampleData.RESIDENT_ADDRESS.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Mobile",
                displayName = "Mobile",
                description = "Mobile",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PHONE,
                sampleValue = "1234567890".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "MaskedMobile",
                displayName = "Masked mobile",
                description = "Masked mobile",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.PHONE,
                sampleValue = "XXXXXX7890".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "Email",
                displayName = "Email",
                description = "Email",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.EMAIL,
                sampleValue = SampleData.EMAIL_ADDRESS.toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "MaskedEmail",
                displayName = "Masked email",
                description = "Masked email",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.EMAIL,
                sampleValue = "a***a@example.com".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "MaskedUID",
                displayName = "Masked UID",
                description = "Masked UID",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.NUMBERS,
                sampleValue = "XXXXXXXX1234".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.String,
                identifier = "AadhaarType",
                displayName = "Type",
                description = "Type",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                icon = Icon.BADGE,
                sampleValue = "Resident".toDataItem()
            )
            .addMdocAttribute(
                type = DocumentAttributeType.Date,
                identifier = "AadhaarExpiresOn",
                displayName = "Expires on",
                description = "Expires on",
                mandatory = false,
                mdocNamespace = AADHAAR_NAMESPACE,
                sensitivity = DocumentAttributeSensitivity.VALIDITY,
                icon = Icon.CALENDAR_CLOCK,
                sampleValue = LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )
            .addSampleRequest(
                id = "age_over_18",
                displayName = "Age over 18",
                mdocDataElements = mapOf(
                    AADHAAR_NAMESPACE to mapOf(
                        "AgeAbove18" to false,
                    )
                )
            )
            .addSampleRequest(
                id = "age_over_18_zkp",
                displayName ="Age over 18 (ZKP)",
                mdocDataElements = mapOf(
                    AADHAAR_NAMESPACE to mapOf(
                        "AgeAbove18" to false,
                    )
                ),
                mdocUseZkp = true
            )
            .addSampleRequest(
                id = "age_over_18_and_portrait",
                displayName = "Age over 18 + portrait",
                mdocDataElements = mapOf(
                    AADHAAR_NAMESPACE to mapOf(
                        "AgeAbove18" to false,
                        "ResidentImage" to false,
                    )
                )
            )
            .addSampleRequest(
                id = "full",
                displayName = "All data elements",
                mdocDataElements = mapOf(
                    AADHAAR_NAMESPACE to mapOf()
                )
            )
            .build()
    }
}
