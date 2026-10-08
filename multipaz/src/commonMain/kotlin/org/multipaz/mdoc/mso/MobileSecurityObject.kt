package org.multipaz.mdoc.mso

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.revocation.RevocationStatus
import org.multipaz.util.Logger
import org.multipaz.validation.ValidationResult
import org.multipaz.validation.buildValidationResult
import kotlin.time.Instant

/**
 * Mobile Security Object according to ISO/IEC 18013-5.
 *
 * @property version the version, e.g. `1.0` or `1.1`.
 * @property docType the type of the document, e.g. "org.iso.18013.5.1.mDL".
 * @property signedAt the point in time the MSO was signed.
 * @property validFrom the point in time the MSO is valid from.
 * @property validUntil the point in time the MSO is valid until.
 * @property expectedUpdate the point in time the MSO is expected to be updated, if available.
 * @property digestAlgorithm the digest algorithm used for the value digests.
 * @property valueDigests the value digests, use [org.multipaz.mdoc.issuersigned.IssuerNamespaces.getValueDigests] to set.
 * @property deviceKey the public part of the key the MSO is bound to.
 * @property deviceKeyAuthorizedNamespaces namespaces the mdoc is authorized to returned device signed data elements for.
 * @property deviceKeyAuthorizedDataElements data elements for which the mdoc is authorized to return data elements for.
 * @property deviceKeyInfo additional information about the device key.
 * @property revocationStatus defines how to check if this document is revoked.
 */
data class MobileSecurityObject(
    val version: String,
    val docType: String,
    val signedAt: Instant,
    val validFrom: Instant,
    val validUntil: Instant,
    val expectedUpdate: Instant?,
    val digestAlgorithm: Algorithm,
    val valueDigests: Map<String, Map<Long, ByteString>>,
    val deviceKey: EcPublicKey,
    val deviceKeyAuthorizedNamespaces: List<String> = emptyList(),
    val deviceKeyAuthorizedDataElements: Map<String, List<String>> = emptyMap(),
    val deviceKeyInfo: Map<Long, DataItem> = emptyMap(),
    val revocationStatus: RevocationStatus? = null
) {

    /**
     * Generates CBOR compliant with the CDDL for `MobileSecurityObject` according to ISO 18013-5.
     *
     * @return a [DataItem].
     */
    fun toDataItem(): DataItem = buildCborMap {
        // From 18013-5 clause 9.1.2.4 Signing method and structure for MSO:
        //
        //   The timestamps in the ValidityInfo structure shall not use fractions of seconds and
        //   shall use a UTC offset of 00:00, as indicated by the character “Z”
        //
        require(signedAt.nanosecondsOfSecond == 0) { "signedAt cannot have fractional seconds" }
        require(validFrom.nanosecondsOfSecond == 0) { "validFrom cannot have fractional seconds" }
        require(validUntil.nanosecondsOfSecond == 0) { "validUntil cannot have fractional seconds" }
        expectedUpdate?.let {
            require(it.nanosecondsOfSecond == 0) { "expectedUpdate cannot have fractional seconds" }
        }

        put("version", version)
        put("digestAlgorithm",
            when (digestAlgorithm) {
                Algorithm.SHA256 -> "SHA-256"
                Algorithm.SHA384 -> "SHA-384"
                Algorithm.SHA512 -> "SHA-512"
                else -> throw IllegalArgumentException("Unsupported digest algorithm $digestAlgorithm")
            }
        )
        put("docType", docType)
        putCborMap("valueDigests") {
            valueDigests.forEach { (namespace, digestIds) ->
                putCborMap(namespace) {
                    digestIds.forEach { (digestId, digest) ->
                        put(digestId.toDataItem(), Bstr(digest.toByteArray()))
                    }
                }
            }
        }
        putCborMap("deviceKeyInfo") {
            put("deviceKey", deviceKey.toCoseKey().toDataItem())
            if (deviceKeyAuthorizedNamespaces.isNotEmpty() || deviceKeyAuthorizedDataElements.isNotEmpty()) {
                putCborMap("keyAuthorizations") {
                    if (deviceKeyAuthorizedNamespaces.isNotEmpty()) {
                        putCborArray("nameSpaces") {
                            deviceKeyAuthorizedNamespaces.forEach { add(it) }
                        }
                    }
                    if (deviceKeyAuthorizedDataElements.isNotEmpty()) {
                        putCborMap("dataElements") {
                            deviceKeyAuthorizedDataElements.forEach { (namespace, dataElementList) ->
                                putCborArray(namespace) {
                                    dataElementList.forEach { add(it) }
                                }
                            }
                        }
                    }
                }
            }
            if (deviceKeyInfo.isNotEmpty()) {
                putCborMap("keyInfo") {
                    deviceKeyInfo.forEach { (key, value) ->
                        put(key.toDataItem(), value)
                    }
                }
            }
        }
        putCborMap("validityInfo") {
            put("signed", signedAt.toDataItemDateTimeString())
            put("validFrom", validFrom.toDataItemDateTimeString())
            put("validUntil", validUntil.toDataItemDateTimeString())
            expectedUpdate?.let {
                put("expectedUpdate", it.toDataItemDateTimeString())
            }
        }
        if (revocationStatus != null) {
            put("status", revocationStatus.toDataItem())
        }
    }

    /**
     * Validates the internal structure and contents of this [MobileSecurityObject] according to
     * ISO/IEC 18013-5:2021.
     *
     * @param now the reference time to check for expiration or validity interval, or `null` to skip
     * current time validity checks.
     * @return a [ValidationResult] containing any errors or warnings.
     */
    fun validate(now: Instant? = null): ValidationResult = buildValidationResult {
        if (version !in listOf("1.0", "1.1")) {
            if (version.isEmpty()) {
                addError("MSO version cannot be empty")
            } else {
                addWarning("MSO version '$version' is unrecognized; expected '1.0' or '1.1'")
            }
        }

        val expectedDigestSize = when (digestAlgorithm) {
            Algorithm.SHA256 -> 32
            Algorithm.SHA384 -> 48
            Algorithm.SHA512 -> 64
            else -> {
                addError("Unsupported digest algorithm: $digestAlgorithm (must be SHA-256, SHA-384, or SHA-512)")
                null
            }
        }

        if (docType.isEmpty()) {
            addError("MSO docType cannot be empty")
        }

        // Validity info checks
        if (signedAt.nanosecondsOfSecond != 0) {
            addError("MSO signed timestamp must not have fractional seconds")
        }
        if (validFrom.nanosecondsOfSecond != 0) {
            addError("MSO validFrom timestamp must not have fractional seconds")
        }
        if (validUntil.nanosecondsOfSecond != 0) {
            addError("MSO validUntil timestamp must not have fractional seconds")
        }
        if (expectedUpdate != null && expectedUpdate.nanosecondsOfSecond != 0) {
            addError("MSO expectedUpdate timestamp must not have fractional seconds")
        }

        if (validFrom < signedAt) {
            addError("MSO validFrom ($validFrom) must be equal to or later than signed ($signedAt)")
        }
        if (validUntil <= validFrom) {
            addError("MSO validUntil ($validUntil) must be later than validFrom ($validFrom)")
        }
        if (expectedUpdate != null) {
            if (expectedUpdate < validFrom) {
                addError("MSO expectedUpdate ($expectedUpdate) cannot be earlier than validFrom ($validFrom)")
            }
            if (expectedUpdate > validUntil) {
                addWarning("MSO expectedUpdate ($expectedUpdate) is later than validUntil ($validUntil)")
            }
        }

        if (now != null) {
            if (now < validFrom) {
                addWarning("MSO is not yet valid (validFrom: $validFrom, current time: $now)")
            }
            if (now > validUntil) {
                addError("MSO is expired (validUntil: $validUntil, current time: $now)")
            }
        }

        // Value digests checks
        if (valueDigests.isEmpty()) {
            addError("MSO valueDigests cannot be empty")
        }
        valueDigests.forEach { (namespace, digestMap) ->
            if (namespace.isEmpty()) {
                addError("MSO valueDigests contains an empty namespace name")
            }
            if (digestMap.isEmpty()) {
                addError("MSO valueDigests for namespace '$namespace' is empty")
            }
            digestMap.forEach { (digestId, digest) ->
                if (digestId < 0) {
                    addError("MSO digestID $digestId in namespace '$namespace' must be non-negative")
                }
                if (expectedDigestSize != null && digest.size != expectedDigestSize) {
                    addError("MSO digest for digestID $digestId in namespace '$namespace' has length ${digest.size} bytes, expected $expectedDigestSize bytes for $digestAlgorithm")
                }
            }
        }

        // Key authorizations checks
        deviceKeyAuthorizedNamespaces.forEach { ns ->
            if (ns.isEmpty()) {
                addError("deviceKeyAuthorizedNamespaces contains an empty namespace name")
            }
        }
        deviceKeyAuthorizedDataElements.forEach { (ns, elemList) ->
            if (ns.isEmpty()) {
                addError("deviceKeyAuthorizedDataElements contains an empty namespace name")
            }
            elemList.forEach { elem ->
                if (elem.isEmpty()) {
                    addError("deviceKeyAuthorizedDataElements for namespace '$ns' contains an empty element identifier")
                }
            }
        }
    }

    companion object {
        private const val TAG = "MobileSecurityObject"

        /**
         * Validates the CBOR structure of `MobileSecurityObject` according to ISO/IEC 18013-5:2021.
         *
         * @param dataItem a [DataItem] containing CBOR for `MobileSecurityObject`.
         * @param now the reference time to check for expiration or validity interval, or `null`.
         * @return a [ValidationResult] containing any errors or warnings.
         */
        fun validate(dataItem: DataItem, now: Instant? = null): ValidationResult = buildValidationResult {
            if (dataItem !is CborMap) {
                addError("MobileSecurityObject dataItem is not a CBOR map")
                return@buildValidationResult
            }
            val requiredKeys = listOf(
                "version",
                "digestAlgorithm",
                "docType",
                "valueDigests",
                "deviceKeyInfo",
                "validityInfo"
            )
            for (key in requiredKeys) {
                if (!dataItem.hasKey(key)) {
                    addError("MobileSecurityObject missing required key '$key'")
                }
            }
            if (dataItem.hasKey("digestAlgorithm")) {
                val algStr = try {
                    dataItem["digestAlgorithm"].asTstr
                } catch (_: Throwable) {
                    null
                }
                if (algStr !in listOf("SHA-256", "SHA-384", "SHA-512")) {
                    addError("Unsupported digest algorithm '$algStr' in MSO (must be SHA-256, SHA-384, or SHA-512)")
                }
            }
            if (dataItem.hasKey("validityInfo")) {
                val vi = dataItem["validityInfo"]
                if (vi !is CborMap) {
                    addError("validityInfo is not a CBOR map")
                } else {
                    for (viKey in listOf("signed", "validFrom", "validUntil")) {
                        if (!vi.hasKey(viKey)) {
                            addError("validityInfo missing required timestamp '$viKey'")
                        }
                    }
                }
            }
            if (dataItem.hasKey("deviceKeyInfo")) {
                val dki = dataItem["deviceKeyInfo"]
                if (dki !is CborMap) {
                    addError("deviceKeyInfo is not a CBOR map")
                } else if (!dki.hasKey("deviceKey")) {
                    addError("deviceKeyInfo missing 'deviceKey'")
                }
            }
            try {
                val mso = fromDataItem(dataItem)
                addAll(mso.validate(now))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                addError("Failed to parse MobileSecurityObject: ${e.message}")
            }
        }

        /**
         * Parses CBOR compliant with the CDDL for `MobileSecurityObject` according to ISO 18013-5.
         *
         * @param dataItem a [DataItem] containing CBOR for `MobileSecurityObject`.
         * @return a [MobileSecurityObject].
         */
        fun fromDataItem(dataItem: DataItem): MobileSecurityObject {
            val valueDigests = mutableMapOf<String, MutableMap<Long, ByteString>>()

            dataItem["valueDigests"].asMap.forEach { (namespace, digestIds) ->
                val innerMap = mutableMapOf<Long, ByteString>()
                digestIds.asMap.forEach { (digestId, digest) ->
                    innerMap.put(digestId.asNumber, ByteString(digest.asBstr))
                }
                valueDigests.put(namespace.asTstr, innerMap)
            }

            val dkInfo = dataItem["deviceKeyInfo"]
            val deviceKey = dkInfo["deviceKey"].asCoseKey.ecPublicKey

            val deviceKeyAuthorizedNamespaces = mutableListOf<String>()
            val deviceKeyAuthorizedDataElements = mutableMapOf<String, List<String>>()
            dkInfo.getOrNull("keyAuthorizations")?.let { keyAuthorizationsMap ->
                keyAuthorizationsMap.getOrNull("nameSpaces")?.let { namespaces ->
                    namespaces.asArray.forEach { deviceKeyAuthorizedNamespaces.add(it.asTstr) }
                }
                keyAuthorizationsMap.getOrNull("dataElements")?.let { dataElements ->
                    dataElements.asMap.forEach { (namespace, dataElementArray) ->
                        deviceKeyAuthorizedDataElements[namespace.asTstr] = dataElementArray.asArray.map { it.asTstr }
                    }
                }
            }
            val deviceKeyInfo = mutableMapOf<Long, DataItem>()
            dkInfo.getOrNull("keyInfo")?.let { keyInfoMap ->
                keyInfoMap.asMap.forEach { (key, value) ->
                    deviceKeyInfo.put(key.asNumber, value)
                }
            }

            val validityInfo = dataItem["validityInfo"]

            val revocationStatus = if (dataItem.hasKey("status")) {
                try {
                    RevocationStatus.fromDataItem(dataItem["status"])
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Ignoring malformed status field in MSO", e)
                    Logger.iCbor(TAG, "The malformed status field is", dataItem["status"])
                    null
                }
            } else {
                null
            }

            return MobileSecurityObject(
                version = dataItem["version"].asTstr,
                docType = dataItem["docType"].asTstr,
                signedAt = validityInfo["signed"].asDateTimeString,
                validFrom = validityInfo["validFrom"].asDateTimeString,
                validUntil = validityInfo["validUntil"].asDateTimeString,
                expectedUpdate = validityInfo.getOrNull("expectedUpdate")?.asDateTimeString,
                digestAlgorithm = dataItem["digestAlgorithm"].asTstr.let {
                    when (it) {
                        "SHA-256" -> Algorithm.SHA256
                        "SHA-384" -> Algorithm.SHA384
                        "SHA-512" -> Algorithm.SHA512
                        else -> throw IllegalArgumentException("Unsupported digest algorithm $it")
                    }
                },
                valueDigests = valueDigests,
                deviceKey = deviceKey,
                deviceKeyAuthorizedNamespaces = deviceKeyAuthorizedNamespaces,
                deviceKeyAuthorizedDataElements = deviceKeyAuthorizedDataElements,
                deviceKeyInfo = deviceKeyInfo,
                revocationStatus = revocationStatus
            )
        }

    }
}