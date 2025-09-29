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
package org.multipaz.util

import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Boolean
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Object
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1Set
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.crypto.X509Cert
import kotlinx.io.bytestring.ByteString
import org.multipaz.device.AndroidKeystoreSecurityLevel

// This code is based on https://github.com/google/android-key-attestation
class AndroidAttestationExtensionParser(cert: X509Cert) {
    enum class VerifiedBootState {
        UNKNOWN,
        GREEN,
        YELLOW,
        ORANGE,
        RED
    }

    val attestationSecurityLevel: AndroidKeystoreSecurityLevel
    val attestationVersion: Int

    val keymasterSecurityLevel: AndroidKeystoreSecurityLevel
    val keymasterVersion: Int

    val attestationChallenge: ByteArray
    val uniqueId: ByteArray

    val verifiedBootState: VerifiedBootState

    val applicationSignatureDigests: List<ByteString>

    val applicationPackages: List<String>

    private val softwareEnforcedAuthorizations: Map<Int, ASN1Object>
    private val teeEnforcedAuthorizations: Map<Int, ASN1Object>

    init {
        val attestationExtensionBytes = cert.getExtensionValue(KEY_DESCRIPTION_OID)
        require(attestationExtensionBytes != null && attestationExtensionBytes.isNotEmpty()) {
            "Couldn't find keystore attestation extension."
        }
        val extAsn1 = ASN1.decode(attestationExtensionBytes)
            ?: throw IllegalArgumentException("ASN.1 parsing failed")
        val seq = extAsn1 as ASN1Sequence

        attestationVersion = getIntegerFromAsn1(seq.elements[ATTESTATION_VERSION_INDEX])
        this.attestationSecurityLevel = securityLevelToEnum(
            getIntegerFromAsn1(seq.elements[ATTESTATION_SECURITY_LEVEL_INDEX])
        )

        keymasterVersion = getIntegerFromAsn1(seq.elements[KEYMASTER_VERSION_INDEX])
        this.keymasterSecurityLevel = securityLevelToEnum(
            getIntegerFromAsn1(seq.elements[KEYMASTER_SECURITY_LEVEL_INDEX])
        )
        attestationChallenge =
            (seq.elements[ATTESTATION_CHALLENGE_INDEX] as ASN1OctetString).value
        uniqueId = (seq.elements[UNIQUE_ID_INDEX] as ASN1OctetString).value

        softwareEnforcedAuthorizations = getAuthorizationMap(
            (seq.elements[SW_ENFORCED_INDEX] as ASN1Sequence).elements
        )
        teeEnforcedAuthorizations = getAuthorizationMap(
            (seq.elements[TEE_ENFORCED_INDEX] as ASN1Sequence).elements
        )

        val rootOfTrustSeq = findAuthorizationListEntry(teeEnforcedAuthorizations, 704) as ASN1Sequence?
        verifiedBootState =
            if (rootOfTrustSeq != null) {
                when (getIntegerFromAsn1(rootOfTrustSeq.elements[2])) {
                    0 -> VerifiedBootState.GREEN
                    1 -> VerifiedBootState.YELLOW
                    2 -> VerifiedBootState.ORANGE
                    3 -> VerifiedBootState.RED
                    else -> VerifiedBootState.UNKNOWN
                }
            } else {
                VerifiedBootState.UNKNOWN
            }

        val encodedAttestationApplicationId = getSoftwareAuthorizationByteString(709)
            ?: throw IllegalArgumentException("No software authorization")
        val attestationApplicationIdSeq = (ASN1.decode(encodedAttestationApplicationId)
            ?: throw IllegalArgumentException("ASN.1 parsing failed")) as ASN1Sequence

        val packageInfoSet = attestationApplicationIdSeq.elements[0] as ASN1Set
        applicationPackages = buildList {
            for (element in packageInfoSet.elements) {
                val packageInfo = element as ASN1Sequence
                add((packageInfo.elements[0] as ASN1OctetString).value.decodeToString())
            }
        }

        val signatureDigestSet = attestationApplicationIdSeq.elements[1] as ASN1Set
        applicationSignatureDigests = buildList {
            for (element in signatureDigestSet.elements) {
                val octetString = element as ASN1OctetString
                add(ByteString(octetString.value))
            }
        }
    }

    val softwareEnforcedAuthorizationTags: Set<Int>
        get() = softwareEnforcedAuthorizations.keys

    val teeEnforcedAuthorizationTags: Set<Int>
        get() = teeEnforcedAuthorizations.keys

    fun getSoftwareAuthorizationValue(tag: Int): ASN1Object {
        return findAuthorizationListEntry(softwareEnforcedAuthorizations, tag)
            ?: throw IllegalStateException("Tag not found")
    }

    fun getTeeAuthorizationValue(tag: Int): ASN1Object {
        return findAuthorizationListEntry(teeEnforcedAuthorizations, tag)
            ?: throw IllegalStateException("Tag not found")
    }

    fun getSoftwareAuthorizationInteger(tag: Int): Int? {
        val entry = findAuthorizationListEntry(softwareEnforcedAuthorizations, tag)
        return entry?.let { getIntegerFromAsn1(it) }
    }

    fun getSoftwareAuthorizationLong(tag: Int): Long? {
        val entry = findAuthorizationListEntry(softwareEnforcedAuthorizations, tag)
        return entry?.let { getLongFromAsn1(it) }
    }

    fun getTeeAuthorizationInteger(tag: Int): Int? {
        val entry = findAuthorizationListEntry(teeEnforcedAuthorizations, tag)
        return entry?.let { getIntegerFromAsn1(it) }
    }

    fun getTeeAuthorizationLong(tag: Int): Long? {
        val entry = findAuthorizationListEntry(teeEnforcedAuthorizations, tag)
        return entry?.let { getLongFromAsn1(it) }
    }

    fun getSoftwareAuthorizationBoolean(tag: Int): Boolean {
        val entry = findAuthorizationListEntry(softwareEnforcedAuthorizations, tag)
        return entry != null
    }

    fun getTeeAuthorizationBoolean(tag: Int): Boolean {
        val entry = findAuthorizationListEntry(teeEnforcedAuthorizations, tag)
        return entry != null
    }

    fun getSoftwareAuthorizationByteString(tag: Int): ByteArray? {
        val entry =
            findAuthorizationListEntry(softwareEnforcedAuthorizations, tag) as ASN1OctetString?
        return entry?.value
    }

    fun getTeeAuthorizationByteString(tag: Int): ByteArray? {
        val entry = findAuthorizationListEntry(teeEnforcedAuthorizations, tag) as ASN1OctetString?
        return entry?.value
    }

    private fun renderByteArray(data: ByteArray): String {
        return if (data.isEmpty()) {
            "<empty>"
        } else {
            "${data.toHex(byteDivider = " ")} (\"${data.decodeToString()}\")"
        }
    }

    private fun findAuthorizationListEntry(tag: Int): ASN1Object? {
        return findAuthorizationListEntry(teeEnforcedAuthorizations, tag)
            ?:findAuthorizationListEntry(softwareEnforcedAuthorizations, tag)
    }


    fun getUserAuthenticationType(): Long {
        val result = findAuthorizationListEntry(
            AndroidKeystoreAuthorization.userAuthType.tag
        ) as ASN1Integer?
        return result?.toLong() ?: 0
    }

    private enum class VBootState(
        val value: Int,
        val description: String
    ) {
        VERIFIED(0, "Verified (GREEN)"),
        SELF_SIGNED(1, "Self-signed (YELLOW)"),
        UNVERIFIED(2, "Unverified (ORANGE)"),
        FAILED(3, "Failed (RED)")
    }

    private val aksVerifiedBootStateToEnum: Map<Int, VBootState> by lazy {
        VBootState.entries.associateBy { it.value }
    }

    private enum class AuthorizationType {
        INT,
        INT_SET,
        NULL,
        OCTET_STRING,
        ROOT_OF_TRUST,
        ATTESTATION_APPLICATION_ID
    }

    // See https://source.android.com/docs/security/features/keystore/attestation#schema for the list
    // of known authorizations. Add more as needed over time.
    //
    private enum class AndroidKeystoreAuthorization(
        val tag: Int,
        val type: AuthorizationType,
    ) {
        purpose(1, AuthorizationType.INT_SET),
        algorithm(2, AuthorizationType.INT),
        keySize(3, AuthorizationType.INT),
        digest(5, AuthorizationType.INT_SET),
        padding(6, AuthorizationType.INT_SET),
        ecCurve(10, AuthorizationType.INT),
        rsaPublicExponent(200, AuthorizationType.INT),
        mgfDigest(203, AuthorizationType.INT_SET),
        rollbackResistance(303, AuthorizationType.NULL),
        earlyBootOnly(305, AuthorizationType.NULL),
        activeDateTime(400, AuthorizationType.INT),
        originationExpireDateTime(401, AuthorizationType.INT),
        usageExpireDateTime(402, AuthorizationType.INT),
        usageCountLimit(405, AuthorizationType.INT),
        noAuthRequired(503, AuthorizationType.NULL),
        userAuthType(504, AuthorizationType.INT),
        authTimeout(505, AuthorizationType.INT),
        allowWhileOnBody(506, AuthorizationType.NULL),
        trustedUserPresenceRequired(507, AuthorizationType.NULL),
        trustedConfirmationRequired(508, AuthorizationType.NULL),
        unlockedDeviceRequired(509, AuthorizationType.NULL),
        creationDateTime(701, AuthorizationType.INT),
        origin(702, AuthorizationType.INT),
        rootOfTrust(704, AuthorizationType.ROOT_OF_TRUST),
        osVersion(705, AuthorizationType.INT),
        osPatchLevel(706, AuthorizationType.INT),
        attestationApplicationId(709, AuthorizationType.ATTESTATION_APPLICATION_ID),
        attestationIdBrand(710, AuthorizationType.OCTET_STRING),
        attestationIdDevice(711, AuthorizationType.OCTET_STRING),
        attestationIdProduct(712, AuthorizationType.OCTET_STRING),
        attestationIdSerial(713, AuthorizationType.OCTET_STRING),
        attestationIdImei(714, AuthorizationType.OCTET_STRING),
        attestationIdMeid(715, AuthorizationType.OCTET_STRING),
        attestationIdManufacturer(716, AuthorizationType.OCTET_STRING),
        attestationIdModel(717, AuthorizationType.OCTET_STRING),
        vendorPatchLevel(718, AuthorizationType.INT),
        bootPatchLevel(719, AuthorizationType.INT),
        deviceUniqueAttestation(720, AuthorizationType.NULL),
        attestationIdSecondImei(723, AuthorizationType.OCTET_STRING),
        moduleHash(724, AuthorizationType.OCTET_STRING)
    }

    private val aksTagToEnum: Map<Int, AndroidKeystoreAuthorization> by lazy {
        AndroidKeystoreAuthorization.entries.associateBy { it.tag }
    }

    private fun renderAndroidKeystoreAuthorization(
        tag: Int,
        value: ASN1Object
    ): String {
        try {
            val authorization = aksTagToEnum[tag]
            if (authorization != null) {
                return "  ${authorization.name}: " + when (authorization.type) {
                    AuthorizationType.INT -> {
                        (value as ASN1Integer).toLong().toString() + "\n"
                    }

                    AuthorizationType.INT_SET -> {
                        (value as ASN1Set).elements.map {
                            (it as ASN1Integer).toLong().toString()
                        }.joinToString(", ") + "\n"
                    }

                    AuthorizationType.NULL -> {
                        "true\n"
                    }

                    AuthorizationType.OCTET_STRING -> {
                        renderByteArray((value as ASN1OctetString).value) + "\n"
                    }

                    AuthorizationType.ROOT_OF_TRUST -> {
                        val seq = value as ASN1Sequence
                        val verifiedBootKey = (seq.elements[0] as ASN1OctetString).value
                        val deviceLocked = (seq.elements[1] as ASN1Boolean).value
                        val verifiedBootState = (seq.elements[2] as ASN1Integer).toLong().toInt()
                        val verifiedBootStateDesc = aksVerifiedBootStateToEnum[verifiedBootState]!!.description
                        val verifiedBootHash = (seq.elements[3] as ASN1OctetString).value
                        val sb = StringBuilder("\n")
                        sb.append("    verifiedBootKey: ${verifiedBootKey.toHex(byteDivider = " ")}\n")
                        sb.append("    deviceLocked: ${deviceLocked}\n")
                        sb.append("    verifiedBootState: ${verifiedBootStateDesc}\n")
                        sb.append("    verifiedBootHash: ${verifiedBootHash.toHex(byteDivider = " ")}\n")
                        sb.toString()
                    }

                    AuthorizationType.ATTESTATION_APPLICATION_ID -> {
                        val sb = StringBuilder("\n")
                        val seq = ASN1.decode((value as ASN1OctetString).value) as ASN1Sequence

                        sb.append("    Package Infos:\n")
                        for (item in (seq.elements[0] as ASN1Set).elements) {
                            val itemSeq = item as ASN1Sequence
                            val packageName = (itemSeq.elements[0] as ASN1OctetString).value.decodeToString()
                            val version = (itemSeq.elements[1] as ASN1Integer).toLong()
                            sb.append("      $packageName (version $version)\n")
                        }
                        sb.append("    Signature Digests:\n")
                        for (item in (seq.elements[1] as ASN1Set).elements) {
                            sb.append("      ${(item as ASN1OctetString).value.toHex(byteDivider = " ")}\n")
                        }
                        sb.toString()
                    }
                }
            } else {
                return "  $tag: ${ASN1.print(value).trim()}\n"
            }
        } catch (e: Throwable) {
            return "  $tag: Error rendering: ${e.message}"
        }
    }

    fun prettyPrint(): String {
        val sb = StringBuilder()
        sb.append(
            """
                Version: $attestationVersion
                Security Level: $attestationSecurityLevel
                KeyMint Version: $keymasterVersion
                KeyMint Security Level: $keymasterSecurityLevel
                Challenge: ${renderByteArray(attestationChallenge)}
                UniqueId: ${renderByteArray(uniqueId)}
            """.trimIndent()
        )
        sb.append("\n\n")
        sb.append("Software Enforced Authorizations:\n")
        if (softwareEnforcedAuthorizationTags.isEmpty()) {
            sb.append("  <empty>")
        } else {
            for (tag in softwareEnforcedAuthorizationTags) {
                val obj = getSoftwareAuthorizationValue(tag)
                sb.append(renderAndroidKeystoreAuthorization(tag, obj))
            }
        }
        sb.append("\n")
        sb.append("Hardware Enforced Authorizations:\n")
        if (teeEnforcedAuthorizationTags.isEmpty()) {
            sb.append("  <empty>")
        } else {
            for (tag in teeEnforcedAuthorizationTags) {
                val obj = getTeeAuthorizationValue(tag)
                sb.append(renderAndroidKeystoreAuthorization(tag, obj))
            }
        }
        return sb.toString()
    }

    companion object {
        private const val KEY_DESCRIPTION_OID = "1.3.6.1.4.1.11129.2.1.17"
        private const val ATTESTATION_VERSION_INDEX = 0
        private const val ATTESTATION_SECURITY_LEVEL_INDEX = 1
        private const val KEYMASTER_VERSION_INDEX = 2
        private const val KEYMASTER_SECURITY_LEVEL_INDEX = 3
        private const val ATTESTATION_CHALLENGE_INDEX = 4
        private const val UNIQUE_ID_INDEX = 5
        private const val SW_ENFORCED_INDEX = 6
        private const val TEE_ENFORCED_INDEX = 7

        // Some security values. The complete list is in this AOSP file:
        // hardware/libhardware/include/hardware/keymaster_defs.h
        private const val KM_SECURITY_LEVEL_SOFTWARE = 0
        private const val KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1
        private const val KM_SECURITY_LEVEL_STRONG_BOX = 2

        private fun findAuthorizationListEntry(
            authorizationMap: Map<Int, ASN1Object>, tag: Int
        ): ASN1Object? {
            return authorizationMap[tag]
        }

        private fun getBooleanFromAsn1(asn1Value: ASN1Object): Boolean {
            return if (asn1Value is ASN1Boolean) {
                asn1Value.value
            } else {
                throw RuntimeException(
                    "Boolean value expected; found " + asn1Value::class.simpleName + " instead."
                )
            }
        }

        private fun getIntegerFromAsn1(asn1Value: ASN1Object): Int {
            return if (asn1Value is ASN1Integer) {
                val longValue = asn1Value.toLong()
                if (Int.MIN_VALUE <= longValue && longValue <= Int.MAX_VALUE) {
                    longValue.toInt()
                } else {
                    throw IllegalArgumentException("Int value out of range: $longValue")
                }
            } else {
                throw IllegalArgumentException(
                    "Integer value expected; found " + asn1Value::class.simpleName + " instead."
                )
            }
        }

        private fun getLongFromAsn1(asn1Value: ASN1Object): Long {
            return if (asn1Value is ASN1Integer) {
                asn1Value.toLong()
            } else {
                throw IllegalArgumentException(
                    "Integer value expected; found " + asn1Value::class.simpleName + " instead."
                )
            }
        }

        private fun getAuthorizationMap(
            authorizationList: List<ASN1Object>
        ): Map<Int, ASN1Object> {
            val authorizationMap: MutableMap<Int, ASN1Object> = HashMap()
            for (entry in authorizationList) {
                val taggedEntry = entry as ASN1TaggedObject
                authorizationMap[taggedEntry.tag] = ASN1.decode(taggedEntry.content)
                    ?: throw IllegalArgumentException("ASN.1 parsing error")
            }
            return authorizationMap
        }

        private fun securityLevelToEnum(securityLevel: Int): AndroidKeystoreSecurityLevel {
            return when (securityLevel) {
                KM_SECURITY_LEVEL_SOFTWARE -> AndroidKeystoreSecurityLevel.SOFTWARE
                KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT
                KM_SECURITY_LEVEL_STRONG_BOX -> AndroidKeystoreSecurityLevel.STRONG_BOX
                else -> throw IllegalArgumentException("Invalid security level.")
            }
        }
    }
}
