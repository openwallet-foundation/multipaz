package org.multipaz.sdjwt

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.jwt.buildJwt
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Duration

private const val TAG = "SdJwt"

/**
 * A SD-JWT according to
 * [draft-ietf-oauth-selective-disclosure-jwt](https://datatracker.ietf.org/doc/draft-ietf-oauth-selective-disclosure-jwt/).
 *
 * When a [SdJwt] instance is initialized, cursory checks on the provided string with the compact serialization are
 * performed. Full verification of the SD-JWT can be performed using the [verify] method which also returns
 * the processed payload.
 *
 * For presentment, first use one of the [filter] methods to generate an SD-JWT with a reduced set of disclosures. If
 * the SD-JWT is not using key-binding (can be checked by see if [kbKey] is `null`), the resulting SD-JWT can be sent
 * to the verifier. Otherwise use one of the [present] methods to generate a [SdJwtKb] instance. This implementation
 * supports SD-JWTs with disclosures nested at any level.
 *
 * To create a SD-JWT, use [Companion.create]. This currently only supports creating SD-JWT with fully recursive
 * disclosures.
 *
 * This class is immutable.
 *
 * @param compactSerialization the compact serialization of the SD-JWT.
 * @throws IllegalArgumentException if the given compact serialization is malformed.
 */
class SdJwt(
    val compactSerialization: String
) {
    private lateinit var header: String
    private lateinit var body: String
    private lateinit var signature: String

    private lateinit var hashToDisclosureString: Map<String, String>

    /** The digest algorithm used. */
    lateinit var digestAlg: Algorithm

    /** The header of the issuer-signed JWT. */
    val jwtHeader: JsonObject by lazy {
        Json.decodeFromString(JsonObject.serializer(), header.fromBase64Url().decodeToString())
    }

    /** The body of the issuer-signed JWT. */
    lateinit var jwtBody: JsonObject

    /**
     * The certificate chain in the `x5c` header element of the issuer-signed JWT, if present.
     */
    val x5c: X509CertChain? by lazy {
        jwtHeader["x5c"]?.let { X509CertChain.fromX5c(it) }
    }

    /** The value of the `iss` claim in the issuer-signed JWT. */
    val issuer: String by lazy {
        jwtBody["iss"]!!.jsonPrimitive.content
    }

    /** The value of the `sub` claim in the issuer-signed JWT, if present. */
    val subject: String? by lazy {
        jwtBody["sub"]?.jsonPrimitive?.content
    }

    /** The value of the `vct` claim in the issuer-signed JWT, if present. */
    val credentialType: String? by lazy {
        jwtBody["vct"]?.jsonPrimitive?.content
    }

    /** The value of the `iat` claim in the issuer-signed JWT, if present. */
    val issuedAt: Instant? by lazy {
        jwtBody["iat"]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochSeconds(it, 0) }
    }

    /** The value of the `nbf` claim in the issuer-signed JWT, if present. */
    val validFrom: Instant? by lazy {
        jwtBody["nbf"]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochSeconds(it, 0) }
    }

    /** The value of the `exp` claim in the issuer-signed JWT, if present. */
    val validUntil: Instant? by lazy {
        jwtBody["exp"]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochSeconds(it, 0) }
    }

    /** The value of the `cnf` claim in the issuer-signed JWT, if present. */
    val kbKey: EcPublicKey? by lazy {
        jwtBody["cnf"]?.jsonObject["jwk"]?.jsonObject?.let { EcPublicKey.fromJwk(it) }
    }

    /**
     * The disclosures in the SD-JWT.
     *
     * Each string is a base64url-encoded of the JSON array as described in section 1.2
     * of the SD-JWT specification.
     */
    val disclosures: List<String> by lazy {
        hashToDisclosureString.values.toList()
    }

    init {
        if (!compactSerialization.endsWith('~')) {
            throw IllegalArgumentException("Given compact serialization doesn't end with ~")
        }

        val splits = compactSerialization.split("~")
        val jwtSplits = splits[0].split(".")
        if (jwtSplits.size != 3) {
            throw IllegalArgumentException("JWT in SD-JWT didn't consist of three parts: ${splits[0]}")
        }
        header = jwtSplits[0]
        body = jwtSplits[1]
        signature = jwtSplits[2]

        jwtBody = Json.decodeFromString(JsonObject.serializer(), body.fromBase64Url().decodeToString())
        digestAlg = jwtBody["_sd_alg"]?.let {
            Algorithm.fromHashAlgorithmIdentifier(it.jsonPrimitive.content)
        } ?: Algorithm.SHA256

        val htds = mutableMapOf<String, String>()
        for (n in IntRange(1, splits.size - 2)) {
            val disclosureString = splits[n]
            val hash = Crypto.digest(digestAlg, disclosureString.encodeToByteArray()).toBase64Url()
            htds.put(hash, disclosureString)
        }
        hashToDisclosureString = htds
    }

    /**
     * Verifies a SD-JWT according to Section 7.1 of the SD-JWT specification.
     *
     * @param issuerKey the issuer's key to use for verification.
     * @return the processed SD-JWT payload.
     * @throws SignatureVerificationException if the issuer signature or key-binding signature failed to validate.
     */
    fun verify(
        issuerKey: EcPublicKey,
    ): JsonObject {
        // TODO: make sure we perform all checks in Section 7.1
        try {
            JsonWebSignature.verify("$header.$body.$signature", issuerKey)
        } catch (e: Throwable) {
            throw SignatureVerificationException("Error validating issuer signature", e)
        }
        return processObject(
            obj = jwtBody,
            hashToDisclosureString = hashToDisclosureString,
            path = mutableListOf(),
            visitor = { path, value, disclosure -> }
        )
    }

    /**
     * Generates a new SD-JWT by filtering which claims should be included,
     *
     * The resulting SD-JWT will be constructed so it satisfies the requirement in section 7.2
     * which says that each disclosure's hash is either contained in the Issuer-signed JWT claims
     * or in the claim value of another disclosure. Concretely this may mean more disclosures
     * are included than requested via the [pathsToInclude] function.
     *
     * @param pathsToInclude list of paths describing which claims to include.
     * @return the resulting [SdJwt].
     */
    fun filter(
        pathsToInclude: List<JsonArray>
    ): SdJwt {
        val pathToIncludeStrings = pathsToInclude.map { it.joinToString(".") }

        return filter { path: JsonArray, value: JsonElement ->
            val pathOfDisclosure = path.toList().joinToString(".")
            for (pathToIncludeString in pathToIncludeStrings) {
                if (pathOfDisclosure.startsWith(pathToIncludeString)) {
                    return@filter true
                }
            }
            false
        }
    }

    /**
     * Generates a new SD-JWT by removing disclosures.
     *
     * The resulting SD-JWT will be constructed so it satisfies the requirement in section 7.2
     * which says that each disclosure's hash is either contained in the Issuer-signed JWT claims
     * or in the claim value of another disclosure. Concretely this may mean more disclosures
     * are included than requested via the [includeDisclosure] function.
     *
     * For example for fully recursive SD-JWT with the following claims
     * ```
     * {
     *   "age_over_or_equal": {
     *     "18": true,
     *     "21": false
     *  }
     *  ```
     * the hash for the disclosure of the `age_over_or_equal.18` is not included in the Issuer-signed
     * JWT claims, instead it's in the disclosure for the `age_over_or_equal` value.
     *
     * @param includeDisclosure a function to determine if a given disclosure should be included.
     * @return the resulting [SdJwt].
     */
    fun filter(
        includeDisclosure: (path: JsonArray, value: JsonElement) -> Boolean
    ): SdJwt {
        val disclosureHashIncludedInDisclosureString = mutableMapOf<String, String>()

        // Build up list of disclosures to keep, do it this way to preserve the order...
        //
        // At the same time build up `disclosureHashIncludedInDisclosureString` which
        // is used to top-off disclosureStringsToInclude with missing disclosures below.
        //
        val disclosureStringsToSkip = mutableSetOf<String>()
        processObject(
            obj = jwtBody,
            hashToDisclosureString = hashToDisclosureString,
            path = mutableListOf(),
            visitor = { path, value, disclosureString ->
                if (disclosureString != null) {
                    val include = includeDisclosure(path, value)
                    if (!include) {
                        disclosureStringsToSkip.add(disclosureString)
                    }

                    val disclosure = Json.decodeFromString(
                        JsonArray.serializer(),
                        disclosureString.fromBase64Url().decodeToString()
                    ).jsonArray
                    val value = disclosure[disclosure.size - 1]
                    if (value is JsonObject && value["_sd"] != null) {
                        for (hash in value["_sd"]!!.jsonArray.map { it.jsonPrimitive.content }) {
                            disclosureHashIncludedInDisclosureString[hash] = disclosureString
                        }
                    }
                }
            }
        )
        val disclosureStringsToInclude = disclosures.filter { !disclosureStringsToSkip.contains(it) }.toMutableList()

        // It's possible that the user selected disclosures that aren't referenced in the top-level "_sd"
        // array of hashes. Check this by going through each disclosure and top off as needed.
        var restartTopOff = false
        do {
            restartTopOff = false
            for (disclosureString in disclosureStringsToInclude) {
                val hash = Crypto.digest(digestAlg, disclosureString.encodeToByteArray()).toBase64Url()
                val disclosureIncludingThisHash = disclosureHashIncludedInDisclosureString[hash]
                if (disclosureIncludingThisHash != null &&
                    !disclosureStringsToInclude.contains(disclosureIncludingThisHash)
                ) {
                    disclosureStringsToInclude.add(disclosureIncludingThisHash)
                    restartTopOff = true
                    break
                }
            }
        } while (restartTopOff)

        val sb = StringBuilder("$header.$body.$signature~")
        disclosureStringsToInclude.forEach { sb.append("$it~") }
        return SdJwt(sb.toString())
    }

    /**
     * Presents an SD-JWT to a verifier, using SD-JWT's associated signing key.
     *
     * This generates a SD-JWT+KB from the SD-JWT by simply appending a Key-Binding JWT.
     *
     * @param signingKey private key associated with this SD-JWT.
     * @param nonce the nonce, obtained from the verifier.
     * @param audience the audience, obtained from the verifier.
     * @param creationTime the time the presentation was made.
     */
    suspend fun present(
        signingKey: AsymmetricKey,
        nonce: String,
        audience: String,
        creationTime: Instant = Clock.System.now()
    ): SdJwtKb {
        require(signingKey.publicKey == this.kbKey) {
            "Public part of signing key does not match key in `cnf` claim"
        }
        val kbJwt = buildJwt(
            type = "kb+jwt",
            key = signingKey,
            creationTime = creationTime
        ) {
            put("nonce", nonce)
            put("aud", audience)
            put("sd_hash", Crypto.digest(digestAlg, compactSerialization.encodeToByteArray()).toBase64Url())
        }
        return SdJwtKb(compactSerialization + kbJwt)
    }

    companion object {
        // From SD-JWT spec 9.7.  Selectively-Disclosable Validity Claims
        private val CLAIMS_THAT_CANNOT_BE_DISCLOSED = setOf("iss", "exp", "nbf", "cnf", "aud")

        /**
         * Creates a SD-JWT.
         *
         * This implementation uses recursive disclosures for all claims in the [claims] parameter.
         *
         * @param issuerKey the key to sign the issuerSigned JWT with.
         * @param kbKey if set, a `cnf` claim with this public key will be included in the Issuer-signed JWT.
         * @param claims the object with claims that can be selectively disclosed.
         * @param nonSdClaims claims to include in the Issuer-signed JWT which are always disclosed. This must at least
         *   include the `iss` claim and may include more such as `vct`, `sub`, `iat`, `nbf`, `exp`.
         * @param digestAlgorithm the hash algorithm to use, e.g. [Algorithm.SHA256].
         * @param random the [Random] to use to generate salts.
         * @param saltSizeNumBits number of bits to use for each salt.
         */
        suspend fun create(
            issuerKey: AsymmetricKey,
            kbKey: EcPublicKey?,
            claims: JsonObject,
            nonSdClaims: JsonObject,
            digestAlgorithm: Algorithm = Algorithm.SHA256,
            random: Random = Random.Default,
            saltSizeNumBits: Int = 128,
            creationTime: Instant = Instant.DISTANT_PAST,  // TODO: switch to System.Clock.now()?
            expiresIn: Duration? = null
        ): SdJwt {
            require(nonSdClaims["iss"] != null) { "Must include `iss` claim in nonSdClaims" }

            // TODO: add support for decoy digests.

            val disclosures = mutableListOf<JsonArray>()

            val jwt = buildJwt(
                type = "dc+sd-jwt",
                key = issuerKey,
                creationTime = creationTime,
                expiresIn = expiresIn
            ) {
                for (claim in nonSdClaims) {
                    put(claim.key, claim.value)
                }

                val hashes = mutableListOf<String>()
                for (claim in claims) {
                    if (CLAIMS_THAT_CANNOT_BE_DISCLOSED.contains(claim.key)) {
                        throw IllegalArgumentException("Claim ${claim.key} cannot be disclosed")
                    }
                    insertClaim(
                        disclosures = disclosures,
                        hashes = hashes,
                        random = random,
                        saltSizeNumBits = saltSizeNumBits,
                        digestAlg = digestAlgorithm,
                        claimName = claim.key,
                        claimValue = claim.value
                    )
                }
                putJsonArray("_sd") {
                    for (hash in hashes) {
                        add(JsonPrimitive(hash))
                    }
                }

                put("_sd_alg", JsonPrimitive(digestAlgorithm.hashAlgorithmName))

                if (kbKey != null) {
                    putJsonObject("cnf") {
                        put("jwk", kbKey.toJwk())
                    }
                }
            }
            val sb = StringBuilder()
            sb.append(jwt)
            sb.append('~')
            for (disclosure in disclosures) {
                sb.append(disclosure.toString().encodeToByteArray().toBase64Url())
                sb.append('~')
            }
            return SdJwt(sb.toString())
        }


    }
}

private fun insertClaim(
    disclosures: MutableList<JsonArray>,
    hashes: MutableList<String>,
    random: Random,
    saltSizeNumBits: Int,
    digestAlg: Algorithm,
    claimName: String?,
    claimValue: JsonElement
) {
    if (claimValue is JsonPrimitive) {
        val disclosure = buildJsonArray {
            add(JsonPrimitive(random.getRandomSalt(saltSizeNumBits)))
            claimName?.let { add(JsonPrimitive(it)) }
            add(claimValue)
        }
        val disclosureString = disclosure.toString().encodeToByteArray().toBase64Url()
        val hash = Crypto.digest(digestAlg, disclosureString.encodeToByteArray()).toBase64Url()
        disclosures.add(disclosure)
        hashes.add(hash)
    } else if (claimValue is JsonObject) {
        val subClaimHashes = mutableListOf<String>()
        for ((subClaimName, subClaimValue) in claimValue.entries) {
            insertClaim(
                disclosures = disclosures,
                hashes = subClaimHashes,
                random = random,
                saltSizeNumBits = saltSizeNumBits,
                digestAlg = digestAlg,
                claimName = subClaimName,
                claimValue = subClaimValue
            )
        }
        val mappedClaimValue = buildJsonObject {
            putJsonArray("_sd") {
                subClaimHashes.forEach { add(JsonPrimitive(it)) }
            }
        }
        val disclosure = buildJsonArray {
            add(JsonPrimitive(random.getRandomSalt(saltSizeNumBits)))
            claimName?.let { add(JsonPrimitive(it)) }
            add(mappedClaimValue)
        }
        val disclosureString = disclosure.toString().encodeToByteArray().toBase64Url()
        val hash = Crypto.digest(digestAlg, disclosureString.encodeToByteArray()).toBase64Url()
        disclosures.add(disclosure)
        hashes.add(hash)
    } else if (claimValue is JsonArray) {
        val mappedClaimValue = buildJsonArray {
            for (arrayElemValue in claimValue) {
                val subClaimHashes = mutableListOf<String>()
                insertClaim(
                    disclosures = disclosures,
                    hashes = subClaimHashes,
                    random = random,
                    saltSizeNumBits = saltSizeNumBits,
                    digestAlg = digestAlg,
                    claimName = null,
                    claimValue = arrayElemValue
                )
                addJsonObject {
                    put("...", JsonPrimitive(subClaimHashes[0]))
                }
            }
        }
        val disclosure = buildJsonArray {
            add(JsonPrimitive(random.getRandomSalt(saltSizeNumBits)))
            claimName?.let { add(JsonPrimitive(it)) }
            add(mappedClaimValue)
        }
        val disclosureString = disclosure.toString().encodeToByteArray().toBase64Url()
        val hash = Crypto.digest(digestAlg, disclosureString.encodeToByteArray()).toBase64Url()
        disclosures.add(disclosure)
        hashes.add(hash)
    }
}

private fun Random.getRandomSalt(
    saltSizeNumBits: Int,
): String {
    val bytes = ByteArray(saltSizeNumBits/8)
    this.nextBytes(bytes)
    return bytes.toBase64Url()
}

private fun process(
    elem: JsonElement,
    hashToDisclosureString: Map<String, String>,
    path: List<JsonElement>,
    visitor: (path: JsonArray, value: JsonElement, disclosureString: String?) -> Unit,
): JsonElement {
    val processedElem = if (elem is JsonObject) {
        processObject(elem, hashToDisclosureString, path, visitor)
    } else if (elem is JsonArray) {
        processArray(elem, hashToDisclosureString, path, visitor)
    } else {
        elem
    }
    return processedElem
}

private fun processObject(
    obj: JsonObject,
    hashToDisclosureString: Map<String, String>,
    path: List<JsonElement>,
    visitor: (path: JsonArray, value: JsonElement, disclosureString: String?) -> Unit,
): JsonObject {
    return buildJsonObject {
        for (claimName in obj.keys) {
            if (claimName == "_sd" || claimName == "_sd_alg") {
                // skip
            } else {
                val claimValue = obj[claimName]!!
                val subPath = path + JsonPrimitive(claimName)
                visitor(JsonArray(subPath), claimValue, null)
                put(claimName, process(claimValue, hashToDisclosureString, subPath, visitor))
            }
        }

        val embeddedDigests = mutableListOf<String>()
        val _sd = obj["_sd"]
        if (_sd != null) {
            for (digest in _sd.jsonArray) {
                embeddedDigests.add(digest.jsonPrimitive.content)
            }
        }
        for (ed in embeddedDigests) {
            val disclosureString = hashToDisclosureString[ed]
            if (disclosureString != null) {
                val disclosure = Json.decodeFromString(
                    JsonArray.serializer(),
                    disclosureString.fromBase64Url().decodeToString()
                ).jsonArray
                val claimName = disclosure[1].jsonPrimitive.content
                val claimValue = disclosure[2]
                if (claimName == "_sd" || claimName == "...") {
                    throw IllegalArgumentException("Illegal disclosure claim name")
                }
                if (obj.get(claimName) != null) {
                    throw IllegalArgumentException("Claim $claimName already exists")
                }
                val subPath = path + JsonPrimitive(claimName)
                visitor(JsonArray(subPath), claimValue, disclosureString)
                put(claimName, process(claimValue, hashToDisclosureString, subPath, visitor))
            }
        }
    }
}

private fun processArray(
    array: JsonArray,
    hashToDisclosureString: Map<String, String>,
    path: List<JsonElement>,
    visitor: (path: JsonArray, value: JsonElement, disclosureString: String?) -> Unit,
): JsonArray {
    return buildJsonArray {
        for (n in IntRange(0, array.size - 1)) {
            var claimValue = array[n]
            if (claimValue is JsonObject && claimValue.keys.size == 1 && claimValue.keys.first() == "...") {
                val digest = claimValue["..."]!!.jsonPrimitive.content
                val disclosureString = hashToDisclosureString[digest]
                if (disclosureString != null) {
                    val disclosure = Json.decodeFromString(
                        JsonArray.serializer(),
                        disclosureString.fromBase64Url().decodeToString()
                    ).jsonArray
                    claimValue = disclosure[1]
                    val subPath = path + JsonPrimitive(n)
                    visitor(JsonArray(subPath), claimValue, disclosureString)
                    add(process(claimValue, hashToDisclosureString, subPath, visitor))
                }
            } else {
                val subPath = path + JsonPrimitive(n)
                visitor(JsonArray(subPath), claimValue, null)
                add(process(claimValue, hashToDisclosureString, subPath, visitor))
            }
        }
    }
}
