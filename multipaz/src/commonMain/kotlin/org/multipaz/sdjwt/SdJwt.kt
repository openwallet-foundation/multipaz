package org.multipaz.sdjwt

import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
import org.multipaz.crypto.PublicKey
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.revocation.RevocationStatus
import org.multipaz.validation.ValidationResult
import org.multipaz.validation.ValidationResultBuilder
import org.multipaz.validation.buildValidationResult
import org.multipaz.sdjwt.DisclosureMetadata.Companion.isClaimSelectivelyDisclosable
import org.multipaz.sdjwt.DisclosureMetadata.Companion.isIndexSelectivelyDisclosable
import org.multipaz.sdjwt.DisclosureMetadata.Companion.toDisclosureMetadata
import org.multipaz.sdjwt.DisclosureUtil.putClaimDisclosureDigests
import org.multipaz.sdjwt.DisclosureUtil.toArrayDigestElement
import org.multipaz.sdjwt.DisclosureUtil.toArrayDisclosure
import org.multipaz.sdjwt.DisclosureUtil.toClaimDisclosure
import org.multipaz.webtoken.buildJwt
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.collections.iterator
import kotlin.random.Random
import kotlin.time.Duration

private const val TAG = "SdJwt"

/**
 * A SD-JWT according to [RFC 9901](https://datatracker.ietf.org/doc/rfc9901/).
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
 * To create a SD-JWT, use [Companion.fromCompactSerialization] or [Companion.create]. This currently only supports
 * creating SD-JWT with fully recursive disclosures.
 *
 * This class is immutable.
 *
 * @property compactSerialization the compact serialization of the SD-JWT.
 * @property digestAlg the digest algorithm used.
 * @property jwtBody The body of the issuer-signed JWT.
 * @throws IllegalArgumentException if the given compact serialization is malformed.
 */
class SdJwt private constructor(
    val compactSerialization: String,
    val digestAlg: Algorithm,
    val jwtBody: JsonObject,
    private val header: String,
    private val body: String,
    private val signature: String,
    private val hashToDisclosureString: Map<String, String>
) {
    /** The header of the issuer-signed JWT. */
    val jwtHeader: JsonObject by lazy {
        Json.decodeFromString(JsonObject.serializer(), header.fromBase64Url().decodeToString())
    }

    /**
     * The certificate chain in the `x5c` header element of the issuer-signed JWT, if present.
     */
    val x5c: X509CertChain? by lazy {
        jwtHeader["x5c"]?.let { X509CertChain.fromX5c(it) }
    }

    /** The value of the `iss` or `issuer` claim in the issuer-signed JWT, if present. */
    val issuer: String? by lazy {
        (jwtBody["iss"] ?: jwtBody["issuer"])?.jsonPrimitive?.content
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
    val kbKey: PublicKey? by lazy {
        jwtBody["cnf"]?.jsonObject["jwk"]?.jsonObject?.let { PublicKey.fromJwk(it) }
    }

    val revocationStatus: RevocationStatus? by lazy {
        jwtBody["status"]?.let { RevocationStatus.fromJson(it) }
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

    /**
     * Verifies a SD-JWT according to Section 7.1 of the SD-JWT specification.
     *
     * @param issuerKey the issuer's key to use for verification or `null` to not perform issuer signature validation.
     * @return the processed SD-JWT payload.
     * @throws SignatureVerificationException if the issuer signature or key-binding signature failed to validate.
     */
    suspend fun verify(
        issuerKey: PublicKey? = null,
    ): JsonObject {
        // TODO: make sure we perform all checks in Section 7.1
        if (issuerKey != null) {
            try {
                JsonWebSignature.verify("$header.$body.$signature", issuerKey)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw SignatureVerificationException("Error validating issuer signature", e)
            }
        }
        return processObject(
            obj = jwtBody,
            hashToDisclosureString = hashToDisclosureString,
            path = mutableListOf(),
            visitor = { path, value, disclosure -> }
        )
    }

    /**
     * Validates this [SdJwt] according to RFC 9901.
     *
     * @param now reference time to check expiration and validity windows, or `null`.
     * @return a [ValidationResult] containing any errors or warnings.
     */
    suspend fun validate(now: Instant? = null): ValidationResult = buildValidationResult {
        // 1. Header validation
        val typ = jwtHeader["typ"]?.jsonPrimitive?.content
        val recognizedTypes = listOf(
            "sd-jwt",
            "application/sd-jwt",
            "dc+sd-jwt",
            "vc+sd-jwt",
            "application/dc+sd-jwt",
            "application/vc+sd-jwt"
        )
        if (typ == null) {
            addWarning("SD-JWT header is missing 'typ' parameter")
        } else if (typ !in recognizedTypes) {
            addWarning("SD-JWT header has unrecognized 'typ': '$typ'")
        }

        val algStr = jwtHeader["alg"]?.jsonPrimitive?.content
        if (algStr == null) {
            addError("SD-JWT header is missing 'alg' parameter")
        } else {
            val alg = try {
                Algorithm.fromJoseAlgorithmIdentifier(algStr)
            } catch (_: Throwable) {
                null
            }
            if (alg == null) {
                addError("Unsupported signing algorithm in SD-JWT header: '$algStr'")
            }
        }

        val certChain = x5c
        if (certChain == null) {
            addWarning("SD-JWT header does not contain 'x5c' certificate chain")
        } else {
            if (certChain.certificates.isEmpty()) {
                addError("SD-JWT 'x5c' certificate chain is empty")
            } else {
                val leafCert = certChain.certificates.first()
                if (leafCert.keyUsage.isNotEmpty()) {
                    if (!leafCert.keyUsage.contains(X509KeyUsage.DIGITAL_SIGNATURE)) {
                        addError("Issuer certificate in 'x5c' does not have DIGITAL_SIGNATURE key usage")
                    }
                    if (leafCert.keyUsage.contains(X509KeyUsage.KEY_CERT_SIGN)) {
                        addWarning("Issuer certificate in 'x5c' has KEY_CERT_SIGN key usage (issuer certificate should not be a CA)")
                    }
                }
                if (leafCert.basicConstraints?.first == true) {
                    addWarning("Issuer certificate in 'x5c' has Basic Constraints CA=true (issuer certificate should not be a CA)")
                }
                if (now != null) {
                    if (now < leafCert.validityNotBefore) {
                        addWarning("Issuer certificate is not yet valid (notBefore: ${leafCert.validityNotBefore}, current time: $now)")
                    }
                    if (now > leafCert.validityNotAfter) {
                        addWarning("Issuer certificate is expired (notAfter: ${leafCert.validityNotAfter}, current time: $now)")
                    }
                }
                try {
                    JsonWebSignature.verify("$header.$body.$signature", leafCert.publicKey)
                } catch (e: SignatureVerificationException) {
                    addError("SD-JWT issuer signature verification failed: ${e.message}")
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    addError("Error verifying SD-JWT issuer signature: ${e.message}")
                }
            }
        }

        // 2. Payload validation
        val sdAlgStr = jwtBody["_sd_alg"]?.jsonPrimitive?.content
        if (sdAlgStr != null) {
            val supported = listOf("sha-256", "sha-384", "sha-512")
            if (sdAlgStr.lowercase() !in supported) {
                addError("Unsupported digest algorithm in '_sd_alg': '$sdAlgStr'")
            }
        }

        val iss = issuer
        if (iss.isNullOrEmpty()) {
            addError("SD-JWT missing 'iss' claim")
        }

        if (validFrom != null && validUntil != null && validUntil!! <= validFrom!!) {
            addError("SD-JWT 'exp' ($validUntil) must be later than 'nbf' ($validFrom)")
        }
        if (issuedAt != null && validUntil != null && validUntil!! <= issuedAt!!) {
            addError("SD-JWT 'exp' ($validUntil) must be later than 'iat' ($issuedAt)")
        }
        if (issuedAt != null && validFrom != null && validFrom!! < issuedAt!!) {
            addWarning("SD-JWT 'nbf' ($validFrom) is earlier than 'iat' ($issuedAt)")
        }
        if (now != null) {
            if (validFrom != null && now < validFrom!!) {
                addWarning("SD-JWT is not yet valid ('nbf': $validFrom, current time: $now)")
            }
            if (validUntil != null && now > validUntil!!) {
                addError("SD-JWT is expired ('exp': $validUntil, current time: $now)")
            }
        }
        if (certChain != null && certChain.certificates.isNotEmpty()) {
            val leafCert = certChain.certificates.first()
            val referenceTimestamp = issuedAt ?: validFrom
            if (referenceTimestamp != null) {
                if (referenceTimestamp < leafCert.validityNotBefore) {
                    addError("SD-JWT issuance/validity ($referenceTimestamp) is before issuer certificate validity period (notBefore: ${leafCert.validityNotBefore})")
                }
                if (referenceTimestamp > leafCert.validityNotAfter) {
                    addError("SD-JWT issuance/validity ($referenceTimestamp) is after issuer certificate validity period (notAfter: ${leafCert.validityNotAfter})")
                }
            }
            if (validUntil != null && validUntil!! > leafCert.validityNotAfter) {
                addWarning("SD-JWT 'exp' ($validUntil) is after issuer certificate validity period (notAfter: ${leafCert.validityNotAfter})")
            }
        }

        if (jwtBody.containsKey("cnf")) {
            val cnfObj = jwtBody["cnf"]
            if (cnfObj !is JsonObject) {
                addError("SD-JWT 'cnf' claim is not a JSON object")
            } else if (!cnfObj.containsKey("jwk")) {
                addWarning("SD-JWT 'cnf' claim does not contain 'jwk'")
            } else {
                try {
                    kbKey
                } catch (e: Throwable) {
                    addError("Failed to parse public key from 'cnf.jwk': ${e.message}")
                }
            }
        }

        // 3. Disclosures and tree validation
        val parsedDisclosures = mutableMapOf<String, Disclosure>()
        val seenHashes = mutableSetOf<String>()
        for (discStr in disclosures) {
            val disc = try {
                Disclosure.fromDisclosureString(discStr)
            } catch (e: Throwable) {
                addError("Failed to parse disclosure: ${e.message}")
                null
            }
            if (disc != null) {
                addAll(disc.validate())
                val hash = disc.calculateDigest(digestAlg)
                if (!seenHashes.add(hash)) {
                    addWarning("Duplicate disclosure with digest '$hash' in SD-JWT")
                }
                parsedDisclosures[hash] = disc
            }
        }

        val referencedDigests = mutableSetOf<String>()
        val visitedDigests = mutableSetOf<String>()
        validateDigestTree(this, jwtBody, referencedDigests, visitedDigests, parsedDisclosures)

        for (refDigest in referencedDigests) {
            if (!hashToDisclosureString.containsKey(refDigest)) {
                addError("Digest '$refDigest' referenced in claims tree is missing from SD-JWT disclosures")
            }
        }

        for ((hash, disc) in parsedDisclosures) {
            if (hash !in referencedDigests) {
                val label = disc.claimName ?: "<array-element>"
                addWarning("Orphaned disclosure '$label' (digest $hash) is not referenced in the SD-JWT claims tree")
            }
        }
    }

    /**
     * Validates this [SdJwt] according to the SD-JWT VC specification.
     *
     * This runs all RFC 9901 checks via [validate] and additionally enforces SD-JWT VC profile requirements:
     * - The header 'typ' must be a recognized VC type ('vc+sd-jwt', 'dc+sd-jwt', or mime-type equivalent).
     * - The 'vct' claim must be present (and match [expectedVct] if provided).
     * - Disclosures must not selectively disclose 'vct' or 'status'.
     * - The 'status' claim (if present) must be a valid status list claim.
     *
     * @param expectedVct the expected Verifiable Credential Type, or `null` to only check that 'vct' is present.
     * @param now reference time to check expiration and validity windows, or `null`.
     * @return a [ValidationResult] containing any errors or warnings.
     */
    suspend fun validateVc(
        expectedVct: String? = null,
        now: Instant? = null
    ): ValidationResult = buildValidationResult {
        addAll(validate(now))

        val typ = jwtHeader["typ"]?.jsonPrimitive?.content
        val vcTypes = listOf("dc+sd-jwt", "vc+sd-jwt", "application/dc+sd-jwt", "application/vc+sd-jwt")
        if (typ == null) {
            addError("SD-JWT VC header is missing 'typ' parameter (expected 'vc+sd-jwt' or 'dc+sd-jwt')")
        } else if (typ !in vcTypes) {
            addError("SD-JWT VC header 'typ' must be one of ${vcTypes.joinToString(", ")} (found '$typ')")
        }

        val vctClaim = credentialType
        if (vctClaim.isNullOrEmpty()) {
            addError("SD-JWT VC missing 'vct' claim")
        } else if (expectedVct != null && vctClaim != expectedVct) {
            addError("SD-JWT VC 'vct' claim '$vctClaim' does not match expected '$expectedVct'")
        }

        for (discStr in disclosures) {
            val disc = try {
                Disclosure.fromDisclosureString(discStr)
            } catch (_: Throwable) {
                null
            }
            if (disc != null && !disc.isArrayElement) {
                if (disc.claimName in setOf("vct", "status")) {
                    addError("Claim '${disc.claimName}' cannot be selectively disclosed in an SD-JWT VC")
                }
            }
        }

        if (jwtBody.containsKey("status")) {
            val statusObj = jwtBody["status"]
            if (statusObj !is JsonObject) {
                addError("SD-JWT VC 'status' claim is not a JSON object")
            } else {
                try {
                    revocationStatus
                } catch (e: Throwable) {
                    addError("Failed to parse 'status' claim: ${e.message}")
                }
            }
        }
    }

    private fun validateDigestTree(
        builder: ValidationResultBuilder,
        obj: JsonObject,
        referencedDigests: MutableSet<String>,
        visitedDigests: MutableSet<String>,
        hashToDisclosure: Map<String, Disclosure>
    ) {
        val directSd = obj["_sd"]
        val directDigests = mutableListOf<String>()
        if (directSd is JsonArray) {
            for (digestElem in directSd) {
                if (digestElem is JsonPrimitive && digestElem.isString) {
                    val digest = digestElem.content
                    directDigests.add(digest)
                    referencedDigests.add(digest)
                } else {
                    builder.addError("Element in '_sd' array is not a string primitive: $digestElem")
                }
            }
        }

        for (digest in directDigests) {
            if (!visitedDigests.add(digest)) {
                builder.addError("Circular disclosure reference detected for digest '$digest'")
                continue
            }
            val disclosure = hashToDisclosure[digest]
            if (disclosure != null) {
                if (disclosure.isArrayElement) {
                    builder.addError("Array element disclosure (digest $digest) is referenced in an object '_sd' array")
                } else {
                    val claimName = disclosure.claimName!!
                    if (obj.containsKey(claimName)) {
                        builder.addError("Disclosed claim '$claimName' collides with existing claim in the containing object (RFC 9901 Section 5.1)")
                    }
                    if (disclosure.claimValue is JsonObject) {
                        validateDigestTree(builder, disclosure.claimValue, referencedDigests, visitedDigests, hashToDisclosure)
                    } else if (disclosure.claimValue is JsonArray) {
                        validateArrayDigestTree(builder, disclosure.claimValue, referencedDigests, visitedDigests, hashToDisclosure)
                    }
                }
            }
        }

        for ((key, value) in obj) {
            if (key != "_sd" && key != "_sd_alg") {
                if (value is JsonObject) {
                    validateDigestTree(builder, value, referencedDigests, visitedDigests, hashToDisclosure)
                } else if (value is JsonArray) {
                    validateArrayDigestTree(builder, value, referencedDigests, visitedDigests, hashToDisclosure)
                }
            }
        }
    }

    private fun validateArrayDigestTree(
        builder: ValidationResultBuilder,
        array: JsonArray,
        referencedDigests: MutableSet<String>,
        visitedDigests: MutableSet<String>,
        hashToDisclosure: Map<String, Disclosure>
    ) {
        for (elem in array) {
            if (elem is JsonObject && elem.size == 1 && elem.containsKey("...")) {
                val digestElem = elem["..."]
                if (digestElem is JsonPrimitive && digestElem.isString) {
                    val digest = digestElem.content
                    referencedDigests.add(digest)
                    if (!visitedDigests.add(digest)) {
                        builder.addError("Circular disclosure reference detected for digest '$digest'")
                        continue
                    }
                    val disclosure = hashToDisclosure[digest]
                    if (disclosure != null) {
                        if (!disclosure.isArrayElement) {
                            builder.addError("Object property disclosure '${disclosure.claimName}' (digest $digest) is referenced in an array '...' element")
                        }
                        if (disclosure.claimValue is JsonObject) {
                            validateDigestTree(builder, disclosure.claimValue, referencedDigests, visitedDigests, hashToDisclosure)
                        } else if (disclosure.claimValue is JsonArray) {
                            validateArrayDigestTree(builder, disclosure.claimValue, referencedDigests, visitedDigests, hashToDisclosure)
                        }
                    }
                } else {
                    builder.addError("Array digest element '...' is not a string primitive: $elem")
                }
            } else if (elem is JsonObject) {
                validateDigestTree(builder, elem, referencedDigests, visitedDigests, hashToDisclosure)
            } else if (elem is JsonArray) {
                validateArrayDigestTree(builder, elem, referencedDigests, visitedDigests, hashToDisclosure)
            }
        }
    }

    /**
     * Checks if a disclosure path matches a requested path according to OpenID4VP section 7.1.
     *
     * The matching logic compares components at each index:
     * - A `null` value (JsonNull) in the requested path matches any non-negative integer array index in the disclosure path.
     * - String keys or specific array indices match if their string values are equal.
     *
     * In addition, the two paths match if they are equal or if one is a prefix of the other (so that
     * ancestors and descendants are matched, satisfying the RFC 9901 section 7.2 requirement that parent
     * disclosures are preserved to allow traversing to nested children).
     *
     * @param path the disclosure's component path.
     * @param pathToInclude the requested component path.
     * @return `true` if the disclosure path matches the requested path, `false` otherwise.
     */
    private fun pathMatches(path: JsonArray, pathToInclude: JsonArray): Boolean {
        val minLen = minOf(path.size, pathToInclude.size)
        for (i in 0 until minLen) {
            val Cd = path[i]
            val Cr = pathToInclude[i]
            if (Cr is JsonNull) {
                // null matches all elements of array(s) (non-negative integer indices)
                if (Cd !is JsonPrimitive || Cd.isString || Cd.content.toIntOrNull()?.let { it >= 0 } != true) {
                    return false
                }
            } else if (Cr is JsonPrimitive && Cd is JsonPrimitive) {
                if (Cr.content != Cd.content) {
                    return false
                }
            } else {
                return false
            }
        }
        return true
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
    suspend fun filter(
        pathsToInclude: List<JsonArray>
    ): SdJwt {
        return filter { path: JsonArray, value: JsonElement ->
            for (pathToInclude in pathsToInclude) {
                if (pathMatches(path, pathToInclude)) {
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
     * This implementation follows the rules for selection in OpenID4VP section 7.1.
     *
     * @param includeDisclosure a function to determine if a given disclosure should be included.
     * @return the resulting [SdJwt].
     */
    suspend fun filter(
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
        return fromCompactSerialization(sb.toString())
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
     * @param additionalClaimBuilderAction builder block to add extra claims into SD-JWT+KB body
     */
    suspend fun present(
        signingKey: AsymmetricKey,
        nonce: String,
        audience: String,
        creationTime: Instant = Clock.System.now(),
        additionalClaimBuilderAction: JsonObjectBuilder.() -> Unit = {}
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
            additionalClaimBuilderAction.invoke(this)
        }
        return SdJwtKb.fromCompactSerialization(compactSerialization + kbJwt)
    }

    companion object {
        // From SD-JWT spec 9.7.  Selectively-Disclosable Validity Claims
        private val CLAIMS_THAT_CANNOT_BE_DISCLOSED = setOf("iss", "exp", "nbf", "cnf", "aud")

        /**
         * Creates a [SdJwt] from compact serialization.
         *
         * @param compactSerialization the compact serialization of the SD-JWT.
         * @return a [SdJwt] instance.
         */
        suspend fun fromCompactSerialization(
            compactSerialization: String,
        ): SdJwt {
            if (!compactSerialization.endsWith('~')) {
                throw IllegalArgumentException("Given compact serialization doesn't end with ~")
            }

            val splits = compactSerialization.split("~")
            val jwtSplits = splits[0].split(".")
            if (jwtSplits.size != 3) {
                throw IllegalArgumentException("JWT in SD-JWT didn't consist of three parts: ${splits[0]}")
            }
            val header = jwtSplits[0]
            val body = jwtSplits[1]
            val signature = jwtSplits[2]

            val jwtBody = Json.decodeFromString(JsonObject.serializer(), body.fromBase64Url().decodeToString())
            val digestAlg = jwtBody["_sd_alg"]?.let {
                Algorithm.fromHashAlgorithmIdentifier(it.jsonPrimitive.content)
            } ?: Algorithm.SHA256

            val htds = mutableMapOf<String, String>()
            for (n in IntRange(1, splits.size - 2)) {
                val disclosureString = splits[n]
                val hash = Crypto.digest(digestAlg, disclosureString.encodeToByteArray()).toBase64Url()
                htds.put(hash, disclosureString)
            }
            return SdJwt(
                compactSerialization = compactSerialization,
                digestAlg = digestAlg,
                jwtBody = jwtBody,
                header = header,
                body = body,
                signature = signature,
                hashToDisclosureString = htds
            )
        }

        /**
         * Defensively validates a compact serialization string for an SD-JWT according to RFC 9901.
         *
         * @param compactSerialization the compact serialization string.
         * @param now reference time to check expiration and validity windows, or `null`.
         * @return a [ValidationResult] containing any errors or warnings.
         */
        suspend fun validate(
            compactSerialization: String,
            now: Instant? = null
        ): ValidationResult = buildValidationResult {
            val sdJwt = validateCompactSerializationStructure(this, compactSerialization)
            if (sdJwt != null) {
                addAll(sdJwt.validate(now))
            }
        }

        /**
         * Defensively validates a compact serialization string for an SD-JWT VC according to the
         * SD-JWT VC specification.
         *
         * @param compactSerialization the compact serialization string.
         * @param expectedVct the expected Verifiable Credential Type, or `null`.
         * @param now reference time to check expiration and validity windows, or `null`.
         * @return a [ValidationResult] containing any errors or warnings.
         */
        suspend fun validateVc(
            compactSerialization: String,
            expectedVct: String? = null,
            now: Instant? = null
        ): ValidationResult = buildValidationResult {
            val sdJwt = validateCompactSerializationStructure(this, compactSerialization)
            if (sdJwt != null) {
                addAll(sdJwt.validateVc(expectedVct, now))
            }
        }

        private suspend fun validateCompactSerializationStructure(
            builder: ValidationResultBuilder,
            compactSerialization: String
        ): SdJwt? {
            if (!compactSerialization.endsWith('~')) {
                builder.addError("SD-JWT compact serialization must end with '~'")
                return null
            }
            val splits = compactSerialization.split("~")
            val jwtSplits = splits[0].split(".")
            if (jwtSplits.size != 3) {
                builder.addError("JWS in SD-JWT does not consist of three parts (header, body, signature)")
                return null
            }
            val headerStr = jwtSplits[0]
            val bodyStr = jwtSplits[1]

            try {
                Json.decodeFromString(JsonObject.serializer(), headerStr.fromBase64Url().decodeToString())
            } catch (e: Throwable) {
                builder.addError("Failed to decode JWS header from base64url/JSON: ${e.message}")
            }
            try {
                Json.decodeFromString(JsonObject.serializer(), bodyStr.fromBase64Url().decodeToString())
            } catch (e: Throwable) {
                builder.addError("Failed to decode JWS payload from base64url/JSON: ${e.message}")
            }

            for (n in 1 until (splits.size - 1)) {
                val discStr = splits[n]
                builder.addAll(Disclosure.validate(discStr))
            }

            return try {
                fromCompactSerialization(compactSerialization)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                builder.addError("Failed to parse SD-JWT: ${e.message}")
                null
            }
        }

        private fun toCompactSerialization(
            jwt: String,
            disclosures: List<JsonArray>
        ): String {
            val sb = StringBuilder()
            sb.append(jwt)
            sb.append('~')
            for (disclosure in disclosures) {
                sb.append(disclosure.toString().encodeToByteArray().toBase64Url())
                sb.append('~')
            }
            return sb.toString()
        }

        /**
         * Creates a SD-JWT.
         *
         * This implementation uses [DisclosureMetadata] in the "_sd" claim of each nested
         * JsonObject in the [claims] parameter to describe which claims to disclose.
         *
         * Note: this variant with [String] instead of [JsonObject] only exists for interoperability with Swift.
         *
         * @param issuerKey the key to sign the issuerSigned JWT with. If this is a [AsymmetricKey.X509Certified]
         *   the certificate chain will be included in the `x5c` claim and always be disclosed.
         * @param kbKey if set, a `cnf` claim with this public key will be included in the Issuer-signed JWT.
         * @param claims the object with claims that can be selectively disclosed.
         * @param digestAlgorithm the hash algorithm to use, e.g. [Algorithm.SHA256].
         * @param random the [Random] to use to generate salts.
         * @param saltSizeNumBits number of bits to use for each salt.
         * @param creationTime the time the SD-JWT was created, pass [Instant.DISTANT_PAST] to not set `iat` claim.
         * @param expiresIn the duration in which the SD-JWT expire or `null`.
         */
        suspend fun createFromMetadata(
            issuerKey: AsymmetricKey,
            kbKey: PublicKey?,
            claims: String,
            digestAlgorithm: Algorithm = Algorithm.SHA256,
            random: Random = Crypto.secureRandom,
            saltSizeNumBits: Int = 128,
            creationTime: Instant = Instant.DISTANT_PAST,
            expiresIn: Duration? = null
        ): SdJwt {
            return createFromMetadata(
                issuerKey = issuerKey,
                kbKey = kbKey,
                claims = Json.decodeFromString<JsonObject>(claims),
                digestAlgorithm = digestAlgorithm,
                random = random,
                saltSizeNumBits = saltSizeNumBits,
                creationTime = creationTime,
                expiresIn = expiresIn
            )
        }

        /**
         * Creates a SD-JWT.
         *
         * This implementation uses [DisclosureMetadata] in the "_sd" claim of each nested
         * JsonObject in the [claims] parameter to describe which claims to disclose.
         *
         * @param issuerKey the key to sign the issuerSigned JWT with. If this is a [AsymmetricKey.X509Certified]
         *   the certificate chain will be included in the `x5c` claim and always be disclosed.
         * @param kbKey if set, a `cnf` claim with this public key will be included in the Issuer-signed JWT.
         * @param claims the object with claims that can be selectively disclosed.
         * @param digestAlgorithm the hash algorithm to use, e.g. [Algorithm.SHA256].
         * @param random the [Random] to use to generate salts.
         * @param saltSizeNumBits number of bits to use for each salt.
         * @param creationTime the time the SD-JWT was created, pass [Instant.DISTANT_PAST] to not set `iat` claim.
         * @param expiresIn the duration in which the SD-JWT expire or `null`.
         */
        suspend fun createFromMetadata(
            issuerKey: AsymmetricKey,
            kbKey: PublicKey?,
            claims: JsonObject,
            digestAlgorithm: Algorithm = Algorithm.SHA256,
            random: Random = Crypto.secureRandom,
            saltSizeNumBits: Int = 128,
            creationTime: Instant = Instant.DISTANT_PAST,
            expiresIn: Duration? = null
        ): SdJwt {
            require(claims["iss"] != null) { "Must include `iss`" }

            val disclosures = mutableListOf<JsonArray>()
            val jwt = buildJwt(
                type = "dc+sd-jwt",
                key = issuerKey,
                creationTime = creationTime,
                expiresIn = expiresIn
            ) {
                mergeAndDiscloseJsonObject(
                    disclosures,
                    claims,
                    digestAlgorithm,
                    random,
                    saltSizeNumBits
                )

                put("_sd_alg", JsonPrimitive(digestAlgorithm.hashAlgorithmName))

                val kbKeyJwk = kbKey?.toJwk()
                if (kbKeyJwk != null) {
                    putJsonObject("cnf") {
                        put("jwk", kbKeyJwk)
                    }
                }
            }

            return fromCompactSerialization(toCompactSerialization(jwt, disclosures))
        }

        private suspend fun JsonObjectBuilder.mergeAndDiscloseJsonObject(
            disclosures: MutableList<JsonArray>,
            claims: JsonObject,
            digestAlgorithm: Algorithm,
            random: Random,
            saltSizeNumBits: Int
        ): JsonObjectBuilder {

            val disclosureMetadata = claims["_sd"]?.jsonObject?.toDisclosureMetadata()

            val claimDisclosures = mutableListOf<JsonArray>()

            for (claim in claims) {
                if(claim.key == "_sd") continue

                val updatedClaimValue = claim.value.extractDisclosures(
                    claim.key,
                    disclosures,
                    disclosureMetadata,
                    digestAlgorithm,
                    random,
                    saltSizeNumBits
                )
                if (disclosureMetadata.isClaimSelectivelyDisclosable(claim.key)) {
                    val disclosure = updatedClaimValue.toClaimDisclosure(
                        claim.key,
                        random.getRandomSalt(saltSizeNumBits)
                    )
                    claimDisclosures.add(disclosure)
                    disclosures.add(disclosure)
                } else {
                    put(claim.key, updatedClaimValue)
                }
            }

            putClaimDisclosureDigests(claimDisclosures, digestAlgorithm)

            return this
        }

        private suspend fun JsonElement.extractDisclosures(
            claimName: String,
            disclosures: MutableList<JsonArray>,
            disclosureMetadata: DisclosureMetadata?,
            digestAlgorithm: Algorithm,
            random: Random,
            saltSizeNumBits: Int
        ): JsonElement {
            return when (this) {
                is JsonPrimitive -> this
                is JsonObject -> buildJsonObject {
                    mergeAndDiscloseJsonObject(
                        disclosures,
                        this@extractDisclosures,
                        digestAlgorithm,
                        random,
                        saltSizeNumBits
                    )
                }
                is JsonArray -> {
                    JsonArray(
                        this.jsonArray.mapIndexed { index, element ->
                            val claimValue = element.extractDisclosures(
                                claimName,
                                disclosures,
                                disclosureMetadata,
                                digestAlgorithm,
                                random,
                                saltSizeNumBits
                            )
                            if (disclosureMetadata.isIndexSelectivelyDisclosable(
                                    claimName,
                                    index
                                )
                            ) {
                                val disclosure = claimValue.toArrayDisclosure(
                                    random.getRandomSalt(saltSizeNumBits)
                                )
                                disclosures.add(disclosure)
                                disclosure.toArrayDigestElement(digestAlgorithm)
                            } else {
                                claimValue
                            }
                        })
                }
            }
        }

        /**
         * Creates a SD-JWT.
         *
         * This implementation uses recursive disclosures for all claims in the [claims] parameter.
         *
         * Note: this variant with [String] instead of [JsonObject] only exists for interoperability with Swift.
         *
         * @param issuerKey the key to sign the issuerSigned JWT with. If this is a [AsymmetricKey.X509Certified]
         *   the certificate chain will be included in the `x5c` claim and always be disclosed.
         * @param kbKey if set, a `cnf` claim with this public key will be included in the Issuer-signed JWT.
         * @param claims the object with claims that can be selectively disclosed.
         * @param nonSdClaims claims to include in the Issuer-signed JWT which are always disclosed. This must at least
         *   include the `iss` claim and may include more such as `vct`, `sub`, `iat`, `nbf`, `exp`.
         * @param digestAlgorithm the hash algorithm to use, e.g. [Algorithm.SHA256].
         * @param random the [Random] to use to generate salts.
         * @param saltSizeNumBits number of bits to use for each salt.
         * @param creationTime the time the SD-JWT was created, pass [Instant.DISTANT_PAST] to not set `iat` claim.
         * @param expiresIn the duration in which the SD-JWT expire or `null`.
         * @param type the type of the SD-JWT in the JWS `typ` header parameter, defaults to `"dc+sd-jwt"`.
         */
        suspend fun create(
            issuerKey: AsymmetricKey,
            kbKey: PublicKey?,
            claims: String,
            nonSdClaims: String,
            digestAlgorithm: Algorithm = Algorithm.SHA256,
            random: Random = Crypto.secureRandom,
            saltSizeNumBits: Int = 128,
            creationTime: Instant = Instant.DISTANT_PAST,
            expiresIn: Duration? = null,
            type: String = "dc+sd-jwt"
        ): SdJwt {
            return create(
                issuerKey = issuerKey,
                kbKey = kbKey,
                claims = Json.decodeFromString<JsonObject>(claims),
                nonSdClaims = Json.decodeFromString<JsonObject>(nonSdClaims),
                digestAlgorithm = digestAlgorithm,
                random = random,
                saltSizeNumBits = saltSizeNumBits,
                creationTime = creationTime,
                expiresIn = expiresIn,
                type = type
            )
        }

        /**
         * Creates a SD-JWT.
         *
         * This implementation uses recursive disclosures for all claims in the [claims] parameter.
         *
         * @param issuerKey the key to sign the issuerSigned JWT with. If this is a [AsymmetricKey.X509Certified]
         *   the certificate chain will be included in the `x5c` claim and always be disclosed.
         * @param kbKey if set, a `cnf` claim with this public key will be included in the Issuer-signed JWT.
         * @param claims the object with claims that can be selectively disclosed.
         * @param nonSdClaims claims to include in the Issuer-signed JWT which are always disclosed. This must at least
         *   include the `iss` claim and may include more such as `vct`, `sub`, `iat`, `nbf`, `exp`.
         * @param digestAlgorithm the hash algorithm to use, e.g. [Algorithm.SHA256].
         * @param random the [Random] to use to generate salts.
         * @param saltSizeNumBits number of bits to use for each salt.
         * @param creationTime the time the SD-JWT was created, pass [Instant.DISTANT_PAST] to not set `iat` claim.
         * @param expiresIn the duration in which the SD-JWT expire or `null`.
         * @param type the type of the SD-JWT in the JWS `typ` header parameter, defaults to `"dc+sd-jwt"`.
         */
        suspend fun create(
            issuerKey: AsymmetricKey,
            kbKey: PublicKey?,
            claims: JsonObject,
            nonSdClaims: JsonObject,
            digestAlgorithm: Algorithm = Algorithm.SHA256,
            random: Random = Crypto.secureRandom,
            saltSizeNumBits: Int = 128,
            creationTime: Instant = Instant.DISTANT_PAST,
            expiresIn: Duration? = null,
            type: String = "dc+sd-jwt"
        ): SdJwt {
            require(nonSdClaims["iss"] != null) { "Must include `iss` claim in nonSdClaims" }

            // TODO: add support for decoy digests.

            val disclosures = mutableListOf<JsonArray>()

            val jwt = buildJwt(
                type = type,
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
                if (hashes.isNotEmpty()) {
                    putJsonArray("_sd") {
                        for (hash in hashes) {
                            add(JsonPrimitive(hash))
                        }
                    }
                }

                put("_sd_alg", JsonPrimitive(digestAlgorithm.hashAlgorithmName))

                val kbKeyJwk = kbKey?.toJwk()
                if (kbKeyJwk != null) {
                    putJsonObject("cnf") {
                        put("jwk", kbKeyJwk)
                    }
                }
            }
            return fromCompactSerialization(toCompactSerialization(jwt, disclosures))
        }
    }
}

private suspend fun insertClaim(
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
            if (subClaimHashes.isNotEmpty()) {
                putJsonArray("_sd") {
                    subClaimHashes.forEach { add(JsonPrimitive(it)) }
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
