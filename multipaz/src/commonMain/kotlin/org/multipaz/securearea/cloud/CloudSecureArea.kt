package org.multipaz.securearea.cloud

import io.ktor.client.engine.HttpClientEngineFactory
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.SecureByteString
import org.multipaz.crypto.Signature
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509CertChainValidationException
import org.multipaz.crypto.secureZero
import org.multipaz.device.DeviceCheck
import org.multipaz.prompt.PassphraseEvaluation
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.prompt.Reason
import org.multipaz.prompt.requestPassphrase
import org.multipaz.rpc.auth.ClientRegistrationStub
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcAuthIssuerAssertion
import org.multipaz.rpc.handler.RpcDispatcher
import org.multipaz.rpc.handler.RpcDispatcherAuth
import org.multipaz.rpc.handler.RpcDispatcherHttp
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.rpc.transport.HttpTransport
import org.multipaz.rpc.transport.KtorHttpTransport
import org.multipaz.securearea.BatchCreateKeyResult
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.KeyInfo
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.fromCbor
import org.multipaz.securearea.toCbor
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageEngine
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger

/**
 * Creates a platform-specific [SecureArea] for the local key created for each Cloud Secure Area key.
 *
 * @param storage the [Storage] to use for the platform-specific [SecureArea].
 * @param partitionId the partition id to use for [storage].
 */
internal expect suspend fun cloudSecureAreaGetPlatformSecureArea(
    storage: Storage,
    partitionId: String,
): SecureArea

/**
 * Creates a [CreateKeySettings] for the platform-specific [SecureArea].
 */
internal expect fun cloudSecureAreaGetPlatformSecureAreaCreateKeySettings(
    challenge: ByteString,
    algorithm: Algorithm,
    userAuthenticationRequired: Boolean,
    userAuthenticationTypes: Set<CloudUserAuthType>
): CreateKeySettings

internal fun buildCloudSecureAreaExceptionMap(): RpcExceptionMap {
    return RpcExceptionMap.Builder().apply {
        CloudWrongPassphraseException.register(this)
        CloudTooManyPassphraseAttemptsException.register(this)
    }.build()
}

/**
 * An implementation of [SecureArea] using a Secure Area managed by an external server.
 */
open class CloudSecureArea protected constructor(
    private val storageTable: StorageTable,
    final override val identifier: String,
    val serverUrl: String,
    httpClientEngineFactory: HttpClientEngineFactory<*>,
    @Suppress("UNUSED_PARAMETER")
    private val random: Random = Crypto.secureRandom
) : SecureArea {

    internal open suspend fun delayForBruteforceMitigation(duration: Duration) {
        Logger.i(TAG, "delayForBruteforceMitigation: delaying for $duration")
        delay(duration)
        Logger.i(TAG, "delayForBruteforceMitigation: resuming (delayed for $duration)")
    }

    override val displayName: String
        get() = "Cloud Secure Area"

    private val supportedAlgorithms_: List<Algorithm> by lazy {
        // TODO: get from server to support configurations where only a subset of algorithms are supported.
        Algorithm.entries.filter {
            it.fullySpecified && (
                it.curve != null ||
                (it.keySizeBits != null && it.isSigning) ||
                it.isMlDsa ||
                it.isMlKem
            )
        }
    }

    override val supportedAlgorithms: List<Algorithm>
        get() = supportedAlgorithms_

    private val ktorHttpTransport = KtorHttpTransport(
        engine = httpClientEngineFactory,
        baseUrl = serverUrl.trimEnd('/')
    )

    private val httpTransport = object : HttpTransport {
        override suspend fun post(url: String, data: ByteString): ByteString {
            return communicateLowlevel(url, data)
        }
    }

    private val exceptionMap = buildCloudSecureAreaExceptionMap()
    private val baseDispatcher = RpcDispatcherHttp(httpTransport, exceptionMap)
    private var authorizedDispatcher: RpcDispatcher? = null
    private var rpcSession = RpcAuthClientSession()

    private var clientId: String? = null
    private var deviceAttestationId: String? = null
    private var registrationContext: ByteString? = null

    private val keyInfoCacheLock = Mutex()
    private val keyInfoCache = mutableMapOf<String, CloudKeyInfo>()

    /**
     * Loads persisted Cloud Secure Area state and initializes its platform secure area.
     *
     * @throws IllegalArgumentException if [identifier] does not have the required prefix.
     */
    protected open suspend fun initialize() {
        require(identifier.startsWith(IDENTIFIER_PREFIX))
        clientId = storageTable.get(CLIENT_ID, identifier)?.decodeToString()
        deviceAttestationId = storageTable.get(DEVICE_ATTESTATION_ID, identifier)?.decodeToString()
        registrationContext = storageTable.get(REGISTRATION_CONTEXT, identifier)
        platformSecureArea = cloudSecureAreaGetPlatformSecureArea(
            storage = storageTable.storage,
            partitionId = PLATFORM_SECURE_AREA_IDENTIFIER_PREFIX + identifier
        )
        if (clientId != null && deviceAttestationId != null) {
            authorizedDispatcher = RpcDispatcherAuth(
                base = baseDispatcher,
                rpcAuthIssuer = RpcAuthIssuerAssertion(
                    clientId = clientId!!,
                    secureArea = platformSecureArea,
                    deviceAttestationId = deviceAttestationId!!
                )
            )
        }
    }

    internal open suspend fun communicateLowlevel(
        relativeUrl: String,
        requestData: ByteString
    ): ByteString {
        return ktorHttpTransport.post(relativeUrl, requestData)
    }

    internal suspend fun <T> withRpcStub(
        block: suspend (CloudSecureAreaProtocol) -> T
    ): T {
        val context = registrationContext
            ?: throw CloudException("Not registered with Cloud Secure Area")
        val dispatcher = authorizedDispatcher
            ?: throw CloudException("Not registered with Cloud Secure Area")
        val stub = CloudSecureAreaProtocolStub.fromCbor(
            context.toByteArray(),
            dispatcher,
            RpcNotifier.SILENT
        )
        return try {
            withContext(rpcSession) {
                block(stub)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is KeyLockedException) throw e
            if (e is CloudException) throw e
            throw CloudException("Error communicating with Cloud Secure Area", e)
        }
    }

    val isRegistered: Boolean
        get() = registrationContext != null

    lateinit var platformSecureArea: SecureArea

    /**
     * Registers with the cloud secure area.
     *
     * @param passphrase the passphrase to use.
     * @param passphraseConstraints the constraints for the passphrase.
     * @param onAuthorizeRootOfTrust called to authorize the Cloud Secure Area's root certificate.
     * @throws CloudException if an error occurred.
     */
    suspend fun register(
        passphrase: String,
        passphraseConstraints: PassphraseConstraints,
        onAuthorizeRootOfTrust: (cloudSecureAreaRootOfTrust: X509Cert) -> Boolean,
    ) {
        try {
            val clientRegistration = ClientRegistrationStub(
                endpoint = "client_registration",
                dispatcher = baseDispatcher,
                notifier = RpcNotifier.SILENT
            )
            val attestationChallenge = clientRegistration.challenge()
            val deviceAttestationResult = DeviceCheck.generateAttestation(
                platformSecureArea,
                attestationChallenge
            )
            val registeredClientId = clientRegistration.register(
                deviceAttestationResult.deviceAttestation
            )
            val newAuthorizedDispatcher = RpcDispatcherAuth(
                base = baseDispatcher,
                rpcAuthIssuer = RpcAuthIssuerAssertion(
                    clientId = registeredClientId,
                    secureArea = platformSecureArea,
                    deviceAttestationId = deviceAttestationResult.deviceAttestationId
                )
            )
            val stub = CloudSecureAreaProtocolStub(
                endpoint = "cloud_secure_area",
                dispatcher = newAuthorizedDispatcher,
                notifier = RpcNotifier.SILENT
            )
            val newSession = RpcAuthClientSession()
            val serverAttestationChain = withContext(newSession) {
                stub.register(passphrase)
            }
            validateServerAttestation(
                serverAttestationChain,
                onAuthorizeRootOfTrust
            )

            // TODO: Need deleteAll() to take a partitionId
            storageTable.enumerate(partitionId = identifier).forEach {
                storageTable.delete(
                    key = it,
                    partitionId = identifier
                )
            }
            keyInfoCacheLock.withLock {
                keyInfoCache.clear()
            }

            clientId = registeredClientId
            deviceAttestationId = deviceAttestationResult.deviceAttestationId
            authorizedDispatcher = newAuthorizedDispatcher
            rpcSession = newSession
            registrationContext = ByteString(stub.toCbor())

            storageTable.insert(
                key = CLIENT_ID,
                data = clientId!!.encodeToByteString(),
                partitionId = identifier,
            )
            storageTable.insert(
                key = DEVICE_ATTESTATION_ID,
                data = deviceAttestationId!!.encodeToByteString(),
                partitionId = identifier,
            )
            storageTable.insert(
                key = REGISTRATION_CONTEXT,
                partitionId = identifier,
                data = registrationContext!!
            )
            storageTable.insert(
                key = PASSPHRASE_CONSTRAINTS,
                partitionId = identifier,
                data = ByteString(passphraseConstraints.toCbor())
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is CloudException) throw e
            throw CloudException(e)
        }
    }

    /**
     * The [PassphraseConstraints] configured at registration time.
     *
     * @return the [PassphraseConstraints] passed to the [register] method.
     * @throws IllegalStateException if not registered with a Cloud Secure Area instance.
     */
    suspend fun getPassphraseConstraints(): PassphraseConstraints {
        val encoded = storageTable.get(key = PASSPHRASE_CONSTRAINTS, partitionId = identifier)
            ?: throw IllegalStateException("Not registered with CSA")
        return PassphraseConstraints.fromCbor(encoded.toByteArray())
    }

    suspend fun unregister() {
        keyInfoCacheLock.withLock {
            keyInfoCache.clear()
        }
        storageTable.delete(key = CLIENT_ID, partitionId = identifier)
        storageTable.delete(key = DEVICE_ATTESTATION_ID, partitionId = identifier)
        storageTable.delete(key = REGISTRATION_CONTEXT, partitionId = identifier)
        storageTable.delete(key = PASSPHRASE_CONSTRAINTS, partitionId = identifier)
        clientId = null
        deviceAttestationId = null
        authorizedDispatcher = null
        registrationContext = null
    }

    private suspend fun validateServerAttestation(
        attestation: X509CertChain,
        onAuthorizeRootOfTrust: (cloudSecureAreaRootOfTrust: X509Cert) -> Boolean,
    ) {
        try {
            attestation.validate()
        } catch (err: X509CertChainValidationException) {
            throw CloudException("Error verifying attestation chain: $err")
        }

        val rootX509Cert = attestation.certificates.last()
        if (!onAuthorizeRootOfTrust(rootX509Cert)) {
            throw CloudException("Root X509Cert not authorized by app")
        }
    }

    private fun toCloudCreateKeySettings(createKeySettings: CreateKeySettings): CloudCreateKeySettings {
        if (createKeySettings is CloudCreateKeySettings) {
            return createKeySettings
        }
        // Use default settings if user passed in a generic SecureArea.CreateKeySettings.
        val builder = CloudCreateKeySettings.Builder(createKeySettings.nonce)
            .setAlgorithm(createKeySettings.algorithm)
            .setUserAuthenticationRequired(
                required = createKeySettings.userAuthenticationRequired,
                types = setOf(
                    CloudUserAuthType.PASSCODE,
                    CloudUserAuthType.BIOMETRIC,
                )
            )
        if (createKeySettings.validFrom != null && createKeySettings.validUntil != null) {
            builder.setValidityPeriod(
                validFrom = createKeySettings.validFrom,
                validUntil = createKeySettings.validUntil
            )
        }
        return builder.build()
    }

    private fun toCloudKeyConfiguration(cSettings: CloudCreateKeySettings): CloudKeyConfiguration {
        // Default for validFrom and validUntil is Jan 1, 1970 to Feb 7, 2106
        val validFrom = cSettings.validFrom ?: Instant.fromEpochMilliseconds(0)
        val validUntil = cSettings.validUntil ?: Instant.fromEpochMilliseconds(Int.MAX_VALUE * 1000L * 2)
        return CloudKeyConfiguration(
            algorithm = cSettings.algorithm,
            validFrom = validFrom,
            validUntil = validUntil,
            passphraseRequired = cSettings.passphraseRequired,
            userAuthenticationRequired = cSettings.userAuthenticationRequired,
            userAuthenticationTypes = cSettings.userAuthenticationTypes,
            challenge = cSettings.attestationChallenge
        )
    }

    override suspend fun createKey(
        alias: String?,
        createKeySettings: CreateKeySettings
    ): CloudKeyInfo {
        if (alias != null) {
            keyInfoCacheLock.withLock {
                keyInfoCache.remove(alias)
            }
            // If the key with the given alias exists, it is silently overwritten.
            storageTable.delete(
                key = alias,
                partitionId = identifier
            )
        }
        val cSettings = toCloudCreateKeySettings(createKeySettings)
        val keyConfiguration = toCloudKeyConfiguration(cSettings)
        return withRpcStub { stub ->
            val cloudChallenge = stub.createKey0(keyConfiguration)
            val newKeyAlias = storageTable.insert(
                key = alias,
                partitionId = identifier,
                data = ByteString()
            )
            val localKeyInfo = platformSecureArea.createKey(
                alias = getLocalKeyAlias(newKeyAlias),
                createKeySettings = cloudSecureAreaGetPlatformSecureAreaCreateKeySettings(
                    challenge = cloudChallenge,
                    algorithm = Algorithm.ESP256,
                    userAuthenticationRequired = cSettings.userAuthenticationRequired,
                    userAuthenticationTypes = cSettings.userAuthenticationTypes
                )
            )
            val response1 = stub.createKey1(localKeyInfo.attestation)
            storageTable.update(
                key = newKeyAlias,
                partitionId = identifier,
                data = response1.serverState
            )
            saveKeyMetadata(newKeyAlias, cSettings, response1.remoteKeyAttestation)
            getKeyInfo(newKeyAlias)
        }
    }

    override suspend fun batchCreateKey(
        numKeys: Int,
        createKeySettings: CreateKeySettings
    ): BatchCreateKeyResult {
        val cSettings = toCloudCreateKeySettings(createKeySettings)
        val keyConfiguration = toCloudKeyConfiguration(cSettings)
        return withRpcStub { stub ->
            val cloudChallenge = stub.batchCreateKey0(keyConfiguration)
            val newKeyAliases = mutableListOf<String>()
            val localKeyAttestations = mutableListOf<KeyAttestation>()
            repeat(numKeys) {
                val newKeyAlias = storageTable.insert(
                    key = null,
                    partitionId = identifier,
                    data = ByteString()
                )
                val localKeyInfo = platformSecureArea.createKey(
                    alias = getLocalKeyAlias(newKeyAlias),
                    createKeySettings = cloudSecureAreaGetPlatformSecureAreaCreateKeySettings(
                        challenge = cloudChallenge,
                        algorithm = Algorithm.ESP256,
                        userAuthenticationRequired = cSettings.userAuthenticationRequired,
                        userAuthenticationTypes = cSettings.userAuthenticationTypes
                    )
                )
                localKeyAttestations.add(localKeyInfo.attestation)
                newKeyAliases.add(newKeyAlias)
            }
            val response1 = stub.batchCreateKey1(localKeyAttestations)
            val keyInfos = mutableListOf<KeyInfo>()
            for ((n, newKeyAlias) in newKeyAliases.withIndex()) {
                storageTable.update(
                    key = newKeyAlias,
                    partitionId = identifier,
                    data = response1.serverStates[n]
                )
                val newKeyAttestation = X509CertChain(
                    listOf(response1.remoteKeyAttestationLeafs[n]) + response1.commonCertChain.certificates
                )
                saveKeyMetadata(newKeyAlias, cSettings, newKeyAttestation)
                keyInfos.add(getKeyInfo(newKeyAlias))
            }
            BatchCreateKeyResult(
                keyInfos = keyInfos,
                openid4vciKeyAttestationJws = response1.openid4vciKeyAttestationCompactSerialization
            )
        }
    }

    override suspend fun deleteKey(alias: String) {
        keyInfoCacheLock.withLock {
            keyInfoCache.remove(alias)
        }
        platformSecureArea.deleteKey(getLocalKeyAlias(alias))
        storageTable.delete(
            key = alias,
            partitionId = identifier
        )
        storageTable.delete(
            key = METADATA_PREFIX + alias,
            partitionId = identifier
        )
    }

    private suspend fun <T> interactionHelper(
        alias: String,
        unlockReason: Reason,
        op: suspend (unlockData: KeyUnlockData?) -> T,
    ): T {
        return try {
            op.invoke(null)
        } catch (e: CloudKeyLockedException) {
            when (e.reason) {
                CloudKeyLockedException.Reason.WRONG_PASSPHRASE -> {
                    val unlockDataProvider = currentCoroutineContext()[KeyUnlockDataProvider.Key]
                        ?: DefaultKeyUnlockDataProvider
                    // DefaultKeyUnlockDataProvider.getKeyUnlockData checks passphrase/PIN before
                    // returning it here, so retry loop is internal to the provider. We expect
                    // other providers to do the same, as we do not want to subject non-interactive
                    // providers to handle repeated calls when unlock data fails to unlock the key.
                    val unlockData = unlockDataProvider.getKeyUnlockData(
                        secureArea = this,
                        alias = alias,
                        algorithm = getKeyInfo(alias).algorithm,
                        unlockReason = unlockReason
                    )
                    op.invoke(unlockData)
                }
                CloudKeyLockedException.Reason.USER_NOT_AUTHENTICATED -> {
                    // This should never happen here...
                    throw IllegalStateException("Unexpected reason USER_NOT_AUTHENTICATED")
                }
            }
        }
    }

    override suspend fun sign(
        alias: String,
        dataToSign: ByteArray,
        unlockReason: Reason
    ): Signature {
        return interactionHelper(
            alias,
            unlockReason,
            op = { unlockData -> signNonInteractive(alias, dataToSign, unlockData) }
        )
    }

    private suspend fun signNonInteractive(
        alias: String,
        dataToSign: ByteArray,
        keyUnlockData: KeyUnlockData?
    ): Signature {
        val keyContext = storageTable.get(
            key = alias,
            partitionId = identifier
        ) ?: throw IllegalArgumentException("No key with given alias")

        // Throw if passphrase is required and not passed in.
        val keyInfo = getKeyInfo(alias)
        if (keyInfo.isPassphraseRequired) {
            if (keyUnlockData == null || (keyUnlockData as CloudKeyUnlockData).passphrase == null) {
                throw CloudKeyLockedException(
                    CloudKeyLockedException.Reason.WRONG_PASSPHRASE,
                    "No passphrase supplied"
                )
            }
        }

        return withRpcStub { stub ->
            val cloudNonce = stub.sign0(ByteString(dataToSign), keyContext)
            val dataToSignLocally = Cbor.encode(
                buildCborArray {
                    add(cloudNonce.toByteArray())
                }
            )
            val signatureLocal = platformSecureArea.sign(
                alias = getLocalKeyAlias(alias),
                dataToSign = dataToSignLocally,
            ) as EcSignature
            val passphrase = (keyUnlockData as? CloudKeyUnlockData)?.passphrase
            var resultingSignature: Signature? = null
            do {
                var tryAgain = false
                try {
                    resultingSignature = stub.sign1(signatureLocal, passphrase)
                } catch (_: CloudWrongPassphraseException) {
                    throw CloudKeyLockedException(
                        CloudKeyLockedException.Reason.WRONG_PASSPHRASE,
                        "Wrong passphrase supplied"
                    )
                } catch (e: CloudTooManyPassphraseAttemptsException) {
                    delayForBruteforceMitigation(e.waitDuration)
                    tryAgain = true
                }
            } while (tryAgain)
            resultingSignature!!
        }
    }

    override suspend fun keyAgreement(
        alias: String,
        otherKey: EcPublicKey,
        unlockReason: Reason
    ): SecureByteString {
        return interactionHelper(
            alias,
            unlockReason,
            op = { unlockData -> keyAgreementNonInteractive(alias, otherKey, unlockData) }
        )
    }

    private suspend fun keyAgreementNonInteractive(
        alias: String,
        otherKey: EcPublicKey,
        keyUnlockData: KeyUnlockData?
    ): SecureByteString {
        val keyContext = storageTable.get(key = alias, partitionId = identifier)
            ?: throw IllegalArgumentException("No key with given alias")

        // Throw if passphrase is required and not passed in.
        val keyInfo = getKeyInfo(alias)
        if (keyInfo.isPassphraseRequired) {
            if (keyUnlockData == null || (keyUnlockData as CloudKeyUnlockData).passphrase == null) {
                throw CloudKeyLockedException(
                    CloudKeyLockedException.Reason.WRONG_PASSPHRASE,
                    "No passphrase supplied"
                )
            }
        }

        return withRpcStub { stub ->
            val cloudNonce = stub.keyAgreement0(otherKey, keyContext)
            val dataToSignLocally = Cbor.encode(
                buildCborArray {
                    add(cloudNonce.toByteArray())
                }
            )
            val signatureLocal = platformSecureArea.sign(
                alias = getLocalKeyAlias(alias),
                dataToSign = dataToSignLocally,
            ) as EcSignature
            val passphrase = (keyUnlockData as? CloudKeyUnlockData)?.passphrase
            var zab: ByteString? = null
            do {
                var tryAgain = false
                try {
                    zab = stub.keyAgreement1(signatureLocal, passphrase)
                } catch (_: CloudWrongPassphraseException) {
                    throw CloudKeyLockedException(
                        CloudKeyLockedException.Reason.WRONG_PASSPHRASE,
                        "Wrong passphrase supplied"
                    )
                } catch (e: CloudTooManyPassphraseAttemptsException) {
                    delayForBruteforceMitigation(e.waitDuration)
                    tryAgain = true
                }
            } while (tryAgain)
            val rawZab = zab!!.toByteArray()
            try {
                SecureByteString(rawZab)
            } finally {
                rawZab.secureZero()
            }
        }
    }

    override suspend fun kemDecapsulate(
        alias: String,
        ciphertext: ByteArray,
        unlockReason: Reason
    ): SecureByteString {
        return interactionHelper(
            alias,
            unlockReason,
            op = { unlockData -> kemDecapsulateNonInteractive(alias, ciphertext, unlockData) }
        )
    }

    private suspend fun kemDecapsulateNonInteractive(
        alias: String,
        ciphertext: ByteArray,
        keyUnlockData: KeyUnlockData?
    ): SecureByteString {
        val keyContext = storageTable.get(key = alias, partitionId = identifier)
            ?: throw IllegalArgumentException("No key with given alias")

        // Throw if passphrase is required and not passed in.
        val keyInfo = getKeyInfo(alias)
        if (keyInfo.isPassphraseRequired) {
            if (keyUnlockData == null || (keyUnlockData as CloudKeyUnlockData).passphrase == null) {
                throw CloudKeyLockedException(
                    CloudKeyLockedException.Reason.WRONG_PASSPHRASE,
                    "No passphrase supplied"
                )
            }
        }

        return withRpcStub { stub ->
            val cloudNonce = stub.kemDecapsulate0(ByteString(ciphertext), keyContext)
            val dataToSignLocally = Cbor.encode(
                buildCborArray {
                    add(cloudNonce.toByteArray())
                }
            )
            val signatureLocal = platformSecureArea.sign(
                alias = getLocalKeyAlias(alias),
                dataToSign = dataToSignLocally,
            ) as EcSignature
            val passphrase = (keyUnlockData as? CloudKeyUnlockData)?.passphrase
            var sharedSecret: ByteString? = null
            do {
                var tryAgain = false
                try {
                    sharedSecret = stub.kemDecapsulate1(signatureLocal, passphrase)
                } catch (_: CloudWrongPassphraseException) {
                    throw CloudKeyLockedException(
                        CloudKeyLockedException.Reason.WRONG_PASSPHRASE,
                        "Wrong passphrase supplied"
                    )
                } catch (e: CloudTooManyPassphraseAttemptsException) {
                    delayForBruteforceMitigation(e.waitDuration)
                    tryAgain = true
                }
            } while (tryAgain)
            val rawSecret = sharedSecret!!.toByteArray()
            try {
                SecureByteString(rawSecret)
            } finally {
                rawSecret.secureZero()
            }
        }
    }

    private suspend fun evaluatePassphrase(
        passphrase: String,
    ): PassphraseEvaluation {
        return withRpcStub { stub ->
            try {
                stub.checkPassphrase(passphrase)
                PassphraseEvaluation.OK
            } catch (_: CloudWrongPassphraseException) {
                PassphraseEvaluation.TryAgain
            } catch (_: CloudTooManyPassphraseAttemptsException) {
                PassphraseEvaluation.TooManyAttempts
            }
        }
    }

    private fun buildCloudKeyInfo(alias: String, keyMetadata: KeyMetadata): CloudKeyInfo {
        val userAuthenticationTypes = CloudUserAuthType.decodeSet(keyMetadata.userAuthenticationTypes)
        return CloudKeyInfo(
            alias,
            KeyAttestation(
                keyMetadata.attestationCertChain.certificates[0].publicKey,
                keyMetadata.attestationCertChain
            ),
            keyMetadata.algorithm,
            keyMetadata.userAuthenticationRequired,
            userAuthenticationTypes,
            keyMetadata.validFrom?.let { Instant.fromEpochMilliseconds(it) },
            keyMetadata.validUntil?.let { Instant.fromEpochMilliseconds(it) },
            keyMetadata.isPassphraseRequired,
        )
    }

    override suspend fun getKeyInfo(alias: String): CloudKeyInfo {
        keyInfoCacheLock.withLock {
            keyInfoCache[alias]?.let { return it }
        }
        val data = storageTable.get(key = METADATA_PREFIX + alias, partitionId = identifier)
            ?: throw IllegalArgumentException("No key with given alias")
        val keyMetadata = KeyMetadata.fromCbor(data.toByteArray())
        val keyInfo = buildCloudKeyInfo(alias, keyMetadata)
        keyInfoCacheLock.withLock {
            keyInfoCache[alias] = keyInfo
        }
        return keyInfo
    }

    override suspend fun getKeyInvalidated(alias: String): Boolean {
        return platformSecureArea.getKeyInvalidated(getLocalKeyAlias(alias))
    }

    override suspend fun unlockKey(
        alias: String,
        unlockReason: Reason
    ): List<KeyUnlockData> {
        val list = mutableListOf<KeyUnlockData>()
        val keyInfo = getKeyInfo(alias)
        if (keyInfo.isPassphraseRequired) {
            val unlockDataProvider = currentCoroutineContext()[KeyUnlockDataProvider.Key]
                ?: DefaultKeyUnlockDataProvider
            val cloudUnlockData = unlockDataProvider.getKeyUnlockData(
                secureArea = this,
                alias = alias,
                algorithm = keyInfo.algorithm,
                unlockReason = unlockReason
            )
            list.add(cloudUnlockData)
        }
        val platformUnlockDataList = platformSecureArea.unlockKey(
            alias = getLocalKeyAlias(alias),
            unlockReason = unlockReason
        )
        list.addAll(platformUnlockDataList)
        return list
    }

    private suspend fun saveKeyMetadata(
        alias: String,
        settings: CloudCreateKeySettings,
        attestationCertChain: X509CertChain
    ) {
        Logger.d(TAG, "attestation len = ${attestationCertChain.certificates.size}")
        val keyMetadata = KeyMetadata(
            algorithm = settings.algorithm,
            userAuthenticationRequired = settings.userAuthenticationRequired,
            userAuthenticationTypes = CloudUserAuthType.encodeSet(settings.userAuthenticationTypes),
            validFrom = settings.validFrom?.toEpochMilliseconds(),
            validUntil = settings.validUntil?.toEpochMilliseconds(),
            isPassphraseRequired = settings.passphraseRequired,
            attestationCertChain = attestationCertChain
        )
        // If the key with the given alias exists, it is silently overwritten.
        storageTable.delete(
            key = METADATA_PREFIX + alias,
            partitionId = identifier
        )
        storageTable.insert(
            key = METADATA_PREFIX + alias,
            partitionId = identifier,
            data = ByteString(keyMetadata.toCbor())
        )
        val keyInfo = buildCloudKeyInfo(alias, keyMetadata)
        keyInfoCacheLock.withLock {
            keyInfoCache[alias] = keyInfo
        }
    }

    internal fun getLocalKeyAlias(alias: String): String {
        return "${identifier}_alias_${alias}"
    }

    @CborSerializable(
        schemaHash = "RUORjIQR7WTnW3b6i6-XRujKbW_WniY8QgUXGF8FyUs"
    )
    internal data class KeyMetadata(
        val algorithm: Algorithm,
        val userAuthenticationRequired: Boolean,
        val userAuthenticationTypes: Long,
        val validFrom: Long?,
        val validUntil: Long?,
        val isPassphraseRequired: Boolean,
        val attestationCertChain: X509CertChain
    ) {
        companion object
    }

    private object DefaultKeyUnlockDataProvider : KeyUnlockDataProvider {
        override suspend fun getKeyUnlockData(
            secureArea: SecureArea,
            alias: String,
            algorithm: Algorithm,
            unlockReason: Reason
        ): KeyUnlockData {
            check(secureArea is CloudSecureArea)
            val unlockData = CloudKeyUnlockData(secureArea, alias)
            val promptModel = try {
                PromptModel.get()
            } catch (_: PromptModelNotAvailableException) {
                throw KeyLockedException("Key is locked and PromptModel is not available to unlock interactively")
            }
            val passphraseConstraints = secureArea.getPassphraseConstraints()
            unlockData.passphrase = try {
                promptModel.requestPassphrase(
                    reason = unlockReason,
                    passphraseConstraints = passphraseConstraints,
                    passphraseEvaluator = { enteredPassphrase: String ->
                        secureArea.evaluatePassphrase(enteredPassphrase)
                    }
                )
            } catch (_: PromptDismissedException) {
                throw KeyLockedException("User canceled passphrase prompt")
            }
            return unlockData
        }
    }

    companion object {
        const val IDENTIFIER_PREFIX = "CloudSecureArea"

        private const val TAG = "CloudSecureArea"

        // Special keys in storage
        private const val CLIENT_ID = "[ClientId]"
        private const val DEVICE_ATTESTATION_ID = "[DeviceAttestationId]"
        private const val REGISTRATION_CONTEXT = "[RegistrationContext]"
        private const val PASSPHRASE_CONSTRAINTS = "[PassphraseConstraints]"

        // Prefix for metadata storage key
        private const val METADATA_PREFIX = "@"

        // Prefix to use for the partition for the platform-specific SecureArea used for local keys.
        private const val PLATFORM_SECURE_AREA_IDENTIFIER_PREFIX = "CloudSecureArea_"

        /**
         * Creates an instance of [CloudSecureArea] using default [Crypto.secureRandom].
         *
         * The given [identifier] must start with `CloudSecureArea` and if the application is only using
         * a single instance of [CloudSecureArea], just using this string as the identifier is fine.
         *
         * For applications using multiple instances of [CloudSecureArea], a common format for the
         * identifier is `CloudSecureArea?id=1234567890&url=https://csa.example.com/csa-server` where the
         * `id` parameter is a unique identifier for the document and the `url` parameter
         * contains the URL to connect to.
         *
         * The [identifier] is used as a prefix for the Android Keystore alias space and also as a prefix
         * for all keys used in the passed-in [storageTable]. As such, it's safe to use the same
         * [StorageEngine] instance for multiple [CloudSecureArea] instances.
         *
         * @param storage the storage engine to use for storing key material.
         * @param identifier an identifier for the Cloud Secure Area.
         * @param serverUrl the URL the Cloud Secure Area is using.
         * @param httpClientEngineFactory the factory for creating the Ktor HTTP client engine (e.g. CIO)
         */
        suspend fun create(
            storage: Storage,
            identifier: String,
            serverUrl: String,
            httpClientEngineFactory: HttpClientEngineFactory<*>
        ): CloudSecureArea = create(
            storage = storage,
            identifier = identifier,
            serverUrl = serverUrl,
            httpClientEngineFactory = httpClientEngineFactory,
            random = Crypto.secureRandom
        )

        /**
         * Creates an instance of CloudSecureArea.
         *
         * @param storage the storage engine to use for storing key material.
         * @param identifier an identifier for the Cloud Secure Area.
         * @param serverUrl the URL the Cloud Secure Area is using.
         * @param httpClientEngineFactory the factory for creating the Ktor HTTP client engine (e.g. CIO)
         * @param random the random provider to use.
         */
        suspend fun create(
            storage: Storage,
            identifier: String,
            serverUrl: String,
            httpClientEngineFactory: HttpClientEngineFactory<*>,
            random: Random
        ): CloudSecureArea {
            val secureArea = CloudSecureArea(
                storage.getTable(tableSpec),
                identifier,
                serverUrl,
                httpClientEngineFactory,
                random
            )
            secureArea.initialize()
            return secureArea
        }

        private val tableSpec = StorageTableSpec(
            name = "CloudSecureArea",
            supportPartitions = true,
            supportExpiration = false
        )
    }
}
