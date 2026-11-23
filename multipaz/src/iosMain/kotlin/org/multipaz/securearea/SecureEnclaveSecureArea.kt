package org.multipaz.securearea

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.HybridSigning
import org.multipaz.crypto.MlDsaPublicKey
import org.multipaz.crypto.MlDsaSignature
import org.multipaz.crypto.MlKemPublicKey
import org.multipaz.crypto.PublicKey
import org.multipaz.crypto.SecretKey
import org.multipaz.crypto.SecureByteString
import org.multipaz.crypto.Signature
import org.multipaz.prompt.Reason
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.util.isRunningOnSimulator
import org.multipaz.util.toByteArray
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.coroutineContext

/**
 * An implementation of [SecureArea] using the Apple Secure Enclave.
 *
 * This implementation uses [CryptoKit](https://developer.apple.com/documentation/cryptokit/secureenclave)
 * and supports [Algorithm.ESP256] and [Algorithm.ECDH_P256], and starting with iOS 26, post-quantum
 * algorithms [Algorithm.ML_DSA_65], [Algorithm.ML_DSA_87], [Algorithm.ML_KEM_768],
 * [Algorithm.ML_KEM_1024], and the PQ/T hybrid composite algorithm [Algorithm.ML_DSA_65_ES256].
 * For [Algorithm.ML_DSA_65_ES256], a Secure Enclave `ML-DSA-65` key and a Secure Enclave `P-256`
 * key are generated and stored under a single alias with the same access control settings and
 * unlocked together with a single [LAContext] during signing. Keys can optionally be protected by
 * user authentication which can be specified using [SecureEnclaveUserAuthType] and
 * [SecureEnclaveCreateKeySettings].
 *
 * Note that this platform automatically displays authentication dialogs when a key is used (if
 * needed) unlike other [SecureArea] dialogs where the application is expected to show
 * authentication dialogs via catching [KeyUnlockData], preparing a [KeyUnlockData], obtaining
 * authentication, and then retrying the operation.
 *
 * The behavior (for example, which message to show the user) of the platform native authentication
 * dialog can be customized by passing a [SecureEnclaveKeyUnlockData] with a suitable
 * [LAContext](https://developer.apple.com/documentation/LocalAuthentication/LAContext)
 * object when the key is used. Note that the platform native authentication dialog will show
 * even if this is not done.
 *
 * As the Secure Enclave does not current support key attestation, the base [KeyAttestation]
 * object is used.
 */
class SecureEnclaveSecureArea internal constructor(
    private val storageTable: StorageTable,
    private val partitionId: String,
    private val getBiometryState: () -> BiometryState = ::defaultGetBiometryState,
    private val backend: Backend = DefaultBackend
): SecureArea {

    internal sealed class BiometryState {
        data class Enrolled(val domainState: ByteString) : BiometryState()
        data object NotEnrolled : BiometryState()
        data object Unavailable : BiometryState()
    }

    internal interface Backend {
        val isPqcSupported: Boolean
        val isRunningOnSimulator: Boolean
        suspend fun createEcPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, EcPublicKey>
        suspend fun ecSign(
            keyBlob: ByteArray,
            message: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): EcSignature
        suspend fun ecKeyAgreement(
            keyBlob: ByteArray,
            otherKey: EcPublicKey,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): SecureByteString
        suspend fun createMlDsaPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, MlDsaPublicKey>
        suspend fun mlDsaSign(
            algorithm: Algorithm,
            keyBlob: ByteArray,
            message: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?,
            context: ByteArray
        ): MlDsaSignature
        suspend fun createMlKemPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, MlKemPublicKey>
        suspend fun mlKemDecapsulate(
            algorithm: Algorithm,
            keyBlob: ByteArray,
            ciphertext: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): SecureByteString
    }

    private object DefaultBackend : Backend {
        override val isPqcSupported: Boolean
            get() = Crypto.secureEnclaveIsPqcSupported

        override val isRunningOnSimulator: Boolean
            get() = org.multipaz.util.isRunningOnSimulator()

        override suspend fun createEcPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, EcPublicKey> =
            Crypto.secureEnclaveCreateEcPrivateKey(algorithm, accessControlCreateFlags)

        override suspend fun ecSign(
            keyBlob: ByteArray,
            message: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): EcSignature =
            Crypto.secureEnclaveEcSign(keyBlob, message, keyUnlockData)

        override suspend fun ecKeyAgreement(
            keyBlob: ByteArray,
            otherKey: EcPublicKey,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): SecureByteString =
            Crypto.secureEnclaveEcKeyAgreement(keyBlob, otherKey, keyUnlockData)

        override suspend fun createMlDsaPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, MlDsaPublicKey> =
            Crypto.secureEnclaveCreateMlDsaPrivateKey(algorithm, accessControlCreateFlags)

        override suspend fun mlDsaSign(
            algorithm: Algorithm,
            keyBlob: ByteArray,
            message: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?,
            context: ByteArray
        ): MlDsaSignature =
            Crypto.secureEnclaveMlDsaSign(algorithm, keyBlob, message, keyUnlockData, context)

        override suspend fun createMlKemPrivateKey(
            algorithm: Algorithm,
            accessControlCreateFlags: Long
        ): Pair<ByteArray, MlKemPublicKey> =
            Crypto.secureEnclaveCreateMlKemPrivateKey(algorithm, accessControlCreateFlags)

        override suspend fun mlKemDecapsulate(
            algorithm: Algorithm,
            keyBlob: ByteArray,
            ciphertext: ByteArray,
            keyUnlockData: SecureEnclaveKeyUnlockData?
        ): SecureByteString =
            Crypto.secureEnclaveMlKemDecapsulate(algorithm, keyBlob, ciphertext, keyUnlockData)
    }

    private data class CachedKeyEntry(
        val keyInfo: SecureEnclaveKeyInfo,
        val keyMetadata: SecureEnclaveAreaKeyMetadata,
    )

    private val keyCacheLock = Mutex()
    private val keyCache = mutableMapOf<String, CachedKeyEntry>()

    companion object {
        private val TAG = "SecureEnclaveSecureArea"

        /**
         * Creates an instance of [SecureEnclaveSecureArea].
         *
         * @param storage the storage engine to use for storing key metadata.
         * @param partitionId the partitionId to use for [storage].
         */
        suspend fun create(
            storage: Storage,
            partitionId: String = "default"
        ): SecureEnclaveSecureArea {
            return SecureEnclaveSecureArea(storage.getTable(tableSpec), partitionId)
        }

        internal val tableSpec = StorageTableSpec(
            name = "SecureEnclaveSecureArea",
            supportPartitions = true,
            supportExpiration = false
        )

        @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
        internal fun defaultGetBiometryState(): BiometryState {
            if (isRunningOnSimulator()) {
                return BiometryState.Unavailable
            }
            return memScoped {
                val context = LAContext()
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                if (context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, errorPtr.ptr)) {
                    val domainState = context.evaluatedPolicyDomainState?.let { ByteString(it.toByteArray()) }
                    if (domainState != null) {
                        BiometryState.Enrolled(domainState)
                    } else {
                        BiometryState.NotEnrolled
                    }
                } else {
                    when (errorPtr.value?.code) {
                        LAErrorBiometryNotEnrolled, LAErrorPasscodeNotSet -> BiometryState.NotEnrolled
                        else -> BiometryState.Unavailable
                    }
                }
            }
        }
    }

    override val identifier: String
        get() = "SecureEnclaveSecureArea"

    override val displayName: String
        get() = "Secure Enclave Secure Area"

    override val supportedAlgorithms: List<Algorithm>
        get() {
            val list = mutableListOf(Algorithm.ESP256, Algorithm.ECDH_P256)
            if (backend.isPqcSupported) {
                list.add(Algorithm.ML_DSA_65)
                list.add(Algorithm.ML_DSA_87)
                list.add(Algorithm.ML_KEM_768)
                list.add(Algorithm.ML_KEM_1024)
                list.add(Algorithm.ML_DSA_65_ES256)
            }
            return list
        }

    override suspend fun createKey(alias: String?, createKeySettings: CreateKeySettings): KeyInfo {
        if (alias != null) {
            keyCacheLock.withLock {
                keyCache.remove(alias)
            }
            // If the key with the given alias exists, it is silently overwritten.
            storageTable.delete(alias, partitionId)
        }

        require(createKeySettings.algorithm in supportedAlgorithms) {
            "Algorithm ${createKeySettings.algorithm} is not supported"
        }

        val settings = if (createKeySettings is SecureEnclaveCreateKeySettings) {
            createKeySettings
        } else {
            // If user passed in a generic SecureArea.CreateKeySettings, honor that (although
            // only key settings can really be honored).
            SecureEnclaveCreateKeySettings.Builder()
                .setAlgorithm(createKeySettings.algorithm)
                .setUserAuthenticationRequired(
                    required = createKeySettings.userAuthenticationRequired,
                    userAuthenticationTypes = setOf(
                        SecureEnclaveUserAuthType.USER_PRESENCE
                    )
                )
                .build()
        }

        var accessControlCreateFlags = 0L
        if (settings.userAuthenticationRequired) {
            accessControlCreateFlags = SecureEnclaveUserAuthType.encodeSet(settings.userAuthenticationTypes)
        }
        // If running on the simulator, no point in trying to create keys with authentication (it will fail)
        // and since no key attestation is generated the party requesting the key creation (likely issuer) is
        // none the wiser. And if they care about security they'd do a DeviceCheck anyway to avoid interacting
        // with simulator instances.
        //
        if (backend.isRunningOnSimulator) {
            if (accessControlCreateFlags != 0L) {
                Logger.w(TAG, "Running on simulator, ignoring userAuthenticationTypes " +
                        "set to ${settings.userAuthenticationTypes} for key creation")
                accessControlCreateFlags = 0L
            }
        }
        val (keyBlob, traditionalKeyBlob, pubKey) = when (settings.algorithm) {
            Algorithm.ESP256, Algorithm.ECDH_P256 -> {
                val (blob, pk) = backend.createEcPrivateKey(
                    settings.algorithm,
                    accessControlCreateFlags
                )
                Triple(blob, null, pk)
            }
            Algorithm.ML_DSA_65, Algorithm.ML_DSA_87 -> {
                val (blob, pk) = backend.createMlDsaPrivateKey(
                    settings.algorithm,
                    accessControlCreateFlags
                )
                Triple(blob, null, pk)
            }
            Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024 -> {
                val (blob, pk) = backend.createMlKemPrivateKey(
                    settings.algorithm,
                    accessControlCreateFlags
                )
                Triple(blob, null, pk)
            }
            Algorithm.ML_DSA_65_ES256 -> {
                val spec = HybridSigning.specFor(settings.algorithm)
                val (pqKeyBlob, pqPubKey) = backend.createMlDsaPrivateKey(
                    spec.pqAlgorithm,
                    accessControlCreateFlags
                )
                val (tradKeyBlob, tradPubKey) = backend.createEcPrivateKey(
                    spec.traditionalAlgorithm,
                    accessControlCreateFlags
                )
                val hybridPubKey = HybridSigning.createPublicKey(
                    algorithm = settings.algorithm,
                    pqPublicKey = pqPubKey,
                    traditionalPublicKey = tradPubKey
                )
                Triple(pqKeyBlob, tradKeyBlob, hybridPubKey)
            }
            else -> throw IllegalArgumentException("Unsupported algorithm ${settings.algorithm}")
        }
        val newAlias = insertKey(alias, settings, keyBlob, pubKey, traditionalKeyBlob)
        return getKeyInfo(newAlias)
    }

    internal suspend fun insertKey(
        alias: String?,
        settings: SecureEnclaveCreateKeySettings,
        keyBlob: ByteArray,
        publicKey: PublicKey,
        traditionalKeyBlob: ByteArray? = null,
    ): String {
        val biometryDomainState = if (
            settings.userAuthenticationRequired &&
            SecureEnclaveUserAuthType.BIOMETRY_CURRENT_SET in settings.userAuthenticationTypes
        ) {
            when (val state = getBiometryState()) {
                is BiometryState.Enrolled -> state.domainState
                is BiometryState.NotEnrolled -> ByteString()
                is BiometryState.Unavailable -> null
            }
        } else {
            null
        }
        val keyMetadata = SecureEnclaveAreaKeyMetadata(
            algorithm = settings.algorithm,
            userAuthenticationRequired = settings.userAuthenticationRequired,
            userAuthenticationTypes = SecureEnclaveUserAuthType.encodeSet(settings.userAuthenticationTypes),
            publicKey = publicKey,
            keyBlob = ByteString(keyBlob),
            biometryDomainState = biometryDomainState,
            traditionalKeyBlob = traditionalKeyBlob?.let { ByteString(it) }
        )
        val insertedAlias = storageTable.insert(alias, ByteString(keyMetadata.toCbor()), partitionId)
        val keyInfo = buildKeyInfo(insertedAlias, keyMetadata)
        keyCacheLock.withLock {
            keyCache[insertedAlias] = CachedKeyEntry(keyInfo, keyMetadata)
        }
        return insertedAlias
    }

    private fun buildKeyInfo(
        alias: String,
        keyMetadata: SecureEnclaveAreaKeyMetadata
    ): SecureEnclaveKeyInfo {
        val userAuthenticationTypes =
            SecureEnclaveUserAuthType.decodeSet(keyMetadata.userAuthenticationTypes)
        return SecureEnclaveKeyInfo(
            alias,
            keyMetadata.algorithm,
            keyMetadata.publicKey,
            keyMetadata.userAuthenticationRequired,
            userAuthenticationTypes
        )
    }

    private fun isKeyInvalidated(keyMetadata: SecureEnclaveAreaKeyMetadata): Boolean {
        val recordedDomainState = keyMetadata.biometryDomainState ?: return false
        if (recordedDomainState.size == 0) {
            return true
        }
        return when (val currentState = getBiometryState()) {
            is BiometryState.Enrolled -> currentState.domainState != recordedDomainState
            is BiometryState.NotEnrolled -> true
            is BiometryState.Unavailable -> false
        }
    }

    private suspend fun loadKeyIgnoreKeyInvalidated(alias: String): CachedKeyEntry {
        keyCacheLock.withLock {
            keyCache[alias]?.let { entry ->
                return entry
            }
        }
        val data = storageTable.get(alias, partitionId)
            ?: throw IllegalArgumentException("No key with given alias")

        val keyMetadata = SecureEnclaveAreaKeyMetadata.fromCbor(data.toByteArray())
        val keyInfo = buildKeyInfo(alias, keyMetadata)
        val entry = CachedKeyEntry(keyInfo, keyMetadata)
        keyCacheLock.withLock {
            keyCache[alias] = entry
        }

        return entry
    }

    private suspend fun loadKey(alias: String): CachedKeyEntry {
        val entry = loadKeyIgnoreKeyInvalidated(alias)
        if (isKeyInvalidated(entry.keyMetadata)) {
            throw KeyInvalidatedException("Key is no longer usable")
        }
        return entry
    }

    override suspend fun deleteKey(alias: String) {
        keyCacheLock.withLock {
            keyCache.remove(alias)
        }
        storageTable.delete(alias, partitionId)
    }

    override suspend fun sign(
        alias: String,
        dataToSign: ByteArray,
        unlockReason: Reason
    ): Signature {
        val entry = loadKey(alias)
        val keyInfo = entry.keyInfo
        val keyBlob = entry.keyMetadata.keyBlob.toByteArray()
        check(keyInfo.algorithm.isSigning)
        val unlockDataProvider = coroutineContext[KeyUnlockDataProvider.Key]
            ?: SecureEnclaveDefaultKeyUnlockDataProvider
        val unlockData = if (keyInfo.isUserAuthenticationRequired) {
            unlockDataProvider.getKeyUnlockData(
                secureArea = this,
                alias = alias,
                algorithm = keyInfo.algorithm,
                unlockReason = unlockReason
            )
        } else {
            null
        }
        check(unlockData is SecureEnclaveKeyUnlockData?)
        return when (keyInfo.algorithm) {
            Algorithm.ESP256 -> backend.ecSign(keyBlob, dataToSign, unlockData)
            Algorithm.ML_DSA_65, Algorithm.ML_DSA_87 -> {
                backend.mlDsaSign(keyInfo.algorithm, keyBlob, dataToSign, unlockData, byteArrayOf())
            }
            Algorithm.ML_DSA_65_ES256 -> {
                val tradKeyBlob = entry.keyMetadata.traditionalKeyBlob?.toByteArray()
                    ?: throw IllegalStateException("Missing traditionalKeyBlob for hybrid key $alias")
                HybridSigning.combineSign(
                    algorithm = keyInfo.algorithm,
                    message = dataToSign,
                    signPq = { pqAlgorithm, mPrime, context ->
                        backend.mlDsaSign(pqAlgorithm, keyBlob, mPrime, unlockData, context)
                    },
                    signTraditional = { traditionalAlgorithm, mPrime ->
                        check(traditionalAlgorithm == Algorithm.ESP256)
                        backend.ecSign(tradKeyBlob, mPrime, unlockData)
                    }
                )
            }
            else -> throw IllegalStateException("Unexpected signing algorithm ${keyInfo.algorithm}")
        }
    }

    override suspend fun keyAgreement(
        alias: String,
        otherKey: EcPublicKey,
        unlockReason: Reason
    ): SecureByteString {
        val entry = loadKey(alias)
        val keyInfo = entry.keyInfo
        val keyBlob = entry.keyMetadata.keyBlob.toByteArray()
        check(otherKey.curve == EcCurve.P256)
        check(keyInfo.algorithm.isKeyAgreement)
        val unlockDataProvider = coroutineContext[KeyUnlockDataProvider.Key]
            ?: SecureEnclaveDefaultKeyUnlockDataProvider
        val unlockData = if (keyInfo.isUserAuthenticationRequired) {
            unlockDataProvider.getKeyUnlockData(
                secureArea = this,
                alias = alias,
                algorithm = keyInfo.algorithm,
                unlockReason = unlockReason
            )
        } else {
            null
        }
        check(unlockData is SecureEnclaveKeyUnlockData?)
        return backend.ecKeyAgreement(keyBlob, otherKey, unlockData)
    }

    override suspend fun kemDecapsulate(
        alias: String,
        ciphertext: ByteArray,
        unlockReason: Reason
    ): SecureByteString {
        val entry = loadKey(alias)
        val keyInfo = entry.keyInfo
        val keyBlob = entry.keyMetadata.keyBlob.toByteArray()
        check(keyInfo.algorithm.isKeyEncapsulation)
        val unlockDataProvider = coroutineContext[KeyUnlockDataProvider.Key]
            ?: SecureEnclaveDefaultKeyUnlockDataProvider
        val unlockData = if (keyInfo.isUserAuthenticationRequired) {
            unlockDataProvider.getKeyUnlockData(
                secureArea = this,
                alias = alias,
                algorithm = keyInfo.algorithm,
                unlockReason = unlockReason
            )
        } else {
            null
        }
        check(unlockData is SecureEnclaveKeyUnlockData?)
        return backend.mlKemDecapsulate(
            keyInfo.algorithm,
            keyBlob,
            ciphertext,
            unlockData
        )
    }

    override suspend fun getKeyInfo(alias: String): SecureEnclaveKeyInfo {
        return loadKeyIgnoreKeyInvalidated(alias).keyInfo
    }

    override suspend fun getKeyInvalidated(alias: String): Boolean {
        return isKeyInvalidated(loadKeyIgnoreKeyInvalidated(alias).keyMetadata)
    }

    override suspend fun unlockKey(
        alias: String,
        unlockReason: Reason
    ): List<KeyUnlockData> {
        val keyInfo = getKeyInfo(alias)
        if (!keyInfo.isUserAuthenticationRequired) {
            return emptyList()
        }
        val unlockDataProvider = coroutineContext[KeyUnlockDataProvider.Key]
            ?: SecureEnclaveDefaultKeyUnlockDataProvider
        val unlockData = unlockDataProvider.getKeyUnlockData(
            secureArea = this,
            alias = alias,
            algorithm = keyInfo.algorithm,
            unlockReason = unlockReason
        )
        return listOf(unlockData)
    }
}