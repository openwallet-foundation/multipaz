package org.multipaz.claim

import kotlinx.coroutines.CancellationException
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tagged
import org.multipaz.datetime.formatLocalized
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentAttributeType
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * A claim in an ISO mdoc credential.
 *
 * @property docType the document type.
 * @property namespaceName the mdoc namespace.
 * @property dataElementName the data element name.
 * @property value the value of the claim.
 */
data class MdocClaim(
    override val displayName: String,
    override val attribute: DocumentAttribute?,
    val docType: String,
    val namespaceName: String,
    val dataElementName: String,
    val value: DataItem
) : Claim(displayName, attribute) {

    override fun render(timeZone: TimeZone): String {
        val str = try {
            if (attribute == null) {
                Cbor.toDiagnostics(
                    value,
                    setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.BSTR_PRINT_LENGTH)
                )
            } else {
                val diagnosticsOptions = setOf(DiagnosticOption.BSTR_PRINT_LENGTH)
                when (attribute.type) {
                    // TODO: translations
                    DocumentAttributeType.Boolean -> {
                        if (value.asBoolean) {
                            "True"
                        } else {
                            "False"
                        }
                    }

                    DocumentAttributeType.Blob -> {
                        Cbor.toDiagnostics(value, diagnosticsOptions)
                    }

                    DocumentAttributeType.ComplexType -> {
                        Cbor.toDiagnostics(value)
                    }

                    DocumentAttributeType.Date -> {
                        // value can be either tdate or full-date or a map as per ISO/IEC 23220-2
                        // clause 6.3.1.1.3 Date of birth as uncertain or approximate, or both
                        val taggedValue = if (value is CborMap) {
                            value.get("birth_date")
                            // TODO: if available, also use approximate_mask when rendering the value
                        } else {
                            value
                        }
                        val tagNumber = (taggedValue as Tagged).tagNumber
                        val d = when (tagNumber) {
                            Tagged.FULL_DATE_STRING -> {
                                LocalDate.parse(taggedValue.asTagged.asTstr)
                            }

                            Tagged.DATE_TIME_STRING -> {
                                val pointInTime = Instant.parse(taggedValue.asTagged.asTstr)
                                pointInTime.toLocalDateTime(timeZone).date
                            }

                            else -> {
                                throw IllegalArgumentException("Unexpected tag $tagNumber")
                            }
                        }
                        d.formatLocalized()
                    }

                    DocumentAttributeType.DateTime -> {
                        // value can be either tdate or full-date or a map as per ISO/IEC 23220-2
                        // clause 6.3.1.1.3 Date of birth as uncertain or approximate, or both
                        val taggedValue = if (value is CborMap) {
                            value.get("birth_date")
                            // TODO: if available, also use approximate_mask when rendering the value
                        } else {
                            value
                        }
                        val tagNumber = (taggedValue as Tagged).tagNumber
                        val dt = when (tagNumber) {
                            Tagged.FULL_DATE_STRING -> {
                                LocalDateTime(
                                    LocalDate.parse(taggedValue.asTagged.asTstr),
                                    LocalTime(0, 0, 0)
                                )
                            }

                            Tagged.DATE_TIME_STRING -> {
                                val pointInTime = Instant.parse(taggedValue.asTagged.asTstr)
                                pointInTime.toLocalDateTime(timeZone)
                            }

                            else -> {
                                throw IllegalArgumentException("Unexpected tag $tagNumber")
                            }
                        }
                        dt.formatLocalized()
                    }

                    is DocumentAttributeType.IntegerOptions -> {
                        val type = attribute.type
                        val option = type.options.find { it.value == value.asNumber.toInt() }
                        option?.displayName ?: value.asNumber.toString()
                    }

                    DocumentAttributeType.Number -> {
                        value.asNumber.toString()
                    }

                    DocumentAttributeType.Picture -> {
                        "Image (${value.asBstr.size} bytes)"
                    }

                    DocumentAttributeType.String -> {
                        value.asTstr
                    }

                    is DocumentAttributeType.StringOptions -> {
                        val type = attribute.type
                        val option = type.options.find { it.value == value.asTstr }
                        option?.displayName ?: value.asTstr
                    }
                }

            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val fallback = Cbor.toDiagnostics(value, setOf(DiagnosticOption.BSTR_PRINT_LENGTH))
            "$fallback (fallback, error occurred during rendering: ${e.message})"
        }
        return str
    }
}

/**
 * Organizes a list of [MdocClaim]s by namespace.
 *
 * @return a map from namespaces into the [MdocClaim]s for that namespace.
 */
fun List<MdocClaim>.organizeByNamespace(): Map<String, List<MdocClaim>> {
    val claimsByNamespace = mutableMapOf<String, MutableList<MdocClaim>>()
    for (claim in this) {
        claimsByNamespace.getOrPut(claim.namespaceName, { mutableListOf() }).add(claim)
    }
    return claimsByNamespace
}