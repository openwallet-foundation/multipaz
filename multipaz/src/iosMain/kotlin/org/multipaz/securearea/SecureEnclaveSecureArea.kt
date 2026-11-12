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
 * algorithms [Algorithm.ML_DSA_65], [Algorithm.ML_DSA_87], [Algorithm.ML_KEM_768], and
 * [Algorithm.ML_KEM_1024]. Keys can optionally be protected by user authentication
 * which can be specified using [SecureEnclaveUserAuthType] and [SecureEnclaveCreateKeySettings].
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
    private val getBiometryState: () -> BiometryState = ::defaultGetBiometryState
): SecureArea {

    internal sealed class BiometryState {
        data class Enrolled(val domainState: ByteString) : BiometryState()
        data object NotEnrolled : BiometryState()
        data object Unavailable : BiometryState()
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
            if (Crypto.secureEnclaveIsPqcSupported) {
                list.add(Algorithm.ML_DSA_65)
                list.add(Algorithm.ML_DSA_87)
                list.add(Algorithm.ML_KEM_768)
                list.add(Algorithm.ML_KEM_1024)
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
        if (isRunningOnSimulator()) {
            if (accessControlCreateFlags != 0L) {
                Logger.w(TAG, "Running on simulator, ignoring userAuthenticationTypes " +
                        "set to ${settings.userAuthenticationTypes} for key creation")
                accessControlCreateFlags = 0L
            }
        }
        val (keyBlob, pubKey) = when (settings.algorithm) {
            Algorithm.ESP256, Algorithm.ECDH_P256 -> {
                Crypto.secureEnclaveCreateEcPrivateKey(
                    settings.algorithm,
                    accessControlCreateFlags
                )
            }
            Algorithm.ML_DSA_65, Algorithm.ML_DSA_87 -> {
                Crypto.secureEnclaveCreateMlDsaPrivateKey(
                    settings.algorithm,
                    accessControlCreateFlags
                )
            }
            Algorithm.ML_KEM_768, Algorithm.ML_KEM_1024 -> {
                Crypto.secureEnclaveCreateMlKemPrivateKey(
                    settings.algorithm,
                    accessControlCreateFlags
                )
            }
            else -> throw IllegalArgumentException("Unsupported algorithm ${settings.algorithm}")
        }
        val newAlias = insertKey(alias, settings, keyBlob, pubKey)
        return getKeyInfo(newAlias)
    }

    internal suspend fun insertKey(
        alias: String?,
        settings: SecureEnclaveCreateKeySettings,
        keyBlob: ByteArray,
        publicKey: PublicKey,
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
            biometryDomainState = biometryDomainState
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

    private suspend fun loadKeyIgnoreKeyInvalidated(
        alias: String
    ): Triple<ByteArray, SecureEnclaveKeyInfo, SecureEnclaveAreaKeyMetadata> {
        keyCacheLock.withLock {
            keyCache[alias]?.let { entry ->
                return Triple(entry.keyMetadata.keyBlob.toByteArray(), entry.keyInfo, entry.keyMetadata)
            }
        }
        val data = storageTable.get(alias, partitionId)
            ?: throw IllegalArgumentException("No key with given alias")

        val keyMetadata = SecureEnclaveAreaKeyMetadata.fromCbor(data.toByteArray())
        val keyInfo = buildKeyInfo(alias, keyMetadata)
        keyCacheLock.withLock {
            keyCache[alias] = CachedKeyEntry(keyInfo, keyMetadata)
        }

        return Triple(keyMetadata.keyBlob.toByteArray(), keyInfo, keyMetadata)
    }

    private suspend fun loadKey(alias: String): Pair<ByteArray, SecureEnclaveKeyInfo> {
        val (keyBlob, keyInfo, keyMetadata) = loadKeyIgnoreKeyInvalidated(alias)
        if (isKeyInvalidated(keyMetadata)) {
            throw KeyInvalidatedException("Key is no longer usable")
        }
        return Pair(keyBlob, keyInfo)
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
        val (keyBlob, keyInfo) = loadKey(alias)
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
            Algorithm.ESP256 -> Crypto.secureEnclaveEcSign(keyBlob, dataToSign, unlockData)
            Algorithm.ML_DSA_65, Algorithm.ML_DSA_87 -> {
                Crypto.secureEnclaveMlDsaSign(keyInfo.algorithm, keyBlob, dataToSign, unlockData)
            }
            else -> throw IllegalStateException("Unexpected signing algorithm ${keyInfo.algorithm}")
        }
    }

    override suspend fun keyAgreement(
        alias: String,
        otherKey: EcPublicKey,
        unlockReason: Reason
    ): SecureByteString {
        val (keyBlob, keyInfo) = loadKey(alias)
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
        return Crypto.secureEnclaveEcKeyAgreement(keyBlob, otherKey, unlockData)
    }

    override suspend fun kemDecapsulate(
        alias: String,
        ciphertext: ByteArray,
        unlockReason: Reason
    ): SecureByteString {
        val (keyBlob, keyInfo) = loadKey(alias)
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
        return Crypto.secureEnclaveMlKemDecapsulate(
            keyInfo.algorithm,
            keyBlob,
            ciphertext,
            unlockData
        )
    }

    override suspend fun getKeyInfo(alias: String): SecureEnclaveKeyInfo {
        val (_, keyInfo, _) = loadKeyIgnoreKeyInvalidated(alias)
        return keyInfo
    }

    override suspend fun getKeyInvalidated(alias: String): Boolean {
        val (_, _, keyMetadata) = loadKeyIgnoreKeyInvalidated(alias)
        return isKeyInvalidated(keyMetadata)
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