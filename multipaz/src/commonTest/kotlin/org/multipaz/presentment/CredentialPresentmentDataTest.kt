package org.multipaz.presentment

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.openid.dcql.DcqlQuery
import kotlin.test.Test
import kotlin.test.assertEquals

class CredentialPresentmentDataTest {

    suspend fun addMdl_with_AgeOver_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
        harness.provisionMdoc(
            displayName = "my-mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "unknown_data_element" to Tstr("Something"),
                    "given_name" to Tstr("David"),
                    "age_over_18" to true.toDataItem(),
                    "age_in_years" to 48.toDataItem(),
                    "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate(),
                    "portrait" to byteArrayOf(1, 2, 3).toDataItem()
                )
            )
        )
    }

    suspend fun addPhotoID_with_AgeOver_AgeInYears_BirthDate(harness: DocumentStoreTestHarness) {
        harness.provisionMdoc(
            displayName = "my-photoID",
            docType = PhotoID.PHOTO_ID_DOCTYPE,
            data = mapOf(
                PhotoID.ISO_23220_2_NAMESPACE to listOf(
                    "unknown_data_element" to Tstr("Something"),
                    "given_name" to Tstr("David"),
                    "age_over_18" to true.toDataItem(),
                    "age_in_years" to 48.toDataItem(),
                    "birth_date" to LocalDate.parse("1976-03-02").toDataItemFullDate(),
                    "portrait" to byteArrayOf(1, 2, 3).toDataItem()
                )
            )
        )
    }

    private fun ageAndPortraitQuery(): DcqlQuery {
        return DcqlQuery.fromJson(
            Json.parseToJsonElement(
                """
                        {
                          "credentials": [
                            {
                              "id": "mdl",
                              "format": "mso_mdoc",
                              "meta": {
                                "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                              },
                              "claims": [
                                {"id": "a", "path": ["${DrivingLicense.MDL_NAMESPACE}", "portrait"]},
                                {"id": "b", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_18"]},
                                {"id": "c", "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_in_years"]},
                                {"id": "d", "path": ["${DrivingLicense.MDL_NAMESPACE}", "birth_date"]}
                              ],
                              "claim_sets": [
                                ["a", "b"],
                                ["a", "c"],
                                ["a", "d"]
                              ]
                            },
                            {
                              "id": "photoid",
                              "format": "mso_mdoc",
                              "meta": {
                                "doctype_value": "${PhotoID.PHOTO_ID_DOCTYPE}"
                              },
                              "claims": [
                                {"id": "a", "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "portrait"]},
                                {"id": "b", "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "age_over_18"]},
                                {"id": "c", "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "age_in_years"]},
                                {"id": "d", "path": ["${PhotoID.ISO_23220_2_NAMESPACE}", "birth_date"]}
                              ],
                              "claim_sets": [
                                ["a", "b"],
                                ["a", "c"],
                                ["a", "d"]
                              ]
                            }
                          ],
                          "credential_sets": [
                            {
                              "options": [
                                [ "mdl" ],
                                [ "photoid" ]
                              ]
                            }
                          ]
                        }
                    """
            ).jsonObject
        )
    }

    @Test
    fun testGetAllSelections() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl_with_AgeOver_AgeInYears_BirthDate(harness)
        addPhotoID_with_AgeOver_AgeInYears_BirthDate(harness)

        val data = ageAndPortraitQuery().execute(presentmentSource = harness.presentmentSource)
        val selections = data.getAllSelections()
        assertEquals(
            """
                selections:
                  matches:
                    match:
                      credential:
                        type: MdocCredential
                        docId: my-mDL
                        claims:
                          claim:
                            nameSpace: org.iso.18013.5.1
                            dataElement: portrait
                            displayName: Photo of holder
                            value: Image (3 bytes)
                          claim:
                            nameSpace: org.iso.18013.5.1
                            dataElement: age_over_18
                            displayName: Older than 18 years
                            value: True
                  matches:
                    match:
                      credential:
                        type: MdocCredential
                        docId: my-photoID
                        claims:
                          claim:
                            nameSpace: org.iso.23220.1
                            dataElement: portrait
                            displayName: Photo of holder
                            value: Image (3 bytes)
                          claim:
                            nameSpace: org.iso.23220.1
                            dataElement: age_over_18
                            displayName: Older than 18 years
                            value: True
            """.trimIndent() + "\n",
            selections.prettyPrint()
        )
    }
}