package org.multipaz.device

import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import org.multipaz.cose.CoseKey
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.util.getInt16
import org.multipaz.util.getInt32
import org.multipaz.crypto.SignatureVerificationException

/** On iOS device attestation is the result of Apple's DeviceCheck API. */
data class DeviceAttestationIos(
    val blob: ByteString
): DeviceAttestation() {
    override fun validate(validationData: DeviceAttestationValidationData) {
        val attestationDict = Cbor.decode(blob.toByteArray())
        val format = attestationDict["fmt"]
        val attStmt = attestationDict["attStmt"]
        val authDataItem = attestationDict["authData"]
        if (format !is Tstr || format.asTstr != "apple-appattest" ||
            attStmt !is CborMap || authDataItem !is Bstr) {
            throw DeviceAttestationException("Invalid attestation format")
        }
        val receiptItem = attStmt["receipt"]
        val x5cItem = attStmt["x5c"]
        if (receiptItem !is Bstr || x5cItem !is CborArray) {
            throw DeviceAttestationException("Invalid attestation format")
        }
        val x5c = x5cItem.asArray.map {
            try {
                X509Cert.fromDataItem(it)
            } catch (err: IllegalArgumentException) {
                throw DeviceAttestationException("Invalid certificate format", err)
            }
        }
        try {
            if (!X509CertChain(x5c).validate()) {
                throw DeviceAttestationException("Invalid certificate chain")
            }
        } catch (e: Throwable) {
            throw DeviceAttestationException("Error validating certificate chain", e)
        }
        try {
            x5c.last().verify(APPLE_ROOT_CERTIFICATE.ecPublicKey)
        } catch (e: SignatureVerificationException) {
            throw IllegalArgumentException("Invalid certificate chain", e)
        }

        // Web Authentication "Authenticator" Data defined here
        // https://www.w3.org/TR/webauthn/#sctn-authenticator-data
        val authData = authDataItem.value

        // First, validate authData integrity, calculate the hash
        val clientHash =
            Crypto.digest(Algorithm.SHA256, validationData.attestationChallenge.toByteArray())
        val composite = ByteStringBuilder().apply {
            append(authData)
            append(clientHash)
        }.toByteString()
        val authDataHash = Crypto.digest(Algorithm.SHA256, composite.toByteArray())

        // Extract the expected hash value from the leaf certificate
        val ext = x5c.first().getExtensionValue("1.2.840.113635.100.8.2")
            ?: throw DeviceAttestationException("Required extension is missing")
        val extAsn1 = ASN1.decode(ext)
            ?: throw DeviceAttestationException("ASN.1 parsing failed")
        val seq = extAsn1 as ASN1Sequence
        if (seq.elements.size != 1) {
            throw DeviceAttestationException("Extension format error")
        }
        val asn1obj = seq.elements.first()
        val tagged = asn1obj as ASN1TaggedObject
        val octetString = ASN1.decode(tagged.content) as ASN1OctetString

        // Compare the actual hash and its expected value
        if (!octetString.value.contentEquals(authDataHash)) {
            throw DeviceAttestationException("AuthData or attestationChallenge integrity error")
        }

        // Now, parse and validate the content of authData
        val auth = parseAuthData(authData)
        val appIdentifiers = validationData.iosAppIdentifiers
        if (appIdentifiers.isNotEmpty()) {
            // If acceptable app identifiers are given (some must be given for production
            // environment!), ensure app identifier is acceptable.
            var valid = false
            for (appIdentifier in appIdentifiers) {
                val appHash = Crypto.digest(Algorithm.SHA256, appIdentifier.encodeToByteArray())
                if (auth.appIdHash == ByteString(appHash)) {
                    valid = true
                    break
                }
            }
            if (!valid) {
                throw DeviceAttestationException("Application id mismatch")
            }
        } else {
            if (validationData.iosReleaseBuild) {
                throw IllegalArgumentException(
                    "iosAppIdentifier must be given if requireReleaseBuild is true")
            }
        }

        if (auth.signCount != 0) {
            throw DeviceAttestationException("Not a freshly-created attestation")
        }
        if (auth.aaguid != releaseAaguid &&
            (validationData.iosReleaseBuild || auth.aaguid != debugAaguid)) {
            if (auth.aaguid == debugAaguid) {
                throw DeviceAttestationException("Release build required")
            } else {
                throw DeviceAttestationException("Unexpected aaguid value")
            }
        }

        val publicKeyBytes =
            (x5c.first().ecPublicKey as EcPublicKeyDoubleCoordinate).asUncompressedPointEncoding
        val expectedKeyIdentifier = ByteString(Crypto.digest(Algorithm.SHA256, publicKeyBytes))
        if (auth.keyIdentifier != expectedKeyIdentifier) {
            throw DeviceAttestationException("Key identifier mismatch")
        }
    }

    override fun validateAssertion(assertion: DeviceAssertion) {
        val assertionDict = Cbor.decode(assertion.platformAssertion.toByteArray())
        val signatureItem = assertionDict["signature"]
        val authenticatorDataItem = assertionDict["authenticatorData"]
        if (signatureItem !is Bstr || authenticatorDataItem !is Bstr) {
            throw DeviceAssertionException("Invalid assertion format")
        }
        val authData = authenticatorDataItem.value

        val signature = EcSignature.fromDerEncoded(EcCurve.P256.bitSize, signatureItem.value)

        val attestationDict = Cbor.decode(blob.toByteArray())
        val attestationData = attestationDict["authData"].asBstr
        val credentialIdLength = attestationData.getInt16(CREDENTIAL_ID_LENGTH_OFFSET)
        val credentialPublicKeyOffset = CREDENTIAL_ID_OFFSET + credentialIdLength
        val (_, publicKeyItem) = Cbor.decode(attestationData, credentialPublicKeyOffset)
        val publicKey = CoseKey.fromDataItem(publicKeyItem).ecPublicKey

        val auth = parseAuthData(attestationData)
        if (auth.appIdHash != ByteString(authData.sliceArray(0..31))) {
            throw DeviceAssertionException("Application id mismatch")
        }

        // Validate authData and assertion.assertionData integrity.
        val clientHash = Crypto.digest(Algorithm.SHA256, assertion.assertionData.toByteArray())
        val composite = ByteStringBuilder().apply {
            append(authData)
            append(clientHash)
        }.toByteString()
        val hash = Crypto.digest(Algorithm.SHA256, composite.toByteArray())
        try {
            Crypto.checkSignature(publicKey, hash, Algorithm.ES256, signature)
        } catch (e: Throwable) {
            throw DeviceAssertionException("Error validating signature", e)
        }
    }

    class ParsedAuthData(
        val appIdHash: ByteString,
        val signCount: Int,
        val aaguid: ByteString,
        val keyIdentifier: ByteString,
        val publicKey: EcPublicKey
    )

    companion object {
        val APPLE_ROOT_CERTIFICATE = X509Cert.fromPem("""
            -----BEGIN CERTIFICATE-----
            MIICITCCAaegAwIBAgIQC/O+DvHN0uD7jG5yH2IXmDAKBggqhkjOPQQDAzBSMSYw
            JAYDVQQDDB1BcHBsZSBBcHAgQXR0ZXN0YXRpb24gUm9vdCBDQTETMBEGA1UECgwK
            QXBwbGUgSW5jLjETMBEGA1UECAwKQ2FsaWZvcm5pYTAeFw0yMDAzMTgxODMyNTNa
            Fw00NTAzMTUwMDAwMDBaMFIxJjAkBgNVBAMMHUFwcGxlIEFwcCBBdHRlc3RhdGlv
            biBSb290IENBMRMwEQYDVQQKDApBcHBsZSBJbmMuMRMwEQYDVQQIDApDYWxpZm9y
            bmlhMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAERTHhmLW07ATaFQIEVwTtT4dyctdh
            NbJhFs/Ii2FdCgAHGbpphY3+d8qjuDngIN3WVhQUBHAoMeQ/cLiP1sOUtgjqK9au
            Yen1mMEvRq9Sk3Jm5X8U62H+xTD3FE9TgS41o0IwQDAPBgNVHRMBAf8EBTADAQH/
            MB0GA1UdDgQWBBSskRBTM72+aEH/pwyp5frq5eWKoTAOBgNVHQ8BAf8EBAMCAQYw
            CgYIKoZIzj0EAwMDaAAwZQIwQgFGnByvsiVbpTKwSga0kP0e8EeDS4+sQmTvb7vn
            53O5+FRXgeLhpJ06ysC5PrOyAjEAp5U4xDgEgllF7En3VcE3iexZZtKeYnpqtijV
            oyFraWVIyd/dganmrduC1bmTBGwD
            -----END CERTIFICATE-----
            """.trimIndent())

        val releaseAaguid = ByteStringBuilder().apply {
            append("appattest".encodeToByteArray())
            repeat(7) {
                append(0.toByte())
            }
        }.toByteString()

        val debugAaguid = "appattestdevelop".encodeToByteString()

        // Byte offsets, sizes and flags are from
        // https://www.w3.org/TR/webauthn/#sctn-authenticator-data
        private const val RP_ID_HASH_SIZE = 32 // starts at the beginning of authData byte array
        private const val FLAGS_OFFSET = 32  // one byte
        private const val SIGN_COUNT_OFFSET = 33  // 32bit int
        private const val AAGUID_OFFSET = 37
        private const val AAGUID_SIZE = 16
        private const val CREDENTIAL_ID_LENGTH_OFFSET = 53  // 16bit int
        private const val CREDENTIAL_ID_OFFSET = 55  // 16bit int

        private const val ATTESTED_CREDENTIAL_DATA_FLAG = 0x40
        private const val EXTENSION_FLAG = 0x80

        private fun parseAuthData(authData: ByteArray): ParsedAuthData {
            val rpIdHash = ByteString(authData.sliceArray(0..<RP_ID_HASH_SIZE))
            val flags = authData[FLAGS_OFFSET].toInt() and 0xFF
            val signCount = authData.getInt32(SIGN_COUNT_OFFSET)
            if (signCount != 0) {
                throw DeviceAttestationException("Not a freshly-created attestation")
            }
            if (flags and ATTESTED_CREDENTIAL_DATA_FLAG == 0) {
                throw DeviceAttestationException("Required authData part is missing in attestation")
            }
            val aaguid = ByteString(authData.sliceArray(
                AAGUID_OFFSET ..< AAGUID_OFFSET + AAGUID_SIZE))
            val credentialIdLength = authData.getInt16(CREDENTIAL_ID_LENGTH_OFFSET)
            val credentialPublicKeyOffset = CREDENTIAL_ID_OFFSET + credentialIdLength
            val credentialId = ByteString(authData.sliceArray(
                CREDENTIAL_ID_OFFSET..<credentialPublicKeyOffset))
            val (nextOffset, publicKeyItem) = Cbor.decode(authData, credentialPublicKeyOffset)
            var offset = nextOffset

            if (flags and EXTENSION_FLAG != 0) {
                // Optional extension dictionary
                val (finalOffset, _) = Cbor.decode(authData, offset)
                offset = finalOffset
            }
            if (authData.size != offset) {
                throw DeviceAttestationException("Attestation format error: authData")
            }

            return ParsedAuthData(
                appIdHash = rpIdHash,
                signCount = signCount,
                aaguid = aaguid,
                keyIdentifier = credentialId,
                publicKey = CoseKey.fromDataItem(publicKeyItem).ecPublicKey
            )
        }
    }
}
