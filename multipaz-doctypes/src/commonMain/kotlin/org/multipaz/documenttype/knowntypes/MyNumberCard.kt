package org.multipaz.documenttype.knowntypes

import kotlinx.datetime.LocalDate
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.knowntypes.PhotoID.ISO_23220_2_NAMESPACE
import org.multipaz.util.fromBase64Url

/**
 * Object containing the metadata of the Japanese My Number Card Document Type.
 *
 * The use of My Number Card is governed by applicable laws and regulations.
 * See https://www.digital.go.jp/en/policies/mynumber for more information.
 */
object MyNumberCard {
    const val MY_NUMBER_CARD_DOCTYPE = "org.iso.23220.1.jp.mnc"
    const val MY_NUMBER_CARD_NAMESPACE_JP = "org.iso.23220.1.jp"

    /**
     * Build the My Number Card Document Type.
     */
    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("My Number Card")
            .addMdocDocumentType(MY_NUMBER_CARD_DOCTYPE)
            .addMdocAttribute(
                DocumentAttributeType.String,
                "full_name_unicode",
                "Full Name",
                "氏名",
                true,
                MY_NUMBER_CARD_NAMESPACE_JP,
                Icon.PERSON,
                "番号　花子".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "resident_address_unicode",
                "Address",
                "住所",
                true,
                MY_NUMBER_CARD_NAMESPACE_JP,
                Icon.PLACE,
                "〇〇県△△市□□町１−２−３".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "local_gov_code_unicode",
                "Local Government Code",
                "市町村コード",
                true,
                MY_NUMBER_CARD_NAMESPACE_JP,
                Icon.NUMBERS,
                "14364".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "individual_number_unicode",
                "Individual Number",
                "個人番号",
                true,
                MY_NUMBER_CARD_NAMESPACE_JP,
                Icon.NUMBERS,
                "123466789012".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Picture,
                "portrait",
                "ID Photo",
                "顔写真",
                true,
                MY_NUMBER_CARD_NAMESPACE_JP,
                Icon.ACCOUNT_BOX,
                SampleData.PORTRAIT_BASE64URL.fromBase64Url().toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "sex_unicode",
                "Sex",
                "性別",
                true,
                MY_NUMBER_CARD_NAMESPACE_JP,
                Icon.EMERGENCY,
                "女".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "birth_date_unicode",
                "Date of Birth",
                "生年月日",
                true,
                MY_NUMBER_CARD_NAMESPACE_JP,
                Icon.TODAY,
                "1971年9月1日".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.IntegerOptions(Options.SEX_ISO_IEC_5218),
                "sex",
                "Sex",
                "document holder’s sex",
                true,
                ISO_23220_2_NAMESPACE,
                Icon.EMERGENCY,
                SampleData.SEX_ISO_5218.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Boolean,
                "age_over_20",
                "Older Than 20 Years",
                "Indication whether the document holder is as old or older than 20",
                true,
                ISO_23220_2_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_OVER.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Number,
                "age_in_years",
                "Age in Years",
                "The age of the document holder",
                true,
                ISO_23220_2_NAMESPACE,
                Icon.TODAY,
                SampleData.AGE_IN_YEARS.toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "birth_date",
                "Date of Birth",
                "Day, month and year on which the document holder was born. If unknown, approximate date of birth",
                true,
                ISO_23220_2_NAMESPACE,
                Icon.TODAY,
                LocalDate.parse(SampleData.BIRTH_DATE).toDataItemFullDate()
            )
            .addSampleRequest(
                id = "id",
                displayName ="Basic 4 Information",
                mdocDataElements = mapOf(
                    MY_NUMBER_CARD_NAMESPACE_JP to mapOf(
                        "full_name_unicode" to false,
                        "birth_date_unicode" to false,
                        "resident_address_unicode" to false,
                        "portrait" to false,
                    ),
                )
            )
            .addSampleRequest(
                id = "age_over_20",
                displayName ="Age Over 20",
                mdocDataElements = mapOf(
                    ISO_23220_2_NAMESPACE to mapOf(
                        "age_over_20" to false
                    ),
                )
            )
            .addSampleRequest(
                id = "age_over_20_and_portrait",
                displayName ="Age Over 20 + Portrait",
                mdocDataElements = mapOf(
                    ISO_23220_2_NAMESPACE to mapOf(
                        "age_over_20" to false
                    ),
                    MY_NUMBER_CARD_NAMESPACE_JP to mapOf(
                        "portrait" to false
                    )
                ),
            )
            .addSampleRequest(
                id = "full",
                displayName ="All Data Elements",
                mdocDataElements = mapOf(
                    MY_NUMBER_CARD_NAMESPACE_JP to mapOf(),
                    ISO_23220_2_NAMESPACE to mapOf(),
                )
            )
            .build()
    }
}