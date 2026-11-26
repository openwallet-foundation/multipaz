package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.toCoseLabel
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url

/**
 * A PQ/T hybrid key encapsulation mechanism (KEM) private key as specified in
 * `draft-irtf-cfrg-concrete-hybrid-kems-04` and `draft-ietf-hpke-pq-05`.
 *
 * In standard seed format, [encoded] holds the 32-byte shared seed (`Nsk = 32`) from which both
 * component private keys are derived via SHAKE256.
 *
 * Call [close] when done with this key to zero key material in memory.
 *
 * @property algorithm the hybrid KEM [Algorithm] ([Algorithm.MLKEM768_P256],
 *   [Algorithm.MLKEM1024_P384], or [Algorithm.MLKEM768_X25519]).
 * @property publicKey the corresponding [HybridKemPublicKey].
 */
@CborSerializationImplemented(schemaId = "")
class HybridKemPrivateKey private constructor(
    val algorithm: Algorithm,
    private val _encoded: ByteArray,
    internal val componentKeys: Pair<MlKemPrivateKey, EcPrivateKey>?,
    override val publicKey: HybridKemPublicKey
) : PrivateKey() {

    /**
     * Constructs a seed-backed [HybridKemPrivateKey] from its 32-byte shared seed and [publicKey].
     *
     * @param algorithm the hybrid KEM [Algorithm].
     * @param encoded the 32-byte shared seed (`Nsk = 32`).
     * @param publicKey the corresponding [HybridKemPublicKey].
     * @throws IllegalArgumentException if [algorithm] or key lengths are invalid.
     */
    @Throws(IllegalArgumentException::class)
    constructor(
        algorithm: Algorithm,
        encoded: ByteString,
        publicKey: HybridKemPublicKey
    ) : this(
        algorithm = algorithm,
        _encoded = encoded.toByteArray(),
        componentKeys = null,
        publicKey = publicKey
    ) {
        require(_encoded.size == HybridKem.SEED_SIZE) {
            "Expected ${HybridKem.SEED_SIZE}-byte seed for $algorithm private key, got ${_encoded.size}"
        }
    }

    private var _isDestroyed: Boolean = false
    private val disposer: KeyDisposer

    init {
        var ok = false
        try {
            require(algorithm.isHybridKem) {
                "Algorithm $algorithm is not a hybrid KEM algorithm"
            }
            require(publicKey.algorithm == algorithm) {
                "Public key algorithm ${publicKey.algorithm} does not match private key algorithm $algorithm"
            }
            if (componentKeys == null) {
                require(_encoded.size == HybridKem.SEED_SIZE) {
                    "Expected ${HybridKem.SEED_SIZE}-byte seed for $algorithm private key, got ${_encoded.size}"
                }
            }
            ok = true
        } finally {
            if (!ok) {
                _encoded.secureZero()
                componentKeys?.first?.close()
                componentKeys?.second?.close()
            }
        }
        val rawBuffer = _encoded
        val componentsRef = componentKeys
        disposer = KeyDisposer.register(this) {
            rawBuffer.secureZero()
            componentsRef?.first?.close()
            componentsRef?.second?.close()
        }
    }

    /** Whether this private key has been closed and zeroed in memory. */
    override val isDestroyed: Boolean
        get() = _isDestroyed

    /**
     * Whether this private key is backed by a 32-byte shared seed representation (`true` on
     * JVM/Android/iOS, `false` for component-backed keys such as those generated via WebCrypto).
     */
    val hasSeed: Boolean
        get() = componentKeys == null

    /**
     * The 32-byte shared seed (`Nsk = 32`).
     *
     * @throws IllegalStateException if this key has already been destroyed.
     * @throws UnsupportedOperationException if this key was created from non-exportable component keys.
     */
    val encoded: ByteString
        get() {
            checkNotDestroyed()
            if (componentKeys != null) {
                throw UnsupportedOperationException(
                    "Component-backed hybrid KEM private key does not have a seed representation"
                )
            }
            return ByteString(_encoded)
        }

    internal fun ensureNotDestroyed() = checkNotDestroyed()

    internal val encodedKeyMaterial: ByteArray
        get() {
            checkNotDestroyed()
            if (componentKeys != null) {
                throw UnsupportedOperationException(
                    "Component-backed hybrid KEM private key does not have a seed representation"
                )
            }
            return _encoded
        }

    override fun close() {
        if (!_isDestroyed) {
            _isDestroyed = true
            _encoded.secureZero()
            componentKeys?.first?.close()
            componentKeys?.second?.close()
            disposer.dispose()
        }
    }

    /**
     * Creates an independent duplicate of this private key with its own memory buffer.
     *
     * @return a new [HybridKemPrivateKey] instance containing the same key material.
     * @throws IllegalStateException if this key has already been destroyed.
     */
    override fun duplicate(): HybridKemPrivateKey {
        checkNotDestroyed()
        return if (componentKeys != null) {
            HybridKemPrivateKey(
                algorithm = algorithm,
                _encoded = ByteArray(0),
                componentKeys = componentKeys.first.duplicate() to componentKeys.second.duplicate(),
                publicKey = publicKey
            )
        } else {
            HybridKemPrivateKey(algorithm, ByteString(_encoded), publicKey)
        }
    }

    /**
     * Encodes this private key as a COSE `AKP` [CoseKey].
     *
     * @param additionalLabels optional additional COSE labels to include.
     * @return the encoded `AKP` [CoseKey] containing both public and private key seed bytes.
     * @throws IllegalStateException if this key has already been destroyed.
     * @throws UnsupportedOperationException if this key has no seed representation.
     */
    override fun toCoseKey(additionalLabels: Map<CoseLabel, DataItem>): CoseKey {
        checkNotDestroyed()
        if (componentKeys != null) {
            throw UnsupportedOperationException(
                "Component-backed hybrid KEM private key cannot be serialized to COSE_Key"
            )
        }
        return publicKey.toCoseKey(
            mapOf(Pair(Cose.COSE_KEY_PARAM_PRIV_KEY.toCoseLabel, _encoded.copyOf().toDataItem())) +
                additionalLabels
        )
    }

    /**
     * Encodes this private key as an `AKP` JSON Web Key.
     *
     * @param additionalClaims optional additional JWK claims to include.
     * @return the encoded [JsonObject].
     * @throws IllegalStateException if this key has already been destroyed.
     * @throws UnsupportedOperationException if this key has no seed representation.
     */
    override fun toJwk(additionalClaims: JsonObject?): JsonObject {
        checkNotDestroyed()
        if (componentKeys != null) {
            throw UnsupportedOperationException(
                "Component-backed hybrid KEM private key cannot be serialized to JWK"
            )
        }
        val joseAlgId = algorithm.joseAlgorithmIdentifier
            ?: throw IllegalStateException("No JOSE algorithm identifier for $algorithm")
        return buildJsonObject {
            put("kty", "AKP")
            put("alg", joseAlgId)
            put("pub", publicKey.encoded.toByteArray().toBase64Url())
            put("priv", _encoded.toBase64Url())
            if (additionalClaims != null) {
                for ((k, v) in additionalClaims) {
                    put(k, v)
                }
            }
        }
    }

    /**
     * Throws [UnsupportedOperationException] because X.509/PEM OIDs are not defined for
     * CFRG hybrid KEMs (`draft-irtf-cfrg-concrete-hybrid-kems-04`).
     *
     * @throws UnsupportedOperationException always.
     */
    override fun toPem(): String {
        throw UnsupportedOperationException(
            "PEM encoding is not defined for CFRG hybrid KEM private keys ($algorithm)"
        )
    }

    override fun toString(): String = "HybridKemPrivateKey(algorithm=$algorithm, publicKey=$publicKey)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as HybridKemPrivateKey
        if (algorithm != other.algorithm) return false
        if (publicKey != other.publicKey) return false
        if (isDestroyed || other.isDestroyed) {
            return isDestroyed == other.isDestroyed
        }
        if (componentKeys != null || other.componentKeys != null) {
            return componentKeys == other.componentKeys
        }
        return _encoded.contentEquals(other._encoded)
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + publicKey.hashCode()
        if (!isDestroyed) {
            result = 31 * result + (componentKeys?.hashCode() ?: _encoded.contentHashCode())
        }
        return result
    }

    companion object {
        internal fun fromComponents(
            algorithm: Algorithm,
            pqPrivateKey: MlKemPrivateKey,
            traditionalPrivateKey: EcPrivateKey,
            publicKey: HybridKemPublicKey
        ): HybridKemPrivateKey = HybridKemPrivateKey(
            algorithm = algorithm,
            _encoded = ByteArray(0),
            componentKeys = pqPrivateKey to traditionalPrivateKey,
            publicKey = publicKey
        )

        /**
         * Decodes a [HybridKemPrivateKey] from a COSE `AKP` [CoseKey].
         *
         * @param coseKey the COSE Key containing public and private key parameters.
         * @return the decoded [HybridKemPrivateKey].
         * @throws IllegalArgumentException if [coseKey] is malformed or missing key parameters.
         */
        @Throws(IllegalArgumentException::class)
        fun fromCoseKey(coseKey: CoseKey): HybridKemPrivateKey {
            require(coseKey.keyType == Cose.COSE_KEY_TYPE_AKP.toDataItem()) {
                "Key type must be AKP (7), got ${coseKey.keyType}"
            }
            val privBytes = coseKey.labels[Cose.COSE_KEY_PARAM_PRIV_KEY.toCoseLabel]?.asBstr
                ?: throw IllegalArgumentException("Missing priv key in COSE Key")
            val pubKey = HybridKemPublicKey.fromCoseKey(coseKey)
            return HybridKemPrivateKey(pubKey.algorithm, ByteString(privBytes), pubKey)
        }

        /**
         * Decodes a [HybridKemPrivateKey] from an `AKP` JSON Web Key.
         *
         * @param jwk the JSON Web Key object containing `kty`, `alg`, `pub`, and `priv`.
         * @return the decoded [HybridKemPrivateKey].
         * @throws IllegalArgumentException if [jwk] is malformed or missing fields.
         */
        @Throws(IllegalArgumentException::class)
        fun fromJwk(jwk: JsonObject): HybridKemPrivateKey {
            val pubKey = HybridKemPublicKey.fromJwk(jwk)
            val privBase64 = jwk["priv"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing priv in JWK")
            val privBytes = privBase64.fromBase64Url()
            return try {
                HybridKemPrivateKey(pubKey.algorithm, ByteString(privBytes), pubKey)
            } finally {
                privBytes.secureZero()
            }
        }

        /**
         * Decodes a [HybridKemPrivateKey] from a CBOR [DataItem] encoding a COSE Key.
         *
         * @param dataItem the CBOR data item.
         * @return the decoded [HybridKemPrivateKey].
         * @throws IllegalArgumentException if [dataItem] is malformed or missing key parameters.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): HybridKemPrivateKey {
            return fromCoseKey(CoseKey.fromDataItem(dataItem))
        }
    }
}
