package org.multipaz.cose

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.UnlockReason
import org.multipaz.securearea.SecureArea

/**
 * COSE support routines.
 *
 * This package includes support for COSE as specified in
 * [RFC 9052](https://datatracker.ietf.org/doc/rfc9052/).
 */
object Cose {

    /**
     * The COSE Key common parameter for the key type (tstr / int).
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#key-common-parameters
     */
    const val COSE_KEY_KTY: Long = 1

    /**
     * The COSE Key common parameter for the key id (bstr).
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#key-common-parameters
     */
    const val COSE_KEY_KID: Long = 2

    /**
     * The COSE Key type parameter for the EC curve (int / tstr).
     *
     * Values are from https://www.iana.org/assignments/cose/cose.xhtml#elliptic-curves
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#key-type-parameters
     */
    const val COSE_KEY_PARAM_CRV: Long = -1

    /**
     * The COSE Key type parameter for the X coordinate (bstr).
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#key-type-parameters
     */
    const val COSE_KEY_PARAM_X: Long = -2

    /**
     * The COSE Key type parameter for the Y coordinate (bstr / bool).
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#key-type-parameters
     */
    const val COSE_KEY_PARAM_Y: Long = -3

    /**
     * The COSE Key type parameter for the private key (bstr).
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#key-type-parameters
     */
    const val COSE_KEY_PARAM_D: Long = -4

    /**
     * The COSE Key Type for OKP.
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#key-type
     */
    const val COSE_KEY_TYPE_OKP: Long = 1

    /**
     * The COSE Key Type for EC2.
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#key-type
     */
    const val COSE_KEY_TYPE_EC2: Long = 2

    /**
     * The COSE label for conveying an algorithm.
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#header-parameters
     */
    const val COSE_LABEL_ALG = 1L

    /**
     * The COSE label for conveying an X.509 certificate chain.
     *
     * Reference: https://www.iana.org/assignments/cose/cose.xhtml#header-parameters
     */
    const val COSE_LABEL_X5CHAIN = 33L
    
    private fun coseBuildToBeSigned(
        encodedProtectedHeaders: ByteArray,
        dataToBeSigned: ByteArray,
    ): ByteArray {
        return Cbor.encode(
            buildCborArray {
                add("Signature1")
                add(encodedProtectedHeaders)

                // We currently don't support Externally Supplied Data (RFC 8152 section 4.3)
                // so external_aad is the empty bstr
                val emptyExternalAad = ByteArray(0)
                add(emptyExternalAad)

                // Next field is the payload, independently of how it's transported (RFC
                // 8152 section 4.4).
                add(dataToBeSigned)
            }
        )
    }

    /**
     * Checks a COSE_Sign1 signature.
     *
     * @param publicKey the public to check with.
     * @param detachedData detached data, if any.
     * @param signature the COSE_Sign1 object.
     * @param signatureAlgorithm the signature algorithm to use.
     * @throws IllegalArgumentException if not exactly one of `detachedData` and `signature.payload` are non-`null`.
     * @throws SignatureVerificationException if the signature check fails.
     */
    fun coseSign1Check(
        publicKey: EcPublicKey,
        detachedData: ByteArray?,
        signature: CoseSign1,
        signatureAlgorithm: Algorithm
    ) {
        require(
            (detachedData != null && signature.payload == null) ||
                    (detachedData == null && signature.payload != null)
        )
        val encodedProtectedHeaders =
            if (signature.protectedHeaders.isNotEmpty()) {
                Cbor.encode(
                    buildCborMap {
                        signature.protectedHeaders.forEach { (label, di) -> put(label.toDataItem(), di) }
                    }
                )
            } else {
                byteArrayOf()
            }
        val toBeSigned = coseBuildToBeSigned(
            encodedProtectedHeaders = encodedProtectedHeaders,
            dataToBeSigned = detachedData ?: signature.payload!!
        )
        Crypto.checkSignature(
            publicKey,
            toBeSigned,
            signatureAlgorithm,
            EcSignature.fromCoseEncoded(signature.signature))
    }

    /**
     * Creates a COSE_Sign1 signature.
     *
     * By default, the [Cose.COSE_LABEL_ALG] header is included in the protected header with the
     * algorithm specified in the key. If [protectedHeaders] already contain [Cose.COSE_LABEL_ALG]
     * it will not be replaced.
     *
     * The app can include additional headers, for example if certification is needed, [Cose.COSE_LABEL_X5CHAIN]
     * can be included in either the unprotected or protected header.
     *
     * @param signingKey key to sign with.
     * @param message the data to sign.
     * @param includeMessageInPayload whether to include the message in the COSE_Sign1 payload.
     * @param protectedHeaders the protected headers to include.
     * @param unprotectedHeaders the unprotected headers to include.
     */
    suspend fun coseSign1Sign(
        signingKey: AsymmetricKey,
        message: ByteArray,
        includeMessageInPayload: Boolean,
        protectedHeaders: Map<CoseLabel, DataItem>,
        unprotectedHeaders: Map<CoseLabel, DataItem>,
    ): CoseSign1 {
        val adjustedProtectedHeaders = mutableMapOf<CoseLabel, DataItem>()
        adjustedProtectedHeaders.putAll(protectedHeaders)

        // This is important - our key likely has a fully defined algorithm (e.g. ESP256) but
        // it's likely the receiver doesn't understand this. So downgrade to a non-fully-defined
        // algorithm via EcCurve.defaultSigningAlgorithm (e.g. ES256) unless the caller already
        // explicitly set the algorithm.
        //
        if (!protectedHeaders.containsKey(CoseNumberLabel(COSE_LABEL_ALG))) {
            val signingAlgorithmToConvey = signingKey.algorithm
            check(signingAlgorithmToConvey.coseAlgorithmIdentifier != null) {
                "Key algorithm doesn't have a COSE identifier"
            }
            adjustedProtectedHeaders[CoseNumberLabel(COSE_LABEL_ALG)] =
                signingAlgorithmToConvey.coseAlgorithmIdentifier.toDataItem()
        }

        val encodedProtectedHeaders = Cbor.encode(
            buildCborMap {
                adjustedProtectedHeaders.forEach { (label, di) -> put(label.toDataItem(), di) }
            }
        )
        val toBeSigned = coseBuildToBeSigned(encodedProtectedHeaders, message)
        val signature = signingKey.sign(toBeSigned)

        return CoseSign1(
            protectedHeaders = adjustedProtectedHeaders.toMap(),
            unprotectedHeaders = unprotectedHeaders,
            signature = signature.toCoseEncoded(),
            payload = if (includeMessageInPayload) message else null
        )
    }

    /**
     * Creates a COSE_Sign1 signature.
     *
     * By default, the [Cose.COSE_LABEL_ALG] header is included in the protected header with the non-fully-defined
     * [Algorithm] set of the key. For example for a key with [Algorithm.ESP256], the value [Algorithm.ES256]
     * is included. If [protectedHeaders] already contain [Cose.COSE_LABEL_ALG] it will not be replaced.
     *
     * The app can include additional headers, for example if certification is needed, [Cose.COSE_LABEL_X5CHAIN]
     * can be included in either the unprotected or protected header.
     *
     * This function signs with a key in a Secure Area, for signing with a software-based
     * [EcPrivateKey], see the other function with the same name but taking a [EcPrivateKey]
     * instead.
     *
     * This method remains for compatibility. New code should used
     * [AsymmetricKey]-based method instead.
     *
     * @param secureArea the [SecureArea] holding the private key.
     * @param alias the alias for the private key to use to sign with.
     * @param message the data to sign.
     * @param includeMessageInPayload whether to include the message in the COSE_Sign1 payload.
     * @param protectedHeaders the protected headers to include.
     * @param unprotectedHeaders the unprotected headers to include.
     * @param keyUnlockData a [KeyUnlockData] for unlocking the key in the [SecureArea].
     */
    @Deprecated(message = "Use SigningKey-based method instead")
    suspend fun coseSign1Sign(
        secureArea: SecureArea,
        alias: String,
        message: ByteArray,
        includeMessageInPayload: Boolean,
        protectedHeaders: Map<CoseLabel, DataItem>,
        unprotectedHeaders: Map<CoseLabel, DataItem>,
        unlockReason: UnlockReason = UnlockReason.Unspecified
    ): CoseSign1 {
        val adjustedProtectedHeaders = mutableMapOf<CoseLabel, DataItem>()
        adjustedProtectedHeaders.putAll(protectedHeaders)

        // This is important - our key likely has a fully defined algorithm (e.g. ESP256) but
        // it's likely the receiver doesn't understand this. So downgrade to a non-fully-defined
        // algorithm via EcCurve.defaultSigningAlgorithm (e.g. ES256) unless the caller already
        // explicitly set the algorithm.
        //
        if (!protectedHeaders.containsKey(CoseNumberLabel(COSE_LABEL_ALG))) {
            val keyInfo = secureArea.getKeyInfo(alias)
            val signingAlgorithmToConvey = keyInfo.algorithm.curve!!.defaultSigningAlgorithm
            check(signingAlgorithmToConvey.coseAlgorithmIdentifier != null) {
                "Key algorithm doesn't have a COSE identifier"
            }
            adjustedProtectedHeaders[CoseNumberLabel(COSE_LABEL_ALG)] =
                signingAlgorithmToConvey.coseAlgorithmIdentifier.toDataItem()
        }

        val encodedProtectedHeaders = Cbor.encode(
            buildCborMap {
                adjustedProtectedHeaders.forEach { (label, di) -> put(label.toDataItem(), di) }
            }
        )
        val toBeSigned = coseBuildToBeSigned(encodedProtectedHeaders, message)
        val signature = secureArea.sign(alias, toBeSigned, unlockReason)

        return CoseSign1(
            protectedHeaders = adjustedProtectedHeaders.toMap(),
            unprotectedHeaders = unprotectedHeaders,
            signature = signature.toCoseEncoded(),
            payload = if (includeMessageInPayload) message else null
        )
    }

    /**
     * Creates a COSE_Sign1 signature.
     *
     * By default, no headers are added. Applications likely want to include [Cose.COSE_LABEL_ALG]
     * in the protected header and if certification is needed, [Cose.COSE_LABEL_X5CHAIN] in
     * either the unprotected or protected header.
     *
     * This function signs with a software-based [EcPrivateKey], for using a key in a
     * Secure Area see the other function with the same name but taking a [SecureArea]
     * and alias.
     *
     * This method remains for compatibility. New code should used
     * [AsymmetricKey]-based method instead.
     *
     * @param key the private key to sign with.
     * @param dataToSign the data to sign.
     * @param includeDataInPayload whether to include the message in the COSE_Sign1 payload.
     * @param signatureAlgorithm the signature algorithm to use.
     * @param protectedHeaders the protected headers to include.
     * @param unprotectedHeaders the unprotected headers to include.
     */
    @Deprecated(message = "Use SigningKey-based method instead")
    fun coseSign1Sign(
        key: EcPrivateKey,
        dataToSign: ByteArray,
        includeDataInPayload: Boolean,
        signatureAlgorithm: Algorithm,
        protectedHeaders: Map<CoseLabel, DataItem>,
        unprotectedHeaders: Map<CoseLabel, DataItem>
    ): CoseSign1 {
        val encodedProtectedHeaders = if (protectedHeaders.size > 0) {
            Cbor.encode(
                buildCborMap {
                    protectedHeaders.forEach { (label, di) -> put(label.toDataItem(), di) }
                }
            )
        } else {
            byteArrayOf()
        }
        val toBeSigned = coseBuildToBeSigned(encodedProtectedHeaders, dataToSign)
        val signature = Crypto.sign(key, signatureAlgorithm, toBeSigned)

        return CoseSign1(
            protectedHeaders = protectedHeaders,
            unprotectedHeaders = unprotectedHeaders,
            signature = signature.toCoseEncoded(),
            payload = if (includeDataInPayload) dataToSign else null
        )
    }

    /**
     * Creates a COSE_Mac0 message authentication code.
     *
     * By default, no headers are added. Applications likely want to include [Cose.COSE_LABEL_ALG]
     * in the protected header.
     *
     * @param algorithm the algorithm to use, e.g. [Algorithm.HMAC_SHA256].
     * @param key the bytes of the symmetric key to use.
     * @param message the message.
     * @param includeMessageInPayload whether to include the message in the payload.
     * @param protectedHeaders the protected headers to include.
     * @param unprotectedHeaders the unprotected headers to include.
     */
    fun coseMac0(
        algorithm: Algorithm,
        key: ByteArray,
        message: ByteArray,
        includeMessageInPayload: Boolean,
        protectedHeaders: Map<CoseLabel, DataItem>,
        unprotectedHeaders: Map<CoseLabel, DataItem>,
    ): CoseMac0 {
        val encodedProtectedHeaders = if (protectedHeaders.size > 0) {
            Cbor.encode(
                buildCborMap {
                    protectedHeaders.forEach { (label, di) -> put(label.toDataItem(), di) }
                }
            )
        } else {
            byteArrayOf()
        }
        val toBeMACed = coseBuildToBeMACed(encodedProtectedHeaders, message)
        val mac = Crypto.mac(algorithm, key, toBeMACed)
        return CoseMac0(
            protectedHeaders = protectedHeaders,
            unprotectedHeaders = unprotectedHeaders,
            tag = mac,
            payload = if (includeMessageInPayload) message else null
        )
    }

    private fun coseBuildToBeMACed(
        encodedProtectedHeaders: ByteArray,
        data: ByteArray,
    ): ByteArray {
        return Cbor.encode(
            buildCborArray {
                add("MAC0")
                add(encodedProtectedHeaders)
                // We currently don't support Externally Supplied Data (RFC 8152 section 4.3)
                // so external_aad is the empty bstr
                val emptyExternalAad = ByteArray(0)
                add(emptyExternalAad)
                add(data)
            }
        )
    }
}