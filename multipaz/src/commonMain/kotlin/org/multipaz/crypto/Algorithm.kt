package org.multipaz.crypto

import kotlin.enums.enumEntries

/**
 * Algorithm identifiers.
 *
 * This is an enumeration of algorithms and has support for multiple registries, including
 * [COSE](https://www.iana.org/assignments/cose/cose.xhtml#algorithms),
 * [JOSE](https://www.iana.org/assignments/jose/jose.xhtml#web-signature-encryption-algorithms), and the
 * [Named Information Hash Algorithm Registry](https://www.iana.org/assignments/named-information/named-information.xhtml#hash-alg).
 *
 * Applications should not rely on the ordinals in this enumeration, they might change in the future as algorithms
 * are added or things are rearranged. Use [name] for a stable identifier if serialization is needed.
 *
 * @param coseAlgorithmIdentifier the [COSE algorithm identifier](https://www.iana.org/assignments/cose/cose.xhtml#algorithms)
 *   or `null` if this algorithm does not exists in this registry.
 * @param joseAlgorithmIdentifier the [JOSE algorithm identifier](https://www.iana.org/assignments/jose/jose.xhtml#web-signature-encryption-algorithms)
 *   or `null` if this algorithm does not exists in this registry.
 * @param hashAlgorithmName the hash algorithm name from the
 *   [Named Information Hash Algorithm Registry](https://www.iana.org/assignments/named-information/named-information.xhtml#hash-alg)
 *   or `null` if this algorithm does not exists in this registry.
 * @param fullySpecified `true` if this completely specifies an algorithm, `false` otherwise. See
 *   [Fully-Specified Algorithms for JOSE and COSE draft](https://datatracker.ietf.org/doc/draft-ietf-jose-fully-specified-algorithms/)
 *   for more information on what constitutes a fully specified algorithm.
 * @param curve the [EcCurve] used in the algorithm or `null` if not applicable or not a fully specified algorithm.
 * @param hashAlgorithm the hash algorithm in the the algorithm or `null` if not applicable or not a fully specified algorithm.
 * @param isSigning `true` if the algorithm is for signing, `false` if not applicable or not a fully specified algorithm.
 * @param isKeyAgreement `true` if the algorithm is for key agreement, `false` if not applicable or not a fully specified algorithm.
 * @param description A human readable description of the algorithm.
 */
enum class Algorithm(
    val coseAlgorithmIdentifier: Int? = null,
    val joseAlgorithmIdentifier: String? = null,
    val hashAlgorithmName: String? = null,
    val fullySpecified: Boolean = false,
    val curve: EcCurve? = null,
    val hashAlgorithm: Algorithm? = null,
    val isSigning: Boolean = false,
    val isKeyAgreement: Boolean = false,
    val description: String,
) {
    /** Used to indicate the algorithm is unset.  */
    UNSET(description = "Unset"),

    /** The algorithm identifier for signatures using ECDSA with SHA-256  */
    ES256(coseAlgorithmIdentifier = -7,
        description = "ECDSA with SHA-256"),

    /** The algorithm identifier for signatures using ECDSA with SHA-384  */
    ES384(coseAlgorithmIdentifier = -35,
        description = "ECDSA with SHA-384"),

    /** The algorithm identifier for signatures using ECDSA with SHA-512  */
    ES512(coseAlgorithmIdentifier = -36,
        description = "ECDSA with SHA-512"),

    /** The algorithm identifier for signatures using EdDSA  */
    EDDSA(coseAlgorithmIdentifier = -8, joseAlgorithmIdentifier = "EdDSA",
        description = "EdDSA"),

    /** SHA-1 Hash (insecure, shouldn't be used) */
    INSECURE_SHA1(coseAlgorithmIdentifier = -14,
        description = "SHA-1 (Insecure)"),

    /** SHA-2 256-bit Hash */
    SHA256(coseAlgorithmIdentifier = -16, hashAlgorithmName = "sha-256",
        description = "SHA-2 (256 bit)"),

    /** SHA-2 384-bit Hash */
    SHA384(coseAlgorithmIdentifier = -43, hashAlgorithmName = "sha-384",
        description = "SHA-2 (384 bit)"),

    /** SHA-2 512-bit Hash */
    SHA512(coseAlgorithmIdentifier = -44, hashAlgorithmName = "sha-512",
        description = "SHA-2 (512 bit)"),

    /** HMAC w/ SHA-1 (insecure, shouldn't be used) */
    HMAC_INSECURE_SHA1(joseAlgorithmIdentifier = "HS1",  // Note: doesn't exist in COSE registry
        description = "HMAC with SHA-1"),

    /** HMAC w/ SHA-256 */
    HMAC_SHA256(coseAlgorithmIdentifier = 5, joseAlgorithmIdentifier = "HS256",
        description = "HMAC with SHA-256"),

    /** HMAC w/ SHA-384 */
    HMAC_SHA384(coseAlgorithmIdentifier = 6, joseAlgorithmIdentifier = "HS384",
        description = "HMAC with SHA-384"),

    /** HMAC w/ SHA-512 */
    HMAC_SHA512(coseAlgorithmIdentifier = 7, joseAlgorithmIdentifier = "HS512",
        description = "HMAC with SHA-512"),

    /** AES-GCM mode w/ 128-bit key, 128-bit tag */
    A128GCM(coseAlgorithmIdentifier = 1, joseAlgorithmIdentifier = "A128GCM",
        description = "AES-GCM mode w/ 128-bit key, 128-bit tag"),

    /** AES-GCM mode w/ 192-bit key, 128-bit tag */
    A192GCM(coseAlgorithmIdentifier = 2, joseAlgorithmIdentifier = "A192GCM",
        description = "AES-GCM mode w/ 192-bit key, 128-bit tag"),

    /** AES-GCM mode w/ 256-bit key, 128-bit tag */
    A256GCM(coseAlgorithmIdentifier = 3, joseAlgorithmIdentifier = "A256GCM",
        description = "AES-GCM mode w/ 256-bit key, 128-bit tag"),

    /** RSASSA-PKCS1-v1_5 using SHA-256 */
    RS256(coseAlgorithmIdentifier = -257, joseAlgorithmIdentifier = "RS256",
        description = "RSASSA-PKCS1-v1_5 using SHA-256"),

    /** RSASSA-PKCS1-v1_5 using SHA-384 */
    RS384(coseAlgorithmIdentifier = -258, joseAlgorithmIdentifier = "RS384",
        description = "RSASSA-PKCS1-v1_5 using SHA-384"),

    /** RSASSA-PKCS1-v1_5 using SHA-512 */
    RS512(coseAlgorithmIdentifier = -259, joseAlgorithmIdentifier = "RS512",
        description = "RSASSA-PKCS1-v1_5 using SHA-512"),

    // Fully-specified algorithms start here, see also
    //  https://datatracker.ietf.org/doc/draft-ietf-jose-fully-specified-algorithms/
    //

    /** ECDSA using P-256 curve and SHA-256 */
    ESP256(coseAlgorithmIdentifier = -9, joseAlgorithmIdentifier = "ES256", fullySpecified = true,
        description = "ECDSA using P-256 curve and SHA-256",
        curve = EcCurve.P256, hashAlgorithm = SHA256, isSigning = true),

    /** ECDSA using P-384 curve and SHA-384 */
    ESP384(coseAlgorithmIdentifier = -48, joseAlgorithmIdentifier = "ES384", fullySpecified = true,
        description = "ECDSA using P-384 curve and SHA-384",
        curve = EcCurve.P384, hashAlgorithm = SHA384, isSigning = true),

    /** ECDSA using P-521 curve and SHA-512 */
    ESP512(coseAlgorithmIdentifier = -49, joseAlgorithmIdentifier = "ES512", fullySpecified = true,
        description = "ECDSA using P-521 curve and SHA-512",
        curve = EcCurve.P521, hashAlgorithm = SHA512, isSigning = true),

    /** ECDSA using BrainpoolP256r1 curve and SHA-256 */
    ESB256(coseAlgorithmIdentifier = -261, joseAlgorithmIdentifier = "ESB256", fullySpecified = true,
        description = "ECDSA using BrainpoolP256r1 curve and SHA-256",
        curve = EcCurve.BRAINPOOLP256R1, hashAlgorithm = SHA256, isSigning = true),

    /** ECDSA using BrainpoolP320r1 curve and SHA-384 */
    ESB320(coseAlgorithmIdentifier = -262, joseAlgorithmIdentifier = "ESB320", fullySpecified = true,
        description = "ECDSA using BrainpoolP320r1 curve and SHA-384",
        curve = EcCurve.BRAINPOOLP320R1, hashAlgorithm = SHA384, isSigning = true),

    /** ECDSA using BrainpoolP384r1 curve and SHA-384 */
    ESB384(coseAlgorithmIdentifier = -263, joseAlgorithmIdentifier = "ESB384", fullySpecified = true,
        description = "ECDSA using BrainpoolP384r1 curve and SHA-384",
        curve = EcCurve.BRAINPOOLP384R1, hashAlgorithm = SHA384, isSigning = true),

    /** ECDSA using BrainpoolP512r1 curve and SHA-512 */
    ESB512(coseAlgorithmIdentifier = -264, joseAlgorithmIdentifier = "ESB512", fullySpecified = true,
        description = "ECDSA using BrainpoolP512r1 curve and SHA-512",
        curve = EcCurve.BRAINPOOLP512R1, hashAlgorithm = SHA512, isSigning = true),

    /** EdDSA using Ed25519 curve */
    ED25519(coseAlgorithmIdentifier = -50, joseAlgorithmIdentifier = "Ed25519", fullySpecified = true,
        description = "EdDSA using Ed25519 curve",
        curve = EcCurve.ED25519, isSigning = true),

    /** EdDSA using Ed448 curve */
    ED448(coseAlgorithmIdentifier = -51, joseAlgorithmIdentifier = "Ed448", fullySpecified = true,
        description = "EdDSA using Ed448 curve",
        curve = EcCurve.ED448, isSigning = true),

    /** ECDH using P-256 curve without KDF */
    ECDH_P256(fullySpecified = true,
        description = "ECDH using P-256 curve without KDF", curve = EcCurve.P256,
        isKeyAgreement = true),

    /** ECDH using P-384 curve without KDF */
    ECDH_P384(fullySpecified = true,
        description = "ECDH using P-384 curve without KDF", curve = EcCurve.P384,
        isKeyAgreement = true),

    /** ECDH using P-521 curve without KDF */
    ECDH_P521(fullySpecified = true,
        description = "ECDH using P-521 curve without KDF", curve = EcCurve.P521,
        isKeyAgreement = true),

    /** ECDH using BrainpoolP256r1 curve without KDF */
    ECDH_BRAINPOOLP256R1(fullySpecified = true,
        description = "ECDH using BrainpoolP256r1 curve without KDF", curve = EcCurve.BRAINPOOLP256R1,
        isKeyAgreement = true),

    /** ECDH using BrainpoolP320r1 curve without KDF */
    ECDH_BRAINPOOLP320R1(fullySpecified = true,
        description = "ECDH using BrainpoolP320r1 curve without KDF", curve = EcCurve.BRAINPOOLP320R1,
        isKeyAgreement = true),

    /** ECDH using BrainpoolP384r1 curve without KDF */
    ECDH_BRAINPOOLP384R1(fullySpecified = true,
        description = "ECDH using BrainpoolP384r1 curve without KDF", curve = EcCurve.BRAINPOOLP384R1,
        isKeyAgreement = true),

    /** ECDH using BrainpoolP512r1 curve without KDF */
    ECDH_BRAINPOOLP512R1(fullySpecified = true,
        description = "ECDH using BrainpoolP512r1 curve without KDF", curve = EcCurve.BRAINPOOLP512R1,
        isKeyAgreement = true),

    /** ECDH using X25519 curve without KDF */
    ECDH_X25519(fullySpecified = true,
        description = "ECDH using X25519 curve without KDF", curve = EcCurve.X25519,
        isKeyAgreement = true),

    /** ECDH using X448 curve without KDF */
    ECDH_X448(fullySpecified = true,
        description = "ECDH using X448 curve without KDF", curve = EcCurve.X448,
        isKeyAgreement = true),

    ANDROID_KEYSTORE_ATTEST_KEY(
        description = "Android Keystore Attest Key",
        fullySpecified = true
    )

    ;

    companion object {
        private val coseIdentifierToAlgorithm: Map<Int, Algorithm> by lazy {
            buildMap {
                enumEntries<Algorithm>().forEach {
                    if (it.coseAlgorithmIdentifier != null) {
                        put(it.coseAlgorithmIdentifier, it)
                    }
                }
            }
        }

        private val joseIdentifierToAlgorithm: Map<String, Algorithm> by lazy {
            buildMap {
                enumEntries<Algorithm>().forEach {
                    if (it.joseAlgorithmIdentifier != null) {
                        put(it.joseAlgorithmIdentifier, it)
                    }
                }
            }
        }

        private val hashIdentifierToAlgorithm: Map<String, Algorithm> by lazy {
            buildMap {
                enumEntries<Algorithm>().forEach {
                    if (it.hashAlgorithmName != null) {
                        put(it.hashAlgorithmName, it)
                    }
                }
            }
        }

        private val nameToAlgorithm: Map<String, Algorithm> by lazy {
            buildMap {
                enumEntries<Algorithm>().forEach {
                    put(it.name, it)
                }
            }
        }

        /**
         * Creates a [Algorithm] from a
         * [COSE algorithm identifier](https://www.iana.org/assignments/cose/cose.xhtml#algorithms).
         *
         * @param coseAlgorithmIdentifier the COSE identifier.
         * @throws IllegalArgumentException if the identifier isn't in the [Algorithm] enumeration.
         */
        fun fromCoseAlgorithmIdentifier(coseAlgorithmIdentifier: Int): Algorithm =
            coseIdentifierToAlgorithm.get(coseAlgorithmIdentifier)
                ?: throw IllegalArgumentException("No algorithm with COSE identifier $coseAlgorithmIdentifier")

        /**
         * Creates an [Algorithm] from a
         * [JOSE algorithm identifier](https://www.iana.org/assignments/jose/jose.xhtml#web-signature-encryption-algorithms).
         *
         * @param joseAlgorithmIdentifier the JOSE identifier.
         * @throws IllegalArgumentException if there is no corresponding value.
         */
        fun fromJoseAlgorithmIdentifier(joseAlgorithmIdentifier: String): Algorithm =
            joseIdentifierToAlgorithm.get(joseAlgorithmIdentifier)
                ?: throw IllegalArgumentException("No algorithm with JOSE identifier $joseAlgorithmIdentifier")

        /**
         * Creates an [Algorithm] from a hash algorithm identifier from the
         * [Named Information Hash Algorithm Registry](https://www.iana.org/assignments/named-information/named-information.xhtml#hash-alg).
         *
         * @param hashAlgorithmIdentifier the hash algorithm identifier.
         * @throws IllegalArgumentException if there is no corresponding value.
         */
        fun fromHashAlgorithmIdentifier(hashAlgorithmIdentifier: String): Algorithm =
            hashIdentifierToAlgorithm.get(hashAlgorithmIdentifier)
                ?: throw IllegalArgumentException("No hash algorithm for $hashAlgorithmIdentifier")

        /**
         * Creates an [Algorithm] from its name.
         *
         * @param name name of the algorithm.
         * @throws IllegalArgumentException if there is no corresponding value.
         */
        fun fromName(name: String): Algorithm {
            return nameToAlgorithm.get(name)
                ?: throw IllegalArgumentException("No algorithm for name $name")
        }
    }

}