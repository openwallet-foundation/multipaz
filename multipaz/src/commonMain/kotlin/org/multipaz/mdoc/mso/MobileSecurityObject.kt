package org.multipaz.mdoc.mso

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.revocation.RevocationStatus
import org.multipaz.util.Logger
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

    companion object {
        private const val TAG = "MobileSecurityObject"

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
                } catch (e: Throwable) {
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