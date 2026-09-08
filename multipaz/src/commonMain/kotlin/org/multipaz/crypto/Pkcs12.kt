package org.multipaz.crypto

import kotlinx.coroutines.CancellationException
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1BitString
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Null
import org.multipaz.asn1.ASN1Object
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1Set
import org.multipaz.asn1.ASN1String
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.crypto.X509SignedBuilder.Companion.getCurveAlgorithmSeq
import kotlin.random.Random

/**
 * Thrown when decoding a PKCS#12 archive fails due to an invalid passphrase,
 * MAC verification failure, or cipher padding error.
 */
class Pkcs12WrongPassphraseException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * A PKCS#12 (PFX) container holding an EC private key and its associated certificate chain
 * according to [RFC 7292](https://datatracker.ietf.org/doc/html/rfc7292).
 *
 * @param privateKey the private key.
 * @param certChain the certificate chain, where the first certificate must correspond to [privateKey].
 */
data class Pkcs12(
    val privateKey: EcPrivateKey,
    val certChain: X509CertChain
) {
    init {
        require(certChain.certificates.isNotEmpty()) { "Certificate chain must not be empty" }
        require(certChain.certificates.first().ecPublicKey == privateKey.publicKey) {
            "First certificate in chain does not correspond to the private key"
        }
    }

    /**
     * Encodes this PKCS#12 container into DER format.
     *
     * @param passphrase the passphrase used to encrypt the private key and certs, and compute the MAC,
     *                   or `null` for passwordless protection.
     * @param iterations iteration count for PBKDF2 and MAC derivation (default [DEFAULT_ITERATIONS]).
     * @return the DER-encoded PKCS#12 file as [ByteString].
     * @throws IllegalArgumentException if the key algorithm or curve is not supported.
     */
    @Throws(
        CancellationException::class,
        IllegalArgumentException::class
    )
    suspend fun toDer(
        passphrase: String? = null,
        iterations: Int = DEFAULT_ITERATIONS
    ): ByteString {
        val effectivePassphrase = passphrase ?: ""
        val subjectPublicKey = when (val pub = privateKey.publicKey) {
            is EcPublicKeyDoubleCoordinate -> pub.asUncompressedPointEncoding
            is EcPublicKeyOkp -> pub.x
        }
        val localKeyId = Crypto.digest(Algorithm.INSECURE_SHA1, subjectPublicKey)

        // 1. Build PrivateKeyInfo
        val privateKeyInfoDer = encodePrivateKeyInfo(privateKey)

        // 2. Encrypt PrivateKeyInfo into ShroudedKeyBag
        val keySalt = Random.Default.nextBytes(16)
        val keyIv = Random.Default.nextBytes(16)
        val derivedKey = Pbkdf2.deriveKey(
            prfAlgorithm = Algorithm.HMAC_SHA256,
            password = effectivePassphrase.encodeToByteArray(),
            salt = keySalt,
            iterationCount = iterations,
            keyLength = 32
        )
        val encryptedKey = Crypto.encrypt(Algorithm.A256CBC, derivedKey, keyIv, privateKeyInfoDer)
        val encryptedKeyInfo = ASN1Sequence(listOf(
            buildPbes2AlgorithmIdentifier(keySalt, iterations, Algorithm.HMAC_SHA256, keyIv, 32),
            ASN1OctetString(encryptedKey)
        ))

        val shroudedKeyBag = ASN1Sequence(listOf(
            ASN1ObjectIdentifier(OID.PKCS12_PKCS8_SHROUDED_KEY_BAG.oid),
            ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, 0, ASN1.encode(encryptedKeyInfo)),
            ASN1Set(listOf(
                buildLocalKeyIdAttribute(localKeyId)
            ))
        ))
        val keySafeContents = ASN1Sequence(listOf(shroudedKeyBag))
        val keyContentInfo = ASN1Sequence(listOf(
            ASN1ObjectIdentifier(OID.PKCS7_DATA.oid),
            ASN1TaggedObject(
                ASN1TagClass.CONTEXT_SPECIFIC,
                ASN1Encoding.CONSTRUCTED,
                0,
                ASN1.encode(ASN1OctetString(ASN1.encode(keySafeContents)))
            )
        ))

        // 3. Build Cert SafeBags and EncryptedData
        val certBags = certChain.certificates.mapIndexed { index, cert ->
            val certBagValue = ASN1Sequence(listOf(
                ASN1ObjectIdentifier(OID.PKCS12_X509_CERTIFICATE.oid),
                ASN1TaggedObject(
                    ASN1TagClass.CONTEXT_SPECIFIC,
                    ASN1Encoding.CONSTRUCTED,
                    0,
                    ASN1.encode(ASN1OctetString(cert.encoded.toByteArray()))
                )
            ))
            val bagAttrs = mutableListOf<ASN1Object>()
            if (index == 0) {
                bagAttrs.add(buildLocalKeyIdAttribute(localKeyId))
            }
            val bagElements = mutableListOf<ASN1Object>(
                ASN1ObjectIdentifier(OID.PKCS12_CERT_BAG.oid),
                ASN1TaggedObject(
                    ASN1TagClass.CONTEXT_SPECIFIC,
                    ASN1Encoding.CONSTRUCTED,
                    0,
                    ASN1.encode(certBagValue)
                )
            )
            if (bagAttrs.isNotEmpty()) {
                bagElements.add(ASN1Set(bagAttrs))
            }
            ASN1Sequence(bagElements)
        }
        val certsSafeContents = ASN1Sequence(certBags)
        val certsSafeContentsDer = ASN1.encode(certsSafeContents)

        val certSalt = Random.Default.nextBytes(16)
        val certIv = Random.Default.nextBytes(16)
        val derivedCertKey = Pbkdf2.deriveKey(
            prfAlgorithm = Algorithm.HMAC_SHA256,
            password = effectivePassphrase.encodeToByteArray(),
            salt = certSalt,
            iterationCount = iterations,
            keyLength = 32
        )
        val encryptedCerts = Crypto.encrypt(Algorithm.A256CBC, derivedCertKey, certIv, certsSafeContentsDer)

        val encryptedContentInfo = ASN1Sequence(listOf(
            ASN1ObjectIdentifier(OID.PKCS7_DATA.oid),
            buildPbes2AlgorithmIdentifier(certSalt, iterations, Algorithm.HMAC_SHA256, certIv, 32),
            ASN1TaggedObject(
                ASN1TagClass.CONTEXT_SPECIFIC,
                ASN1Encoding.PRIMITIVE,
                0,
                encryptedCerts
            )
        ))
        val encryptedData = ASN1Sequence(listOf(
            ASN1Integer(0L),
            encryptedContentInfo
        ))
        val certsContentInfo = ASN1Sequence(listOf(
            ASN1ObjectIdentifier(OID.PKCS7_ENCRYPTED_DATA.oid),
            ASN1TaggedObject(
                ASN1TagClass.CONTEXT_SPECIFIC,
                ASN1Encoding.CONSTRUCTED,
                0,
                ASN1.encode(encryptedData)
            )
        ))

        // 4. Assemble AuthenticatedSafe
        val authenticatedSafe = ASN1Sequence(listOf(certsContentInfo, keyContentInfo))
        val authSafeOctets = ASN1.encode(authenticatedSafe)

        val authSafeContentInfo = ASN1Sequence(listOf(
            ASN1ObjectIdentifier(OID.PKCS7_DATA.oid),
            ASN1TaggedObject(
                ASN1TagClass.CONTEXT_SPECIFIC,
                ASN1Encoding.CONSTRUCTED,
                0,
                ASN1.encode(ASN1OctetString(authSafeOctets))
            )
        ))

        // 5. Compute MAC
        val macSalt = Random.Default.nextBytes(16)
        val macKey = Pkcs12Kdf.deriveKey(
            idByte = Pkcs12Kdf.ID_MAC_KEY,
            passphrase = effectivePassphrase,
            salt = macSalt,
            iterationCount = iterations,
            keyLength = 32,
            algorithm = Algorithm.SHA256
        )
        val macValue = Crypto.mac(Algorithm.HMAC_SHA256, macKey, authSafeOctets)
        val macData = ASN1Sequence(listOf(
            ASN1Sequence(listOf(
                ASN1Sequence(listOf(
                    ASN1ObjectIdentifier(OID.SHA256.oid),
                    ASN1Null()
                )),
                ASN1OctetString(macValue)
            )),
            ASN1OctetString(macSalt),
            ASN1Integer(iterations.toLong())
        ))

        // 6. Assemble PFX
        val pfx = ASN1Sequence(listOf(
            ASN1Integer(3L),
            authSafeContentInfo,
            macData
        ))

        return ByteString(ASN1.encode(pfx))
    }

    companion object {
        /**
         * The default iteration count used for PBKDF2 key derivation and MAC derivation (2048).
         */
        const val DEFAULT_ITERATIONS = 2048

        /**
         * Decodes a PKCS#12 container from DER format.
         *
         * @param derEncoded the DER-encoded PKCS#12 bytes.
         * @param passphrase the passphrase used to decrypt and verify the file, or `null` if no passphrase is provided.
         * @return a [Pkcs12] instance containing the [EcPrivateKey] and [X509CertChain].
         * @throws Pkcs12WrongPassphraseException if the passphrase is incorrect or MAC verification fails.
         * @throws IllegalArgumentException if the data is not a valid PKCS#12 file or required components are missing.
         */
        @Throws(
            CancellationException::class,
            Pkcs12WrongPassphraseException::class,
            IllegalArgumentException::class
        )
        suspend fun fromDer(
            derEncoded: ByteString,
            passphrase: String? = null
        ): Pkcs12 {
            val effectivePassphrase = passphrase ?: ""
            val rootObj = try {
                ASN1.decode(derEncoded.toByteArray())
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid ASN.1 in PKCS#12 data", e)
            }
            val pfxSeq = rootObj as? ASN1Sequence
                ?: throw IllegalArgumentException("Expected SEQUENCE at root of PKCS#12")

            val version = (pfxSeq.elements.getOrNull(0) as? ASN1Integer)?.toLong()
            if (version != 3L) {
                throw IllegalArgumentException("Expected PKCS#12 version 3, got $version")
            }

            val authSafeContentInfo = pfxSeq.elements.getOrNull(1) as? ASN1Sequence
                ?: throw IllegalArgumentException("Missing authSafe ContentInfo in PKCS#12")
            val authSafeTypeOid = (authSafeContentInfo.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid
            if (authSafeTypeOid != OID.PKCS7_DATA.oid) {
                throw IllegalArgumentException("Unsupported authSafe contentType: $authSafeTypeOid")
            }

            val authSafeTagged = authSafeContentInfo.elements.getOrNull(1) as? ASN1TaggedObject
                ?: throw IllegalArgumentException("Missing authSafe content")
            val authSafeOctetString = (ASN1.decode(authSafeTagged.content) as? ASN1OctetString)
                ?: throw IllegalArgumentException("Expected OCTET STRING inside authSafe")
            val authSafeOctets = authSafeOctetString.value

            // Verify MAC if present
            if (pfxSeq.elements.size > 2) {
                val macDataSeq = pfxSeq.elements[2] as? ASN1Sequence
                if (macDataSeq != null) {
                    verifyMac(macDataSeq, authSafeOctets, effectivePassphrase)
                }
            }

            // Parse AuthenticatedSafe
            val authSafeObj = try {
                ASN1.decode(authSafeOctets)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid ASN.1 in AuthenticatedSafe", e)
            }
            val authSafeSeq = authSafeObj as? ASN1Sequence
                ?: throw IllegalArgumentException("Expected SEQUENCE for AuthenticatedSafe")

            val rawPrivateKeys = mutableListOf<Pair<ByteArray, ByteArray?>>() // (PrivateKeyInfoDer, localKeyId)
            val certificates = mutableListOf<Pair<X509Cert, ByteArray?>>()     // (X509Cert, localKeyId)

            for (contentInfoObj in authSafeSeq.elements) {
                val contentInfo = contentInfoObj as? ASN1Sequence ?: continue
                val contentTypeOid = (contentInfo.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid ?: continue
                val contentTagged = contentInfo.elements.getOrNull(1) as? ASN1TaggedObject ?: continue

                when (contentTypeOid) {
                    OID.PKCS7_DATA.oid -> {
                        val octetString = ASN1.decode(contentTagged.content) as? ASN1OctetString
                        if (octetString != null) {
                            val safeContents = ASN1.decode(octetString.value) as? ASN1Sequence
                            if (safeContents != null) {
                                parseSafeContents(safeContents, effectivePassphrase, rawPrivateKeys, certificates)
                            }
                        }
                    }

                    OID.PKCS7_ENCRYPTED_DATA.oid -> {
                        val encryptedDataSeq = ASN1.decode(contentTagged.content) as? ASN1Sequence
                            ?: throw IllegalArgumentException("Expected SEQUENCE for EncryptedData")
                        val encryptedContentInfo = encryptedDataSeq.elements.getOrNull(1) as? ASN1Sequence
                            ?: throw IllegalArgumentException("Missing EncryptedContentInfo")
                        val contentEncryptionAlg = encryptedContentInfo.elements.getOrNull(1) as? ASN1Sequence
                            ?: throw IllegalArgumentException("Missing contentEncryptionAlgorithm")
                        val encryptedContentTagged = encryptedContentInfo.elements.getOrNull(2) as? ASN1TaggedObject
                            ?: throw IllegalArgumentException("Missing encryptedContent")
                        val ciphertext = encryptedContentTagged.content

                        val decryptedSafeContentsDer = decryptPbes2(contentEncryptionAlg, ciphertext, effectivePassphrase)
                        val safeContents = ASN1.decode(decryptedSafeContentsDer) as? ASN1Sequence
                            ?: throw IllegalArgumentException("Expected SEQUENCE for decrypted SafeContents")
                        parseSafeContents(safeContents, effectivePassphrase, rawPrivateKeys, certificates)
                    }
                }
            }

            if (certificates.isEmpty()) {
                throw IllegalArgumentException("No certificates found in PKCS#12 file")
            }
            if (rawPrivateKeys.isEmpty()) {
                throw IllegalArgumentException("No private key found in PKCS#12 file")
            }

            // Find the matching private key and certificate chain
            val certList = certificates.map { it.first }
            val (keyInfoDer, keyLocalId) = rawPrivateKeys.first()

            // Try matching leaf cert by localKeyId first, or by public key match
            var leafCert: X509Cert? = null
            if (keyLocalId != null) {
                leafCert = certificates.firstOrNull { it.second?.contentEquals(keyLocalId) == true }?.first
            }

            val privateKey = if (leafCert != null) {
                decodePrivateKeyInfo(keyInfoDer, leafCert.ecPublicKey)
            } else {
                // Try decoding with each cert's public key until one succeeds and matches
                var decodedKey: EcPrivateKey? = null
                for (cert in certList) {
                    try {
                        val key = decodePrivateKeyInfo(keyInfoDer, cert.ecPublicKey)
                        if (key.publicKey == cert.ecPublicKey) {
                            decodedKey = key
                            leafCert = cert
                            break
                        }
                    } catch (_: Exception) {
                    }
                }
                decodedKey ?: decodePrivateKeyInfo(keyInfoDer, null)
            }

            if (leafCert == null) {
                leafCert = certList.firstOrNull { it.ecPublicKey == privateKey.publicKey }
                    ?: throw IllegalArgumentException("No certificate matches the private key in PKCS#12 file")
            }

            // Order the certificate chain starting with leafCert
            val orderedChain = orderCertificates(leafCert, certList)
            return Pkcs12(privateKey, X509CertChain(orderedChain))
        }

        private suspend fun verifyMac(
            macDataSeq: ASN1Sequence,
            authSafeOctets: ByteArray,
            passphrase: String
        ) {
            val digestInfo = macDataSeq.elements.getOrNull(0) as? ASN1Sequence
                ?: throw IllegalArgumentException("Missing DigestInfo in MacData")
            val digestAlgorithmSeq = digestInfo.elements.getOrNull(0) as? ASN1Sequence
                ?: throw IllegalArgumentException("Missing digestAlgorithm in DigestInfo")
            val digestOid = (digestAlgorithmSeq.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid
                ?: throw IllegalArgumentException("Missing digest OID in DigestInfo")
            val expectedMac = (digestInfo.elements.getOrNull(1) as? ASN1OctetString)?.value
                ?: throw IllegalArgumentException("Missing digest in DigestInfo")

            val macSalt = (macDataSeq.elements.getOrNull(1) as? ASN1OctetString)?.value
                ?: throw IllegalArgumentException("Missing macSalt in MacData")
            val iterations = (macDataSeq.elements.getOrNull(2) as? ASN1Integer)?.toLong()?.toInt() ?: 1

            val (hashAlgorithm, hmacAlgorithm, keyLen) = when (digestOid) {
                OID.SHA256.oid, "2.16.840.1.101.3.4.2.1" -> Triple(Algorithm.SHA256, Algorithm.HMAC_SHA256, 32)
                OID.SHA1.oid, "1.3.14.3.2.26" -> Triple(Algorithm.INSECURE_SHA1, Algorithm.HMAC_INSECURE_SHA1, 20)
                OID.SHA384.oid, "2.16.840.1.101.3.4.2.2" -> Triple(Algorithm.SHA384, Algorithm.HMAC_SHA384, 48)
                OID.SHA512.oid, "2.16.840.1.101.3.4.2.3" -> Triple(Algorithm.SHA512, Algorithm.HMAC_SHA512, 64)
                else -> throw IllegalArgumentException("Unsupported MAC digest algorithm OID: $digestOid")
            }

            val derivedMacKey = Pkcs12Kdf.deriveKey(
                idByte = Pkcs12Kdf.ID_MAC_KEY,
                passphrase = passphrase,
                salt = macSalt,
                iterationCount = iterations,
                keyLength = keyLen,
                algorithm = hashAlgorithm
            )
            val computedMac = Crypto.mac(hmacAlgorithm, derivedMacKey, authSafeOctets)

            if (!computedMac.contentEquals(expectedMac)) {
                throw Pkcs12WrongPassphraseException("MAC verification failed: wrong passphrase or corrupted PKCS#12 file")
            }
        }

        private suspend fun parseSafeContents(
            safeContents: ASN1Sequence,
            passphrase: String,
            rawPrivateKeys: MutableList<Pair<ByteArray, ByteArray?>>,
            certificates: MutableList<Pair<X509Cert, ByteArray?>>
        ) {
            for (bagObj in safeContents.elements) {
                val bag = bagObj as? ASN1Sequence ?: continue
                val bagId = (bag.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid ?: continue
                val bagValueTagged = bag.elements.getOrNull(1) as? ASN1TaggedObject ?: continue
                val bagAttrs = bag.elements.getOrNull(2) as? ASN1Set
                val localKeyId = extractLocalKeyId(bagAttrs)

                when (bagId) {
                    OID.PKCS12_PKCS8_SHROUDED_KEY_BAG.oid -> {
                        val encryptedPrivateKeyInfo = ASN1.decode(bagValueTagged.content) as? ASN1Sequence
                            ?: throw IllegalArgumentException("Expected SEQUENCE for EncryptedPrivateKeyInfo")
                        val encryptionAlgorithm = encryptedPrivateKeyInfo.elements.getOrNull(0) as? ASN1Sequence
                            ?: throw IllegalArgumentException("Missing encryptionAlgorithm in EncryptedPrivateKeyInfo")
                        val encryptedData = (encryptedPrivateKeyInfo.elements.getOrNull(1) as? ASN1OctetString)?.value
                            ?: throw IllegalArgumentException("Missing encryptedData in EncryptedPrivateKeyInfo")

                        val privateKeyInfoDer = decryptPbes2(encryptionAlgorithm, encryptedData, passphrase)
                        rawPrivateKeys.add(Pair(privateKeyInfoDer, localKeyId))
                    }

                    OID.PKCS12_KEY_BAG.oid -> {
                        val privateKeyInfoDer = bagValueTagged.content
                        rawPrivateKeys.add(Pair(privateKeyInfoDer, localKeyId))
                    }

                    OID.PKCS12_CERT_BAG.oid -> {
                        val certBagSeq = ASN1.decode(bagValueTagged.content) as? ASN1Sequence
                            ?: throw IllegalArgumentException("Expected SEQUENCE for CertBag")
                        val certId = (certBagSeq.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid
                        val certValueTagged = certBagSeq.elements.getOrNull(1) as? ASN1TaggedObject
                        if (certId == OID.PKCS12_X509_CERTIFICATE.oid && certValueTagged != null) {
                            val certOctets = (ASN1.decode(certValueTagged.content) as? ASN1OctetString)?.value
                                ?: certValueTagged.content
                            val cert = X509Cert(ByteString(certOctets))
                            certificates.add(Pair(cert, localKeyId))
                        }
                    }
                }
            }
        }

        private fun extractLocalKeyId(bagAttrs: ASN1Set?): ByteArray? {
            if (bagAttrs == null) return null
            for (attrObj in bagAttrs.elements) {
                val attrSeq = attrObj as? ASN1Sequence ?: continue
                val attrType = (attrSeq.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid ?: continue
                if (attrType == OID.PKCS12_LOCAL_KEY_ID.oid) {
                    val attrValues = attrSeq.elements.getOrNull(1) as? ASN1Set ?: continue
                    val octetString = attrValues.elements.getOrNull(0) as? ASN1OctetString ?: continue
                    return octetString.value
                }
            }
            return null
        }

        private suspend fun decryptPbes2(
            encryptionAlgorithm: ASN1Sequence,
            ciphertext: ByteArray,
            passphrase: String
        ): ByteArray {
            val pbes2Oid = (encryptionAlgorithm.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid
            if (pbes2Oid != OID.PBES2.oid) {
                throw IllegalArgumentException("Unsupported encryption algorithm: $pbes2Oid (only PBES2 is supported)")
            }
            val pbes2Params = encryptionAlgorithm.elements.getOrNull(1) as? ASN1Sequence
                ?: throw IllegalArgumentException("Missing PBES2-params")

            val kdfSeq = pbes2Params.elements.getOrNull(0) as? ASN1Sequence
                ?: throw IllegalArgumentException("Missing keyDerivationFunc in PBES2-params")
            val kdfOid = (kdfSeq.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid
            if (kdfOid != OID.PBKDF2.oid) {
                throw IllegalArgumentException("Unsupported KDF algorithm: $kdfOid (only PBKDF2 is supported)")
            }
            val pbkdf2Params = kdfSeq.elements.getOrNull(1) as? ASN1Sequence
                ?: throw IllegalArgumentException("Missing PBKDF2-params")

            val salt = (pbkdf2Params.elements.getOrNull(0) as? ASN1OctetString)?.value
                ?: throw IllegalArgumentException("Missing salt in PBKDF2-params")
            val iterationCount = (pbkdf2Params.elements.getOrNull(1) as? ASN1Integer)?.toLong()?.toInt()
                ?: throw IllegalArgumentException("Missing iterationCount in PBKDF2-params")

            var prfAlgorithm = Algorithm.HMAC_INSECURE_SHA1 // Default per RFC 8018
            var keyLength = 32

            for (i in 2 until pbkdf2Params.elements.size) {
                val elem = pbkdf2Params.elements[i]
                if (elem is ASN1Integer) {
                    keyLength = elem.toLong().toInt()
                } else if (elem is ASN1Sequence) {
                    val prfOid = (elem.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid
                    prfAlgorithm = when (prfOid) {
                        OID.HMAC_WITH_SHA256.oid, "1.2.840.113549.2.9" -> Algorithm.HMAC_SHA256
                        OID.HMAC_WITH_SHA1.oid, "1.2.840.113549.2.7" -> Algorithm.HMAC_INSECURE_SHA1
                        OID.HMAC_WITH_SHA384.oid, "1.2.840.113549.2.10" -> Algorithm.HMAC_SHA384
                        OID.HMAC_WITH_SHA512.oid, "1.2.840.113549.2.11" -> Algorithm.HMAC_SHA512
                        else -> throw IllegalArgumentException("Unsupported PBKDF2 PRF algorithm OID: $prfOid")
                    }
                }
            }

            val encSchemeSeq = pbes2Params.elements.getOrNull(1) as? ASN1Sequence
                ?: throw IllegalArgumentException("Missing encryptionScheme in PBES2-params")
            val encSchemeOid = (encSchemeSeq.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid
                ?: throw IllegalArgumentException("Missing encryptionScheme algorithm OID")
            val iv = (encSchemeSeq.elements.getOrNull(1) as? ASN1OctetString)?.value
                ?: throw IllegalArgumentException("Missing IV in encryptionScheme parameters")

            keyLength = when (encSchemeOid) {
                OID.AES128_CBC.oid, "2.16.840.1.101.3.4.1.2" -> 16
                OID.AES192_CBC.oid, "2.16.840.1.101.3.4.1.22" -> 24
                OID.AES256_CBC.oid, "2.16.840.1.101.3.4.1.42" -> 32
                else -> throw IllegalArgumentException("Unsupported encryption scheme OID: $encSchemeOid")
            }

            val key = Pbkdf2.deriveKey(
                prfAlgorithm = prfAlgorithm,
                password = passphrase.encodeToByteArray(),
                salt = salt,
                iterationCount = iterationCount,
                keyLength = keyLength
            )

            val encAlgorithm = when (keyLength) {
                16 -> Algorithm.A128CBC
                24 -> Algorithm.A192CBC
                32 -> Algorithm.A256CBC
                else -> throw IllegalArgumentException("Unsupported key length $keyLength")
            }

            return try {
                Crypto.decrypt(encAlgorithm, key, iv, ciphertext)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw Pkcs12WrongPassphraseException("Failed to decrypt PBES2 ciphertext", e)
            }
        }

        private fun buildPbes2AlgorithmIdentifier(
            salt: ByteArray,
            iterations: Int,
            prf: Algorithm,
            iv: ByteArray,
            keyLen: Int
        ): ASN1Sequence {
            val prfOid = when (prf) {
                Algorithm.HMAC_SHA256 -> OID.HMAC_WITH_SHA256.oid
                Algorithm.HMAC_INSECURE_SHA1 -> OID.HMAC_WITH_SHA1.oid
                Algorithm.HMAC_SHA384 -> OID.HMAC_WITH_SHA384.oid
                Algorithm.HMAC_SHA512 -> OID.HMAC_WITH_SHA512.oid
                else -> throw IllegalArgumentException("Unsupported PRF: $prf")
            }
            val pbkdf2Params = ASN1Sequence(listOf(
                ASN1OctetString(salt),
                ASN1Integer(iterations.toLong()),
                ASN1Sequence(listOf(
                    ASN1ObjectIdentifier(prfOid),
                    ASN1Null()
                ))
            ))
            val kdfAlgorithm = ASN1Sequence(listOf(
                ASN1ObjectIdentifier(OID.PBKDF2.oid),
                pbkdf2Params
            ))

            val encOid = when (keyLen) {
                16 -> OID.AES128_CBC.oid
                24 -> OID.AES192_CBC.oid
                32 -> OID.AES256_CBC.oid
                else -> throw IllegalArgumentException("Invalid AES key length: $keyLen")
            }
            val encScheme = ASN1Sequence(listOf(
                ASN1ObjectIdentifier(encOid),
                ASN1OctetString(iv)
            ))

            return ASN1Sequence(listOf(
                ASN1ObjectIdentifier(OID.PBES2.oid),
                ASN1Sequence(listOf(
                    kdfAlgorithm,
                    encScheme
                ))
            ))
        }

        private fun buildLocalKeyIdAttribute(localKeyId: ByteArray): ASN1Sequence =
            ASN1Sequence(listOf(
                ASN1ObjectIdentifier(OID.PKCS12_LOCAL_KEY_ID.oid),
                ASN1Set(listOf(
                    ASN1OctetString(localKeyId)
                ))
            ))

        private fun encodePrivateKeyInfo(privateKey: EcPrivateKey): ByteArray {
            val (curveOid, ecPrivateKeyStructure) = when (privateKey) {
                is EcPrivateKeyDoubleCoordinate -> {
                    val oid = when (privateKey.curve) {
                        EcCurve.P256 -> OID.EC_CURVE_P256
                        EcCurve.P384 -> OID.EC_CURVE_P384
                        EcCurve.P521 -> OID.EC_CURVE_P521
                        EcCurve.BRAINPOOLP256R1 -> OID.EC_CURVE_BRAINPOOLP256R1
                        EcCurve.BRAINPOOLP320R1 -> OID.EC_CURVE_BRAINPOOLP320R1
                        EcCurve.BRAINPOOLP384R1 -> OID.EC_CURVE_BRAINPOOLP384R1
                        EcCurve.BRAINPOOLP512R1 -> OID.EC_CURVE_BRAINPOOLP512R1
                        else -> throw IllegalStateException("Unexpected curve ${privateKey.curve}")
                    }
                    val pub = privateKey.publicKey as EcPublicKeyDoubleCoordinate
                    val ecPrivSeq = ASN1Sequence(listOf(
                        ASN1Integer(1L),
                        ASN1OctetString(privateKey.d),
                        ASN1TaggedObject(
                            cls = ASN1TagClass.CONTEXT_SPECIFIC,
                            enc = ASN1Encoding.CONSTRUCTED,
                            tag = 0,
                            content = ASN1.encode(ASN1ObjectIdentifier(oid.oid))
                        ),
                        ASN1TaggedObject(
                            cls = ASN1TagClass.CONTEXT_SPECIFIC,
                            enc = ASN1Encoding.CONSTRUCTED,
                            tag = 1,
                            content = ASN1.encode(ASN1BitString(0, pub.asUncompressedPointEncoding))
                        )
                    ))
                    Pair(oid, ASN1.encode(ecPrivSeq))
                }
                is EcPrivateKeyOkp -> {
                    Pair(null, ASN1.encode(ASN1OctetString(privateKey.d)))
                }
            }

            val privateKeyInfoSeq = ASN1Sequence(listOf(
                ASN1Integer(0L),
                privateKey.curve.getCurveAlgorithmSeq(),
                ASN1OctetString(ecPrivateKeyStructure)
            ))
            return ASN1.encode(privateKeyInfoSeq)
        }

        private fun decodePrivateKeyInfo(
            privateKeyInfoDer: ByteArray,
            matchingCertPublicKey: EcPublicKey?
        ): EcPrivateKey {
            val privateKeyInfo = ASN1.decode(privateKeyInfoDer) as? ASN1Sequence
                ?: throw IllegalArgumentException("Expected SEQUENCE for PrivateKeyInfo")

            val version = (privateKeyInfo.elements.getOrNull(0) as? ASN1Integer)?.toLong()
            if (version != 0L) {
                throw IllegalArgumentException("Expected version 0 for PrivateKeyInfo, got $version")
            }

            val privateKeyAlgorithm = privateKeyInfo.elements.getOrNull(1) as? ASN1Sequence
                ?: throw IllegalArgumentException("Missing privateKeyAlgorithm in PrivateKeyInfo")
            val algorithmOid = (privateKeyAlgorithm.elements.getOrNull(0) as? ASN1ObjectIdentifier)?.oid
                ?: throw IllegalArgumentException("Missing algorithm OID in PrivateKeyInfo")

            val curve = when (algorithmOid) {
                OID.EC_PUBLIC_KEY.oid, "1.2.840.10045.2.1" -> {
                    val ecCurveOid = (privateKeyAlgorithm.elements.getOrNull(1) as? ASN1ObjectIdentifier)?.oid
                    when (ecCurveOid) {
                        OID.EC_CURVE_P256.oid, "1.2.840.10045.3.1.7" -> EcCurve.P256
                        OID.EC_CURVE_P384.oid, "1.3.132.0.34" -> EcCurve.P384
                        OID.EC_CURVE_P521.oid, "1.3.132.0.35" -> EcCurve.P521
                        OID.EC_CURVE_BRAINPOOLP256R1.oid, "1.3.36.3.3.2.8.1.1.7" -> EcCurve.BRAINPOOLP256R1
                        OID.EC_CURVE_BRAINPOOLP320R1.oid, "1.3.36.3.3.2.8.1.1.9" -> EcCurve.BRAINPOOLP320R1
                        OID.EC_CURVE_BRAINPOOLP384R1.oid, "1.3.36.3.3.2.8.1.1.11" -> EcCurve.BRAINPOOLP384R1
                        OID.EC_CURVE_BRAINPOOLP512R1.oid, "1.3.36.3.3.2.8.1.1.13" -> EcCurve.BRAINPOOLP512R1
                        else -> throw IllegalArgumentException("Unsupported EC curve OID: $ecCurveOid")
                    }
                }
                OID.ED25519.oid, "1.3.101.112" -> EcCurve.ED25519
                OID.ED448.oid, "1.3.101.113" -> EcCurve.ED448
                OID.X25519.oid, "1.3.101.110" -> EcCurve.X25519
                OID.X448.oid, "1.3.101.111" -> EcCurve.X448
                else -> throw IllegalArgumentException("Unsupported private key algorithm OID: $algorithmOid")
            }

            val privateKeyOctetString = privateKeyInfo.elements.getOrNull(2) as? ASN1OctetString
                ?: throw IllegalArgumentException("Missing privateKey OCTET STRING in PrivateKeyInfo")

            return when (curve) {
                EcCurve.P256,
                EcCurve.P384,
                EcCurve.P521,
                EcCurve.BRAINPOOLP256R1,
                EcCurve.BRAINPOOLP320R1,
                EcCurve.BRAINPOOLP384R1,
                EcCurve.BRAINPOOLP512R1 -> {
                    val ecPrivSeq = ASN1.decode(privateKeyOctetString.value) as? ASN1Sequence
                        ?: throw IllegalArgumentException("Expected SEQUENCE for ECPrivateKey")
                    val keyMaterial = (ecPrivSeq.elements.getOrNull(1) as? ASN1OctetString)?.value
                        ?: throw IllegalArgumentException("Missing key material in ECPrivateKey")

                    // Look for public key in tag [1] if present
                    val pubKeyBitString = ecPrivSeq.elements
                        .filterIsInstance<ASN1TaggedObject>()
                        .firstOrNull { it.tag == 1 }
                        ?.let { ASN1.decode(it.content) as? ASN1BitString }

                    val pubKey = if (pubKeyBitString != null) {
                        EcPublicKeyDoubleCoordinate.fromUncompressedPointEncoding(curve, pubKeyBitString.value)
                    } else if (matchingCertPublicKey is EcPublicKeyDoubleCoordinate && matchingCertPublicKey.curve == curve) {
                        matchingCertPublicKey
                    } else {
                        throw IllegalArgumentException("Public key point not found in ECPrivateKey and no matching certificate supplied")
                    }

                    EcPrivateKeyDoubleCoordinate(
                        curve = curve,
                        d = keyMaterial,
                        x = pubKey.x,
                        y = pubKey.y
                    )
                }

                EcCurve.ED25519,
                EcCurve.X25519,
                EcCurve.ED448,
                EcCurve.X448 -> {
                    val keyMaterial = (ASN1.decode(privateKeyOctetString.value) as? ASN1OctetString)?.value
                        ?: privateKeyOctetString.value

                    val pubKey = if (matchingCertPublicKey is EcPublicKeyOkp && matchingCertPublicKey.curve == curve) {
                        matchingCertPublicKey
                    } else {
                        throw IllegalArgumentException("Public key not found for OKP private key")
                    }

                    EcPrivateKeyOkp(
                        curve = curve,
                        d = keyMaterial,
                        x = pubKey.x
                    )
                }
            }
        }

        private fun orderCertificates(leaf: X509Cert, allCerts: List<X509Cert>): List<X509Cert> {
            val ordered = mutableListOf(leaf)
            val remaining = allCerts.filter { it != leaf }.toMutableList()

            var current = leaf
            while (remaining.isNotEmpty()) {
                val issuer = remaining.firstOrNull { it.subject == current.issuer }
                if (issuer != null) {
                    ordered.add(issuer)
                    remaining.remove(issuer)
                    if (issuer.subject == issuer.issuer) {
                        // Root CA reached
                        break
                    }
                    current = issuer
                } else {
                    break
                }
            }
            ordered.addAll(remaining)
            return ordered
        }
    }
}
