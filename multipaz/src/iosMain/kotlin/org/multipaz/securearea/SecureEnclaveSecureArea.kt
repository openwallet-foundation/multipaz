package org.multipaz.securearea

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
import org.multipaz.crypto.Signature
import org.multipaz.prompt.Reason
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.util.isRunningOnSimulator
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
class SecureEnclaveSecureArea private constructor(
    private val storageTable: StorageTable,
    private val partitionId: String
): SecureArea {

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

        private val tableSpec = StorageTableSpec(
            name = "SecureEnclaveSecureArea",
            supportPartitions = true,
            supportExpiration = false
        )
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

    private suspend fun insertKey(
        alias: String?,
        settings: SecureEnclaveCreateKeySettings,
        keyBlob: ByteArray,
        publicKey: PublicKey,
    ): String {
        val keyMetadata = SecureEnclaveAreaKeyMetadata(
            algorithm = settings.algorithm,
            userAuthenticationRequired = settings.userAuthenticationRequired,
            userAuthenticationTypes = SecureEnclaveUserAuthType.encodeSet(settings.userAuthenticationTypes),
            publicKey = publicKey,
            keyBlob = ByteString(keyBlob)
        )
        return storageTable.insert(alias, ByteString(keyMetadata.toCbor()), partitionId)
    }

    private suspend fun loadKey(alias: String): Pair<ByteArray, SecureEnclaveKeyInfo> {
        val data = storageTable.get(alias, partitionId)
            ?: throw IllegalArgumentException("No key with given alias")

        val keyMetadata = SecureEnclaveAreaKeyMetadata.fromCbor(data.toByteArray())
        val userAuthenticationTypes =
            SecureEnclaveUserAuthType.decodeSet(keyMetadata.userAuthenticationTypes)

        val keyInfo = SecureEnclaveKeyInfo(
            alias,
            keyMetadata.algorithm,
            keyMetadata.publicKey,
            keyMetadata.userAuthenticationRequired,
            userAuthenticationTypes
        )

        return Pair(keyMetadata.keyBlob.toByteArray(), keyInfo)
    }

    override suspend fun deleteKey(alias: String) {
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
        // TODO: implement default KeyUnlockDataProvider by converting
        //  OperationReason to OperationReason.HumanReadable using PromptModel
        //  and the creating LAContext with title/subtitle from OperationReason.HumanReadable
        val unlockData = if (keyInfo.isUserAuthenticationRequired) {
            unlockDataProvider.getKeyUnlockData(
                secureArea = this,
                alias = alias,
                algorithm = getKeyInfo(alias).algorithm,
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
    ): ByteArray {
        val (keyBlob, keyInfo) = loadKey(alias)
        check(otherKey.curve == EcCurve.P256)
        check(keyInfo.algorithm.isKeyAgreement)
        val unlockDataProvider = coroutineContext[KeyUnlockDataProvider.Key]
            ?: SecureEnclaveDefaultKeyUnlockDataProvider
        // TODO: implement default KeyUnlockDataProvider by converting
        //  OperationReason to OperationReason.HumanReadable using PromptModel
        //  and the creating LAContext with title/subtitle from OperationReason.HumanReadable
        val unlockData = if (keyInfo.isUserAuthenticationRequired) {
            unlockDataProvider.getKeyUnlockData(
                secureArea = this,
                alias = alias,
                algorithm = getKeyInfo(alias).algorithm,
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
    ): ByteArray {
        val (keyBlob, keyInfo) = loadKey(alias)
        check(keyInfo.algorithm.isKeyEncapsulation)
        val unlockDataProvider = coroutineContext[KeyUnlockDataProvider.Key]
            ?: SecureEnclaveDefaultKeyUnlockDataProvider
        val unlockData = if (keyInfo.isUserAuthenticationRequired) {
            unlockDataProvider.getKeyUnlockData(
                secureArea = this,
                alias = alias,
                algorithm = getKeyInfo(alias).algorithm,
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
        val (_, keyInfo) = loadKey(alias)
        return keyInfo
    }

    override suspend fun getKeyInvalidated(alias: String): Boolean {
        return false
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