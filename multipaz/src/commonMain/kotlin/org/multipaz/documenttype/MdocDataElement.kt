/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.multipaz.documenttype

import kotlinx.coroutines.CancellationException
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.util.Logger
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime

/**
 * Class containing the metadata of a data element in a ISO mdoc.
 *
 * @param attribute the generic metadata.
 * @param mandatory a ISO mdoc specific indication whether the data element is mandatory
 */
data class MdocDataElement(
    val attribute: DocumentAttribute,
    val mandatory: Boolean
) {
    companion object {
        private const val TAG = "MdocDataElement"
    }

    /**
     * Renders the value of this data element as a string.
     *
     * The returned value is suitable for displaying in an user interface.
     *
     * This method never throws an exception, if an error happens it falls back to returning the CBOR rendered as
     * diagnostics using [Cbor.toDiagnostics] using the flag [DiagnosticOption.BSTR_PRINT_LENGTH].
     *
     * @param value the value, as a CBOR data item
     * @param timeZone the time zone to use, for rendering date-time. It is never used for rendering a full-date.
     * @param trueFalseStrings a pair with the strings to use for false and true.
     * @return a string representing the value.
     */
    fun renderValue(
        value: DataItem,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        trueFalseStrings: Pair<String, String> = Pair("false", "true")
    ): String {
        val diagnosticsOptions = setOf(DiagnosticOption.BSTR_PRINT_LENGTH)
        val ret = try {
            when (attribute.type) {
                // TODO: translations? maybe take the strings to use for true/false as a param
                DocumentAttributeType.Boolean -> {
                    if (value.asBoolean)
                        trueFalseStrings.second
                    else
                        trueFalseStrings.first
                }

                DocumentAttributeType.Blob -> Cbor.toDiagnostics(value, diagnosticsOptions)

                DocumentAttributeType.ComplexType -> Cbor.toDiagnostics(value)

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
                    dt.format(LocalDateTime.Formats.ISO).substring(IntRange(0, 9))
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
                    dt.format(LocalDateTime.Formats.ISO)
                }

                is DocumentAttributeType.IntegerOptions -> {
                    val option = attribute.type.options.find { it.value == value.asNumber.toInt() }
                    option?.displayName ?: value.asNumber.toString()
                }
                DocumentAttributeType.Number -> value.asNumber.toString()
                DocumentAttributeType.Picture -> Cbor.toDiagnostics(value, diagnosticsOptions)
                DocumentAttributeType.String -> value.asTstr
                is DocumentAttributeType.StringOptions -> {
                    val option = attribute.type.options.find { it.value == value.asTstr }
                    option?.displayName ?: value.asTstr
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val diagnosticsString = Cbor.toDiagnostics(value, diagnosticsOptions)
            Logger.w(TAG, "Error decoding value $diagnosticsString for data element " +
                    "${attribute.identifier}, falling back to diagnostics", e)
            diagnosticsString
        }

        return ret
    }
}