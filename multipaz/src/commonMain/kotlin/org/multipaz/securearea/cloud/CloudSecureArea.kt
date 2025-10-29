package org.multipaz.securearea.cloud

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.asn1.OID
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.cbor.buildCborArray
import org.multipaz.cose.CoseKey
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.SignatureVerificationException
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.device.AssertionNonce
import org.multipaz.device.DeviceCheck
import org.multipaz.prompt.requestPassphrase
import org.multipaz.securearea.BatchCreateKeyResult
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.KeyInfo
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.BatchCreateKeyRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.BatchCreateKeyRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.BatchCreateKeyResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.BatchCreateKeyResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.CreateKeyRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.CreateKeyRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.CreateKeyResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.CreateKeyResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EERequest
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EEResponse
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EESetupRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EESetupRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EESetupResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.E2EESetupResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.KeyAgreementRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.KeyAgreementRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.KeyAgreementResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.KeyAgreementResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.RegisterResponse1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.SignRequest0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.SignRequest1
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.SignResponse0
import org.multipaz.securearea.cloud.CloudSecureAreaProtocol.SignResponse1
import org.multipaz.securearea.fromCbor
import org.multipaz.securearea.toCbor
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageEngine
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.util.appendUInt32
import org.multipaz.certext.MultipazExtension
import org.multipaz.certext.fromCbor
import org.multipaz.crypto.Hkdf
import org.multipaz.prompt.PassphraseEvaluation
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.UnlockReason
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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


/**
 * An implementation of [SecureArea] using a Secure Area managed by an external server.
 */
open class CloudSecureArea protected constructor(
    private val storageTable: StorageTable,
    final override val identifier: String,
    val serverUrl: String,
    httpClientEngineFactory: HttpClientEngineFactory<*>
) : SecureArea {
    private var skDevice: ByteArray? = null
    private var skCloud: ByteArray? = null
    private var e2eeContext: ByteArray? = null
    private var encryptedCounter = 0
    private var decryptedCounter = 0

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
            it.fullySpecified && it.curve != null
        }
    }

    override val supportedAlgorithms: List<Algorithm>
        get() = supportedAlgorithms_

    private val httpClient = HttpClient(httpClientEngineFactory) {
        install(HttpTimeout)
    }

    private var deviceAttestationId: String? = null
    private var cloudBindingKey: EcPublicKey? = null
    private var registrationContext: ByteString? = null

    protected open suspend fun initialize() {
        require(identifier.startsWith(IDENTIFIER_PREFIX))
        deviceAttestationId = storageTable.get(DEVICE_ATTESTATION_ID, identifier)?.decodeToString()
        storageTable.get(BINDING_KEY, identifier)?.let { bindingKeyData ->
            cloudBindingKey = Cbor.decode(bindingKeyData.toByteArray()).asCoseKey.ecPublicKey
        }
        registrationContext = storageTable.get(REGISTRATION_CONTEXT, identifier)
        platformSecureArea = cloudSecureAreaGetPlatformSecureArea(
            storage = storageTable.storage,
            partitionId = PLATFORM_SECURE_AREA_IDENTIFIER_PREFIX + identifier
        )
    }

    open suspend fun communicateLowlevel(
        endpointUri: String,
        requestData: ByteArray
    ): Pair<Int, ByteArray> {
        try {
            val httpResponse = httpClient.post(endpointUri) {
                contentType(ContentType.Application.Cbor)
                accept(ContentType.Application.Cbor)
                timeout {
                    requestTimeoutMillis = 10000
                }
                setBody(requestData)
            }
            val responseStatus = httpResponse.status.value
            val responseBody: ByteArray = httpResponse.body()
            return Pair(responseStatus, responseBody)
        } catch (e: Exception) {
            throw CloudException("Error communicating with Cloud Secure Area", e)
        }
    }

    private suspend fun communicate(
        endpointUri: String,
        requestData: ByteArray
    ): ByteArray {
        val (status, data) = communicateLowlevel(endpointUri, requestData)
        if (status != HttpStatusCode.OK.value) {
            throw CloudException("Status $status: ${data.decodeToString()}")
        }
        return data
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
        var response: ByteArray
        try {
            val request0 = RegisterRequest0("1.0")
            response = communicate(serverUrl, request0.toCbor())
            val response0 = CloudSecureAreaProtocol.Command.fromCbor(response) as RegisterResponse0
            val deviceBindingKeyAlias = "DeviceBindingKey"

            val deviceBindingKeyInfo = platformSecureArea.createKey(deviceBindingKeyAlias,
                cloudSecureAreaGetPlatformSecureAreaCreateKeySettings(
                    challenge = ByteString(response0.cloudChallenge),
                    algorithm = Algorithm.ESP256,
                    userAuthenticationRequired = false,
                    userAuthenticationTypes = setOf()
                )
            )
            val deviceChallenge = Random.Default.nextBytes(32)

            val deviceAttestationResult = DeviceCheck.generateAttestation(
                platformSecureArea,
                response0.attestationChallenge
            )

            val request1 = RegisterRequest1(
                deviceChallenge = deviceChallenge,
                deviceAttestation = deviceAttestationResult.deviceAttestation,
                deviceBindingKey = deviceBindingKeyInfo.publicKey.toCoseKey(),
                deviceBindingKeyAttestation = deviceBindingKeyInfo.attestation.certChain,
                serverState = response0.serverState
            )

            response = communicate(serverUrl, request1.toCbor())
            val response1 = CloudSecureAreaProtocol.Command.fromCbor(response) as RegisterResponse1
            validateCloudBindingKeyAttestation(
                response1.cloudBindingKeyAttestation,
                deviceChallenge,
                onAuthorizeRootOfTrust,
            )

            // TODO: Need deleteAll() to take a partitionId
            storageTable.enumerate(partitionId = identifier).forEach {
                storageTable.delete(
                    key = it,
                    partitionId = identifier
                )
            }

            // Ready to go...
            deviceAttestationId = deviceAttestationResult.deviceAttestationId
            cloudBindingKey = response1.cloudBindingKeyAttestation.certificates[0].ecPublicKey
            registrationContext = ByteString(response1.serverState)

            // ... and save for future use
            storageTable.insert(
                key = DEVICE_ATTESTATION_ID,
                data = deviceAttestationId!!.encodeToByteString(),
                partitionId = identifier,
            )
            storageTable.insert(
                key = BINDING_KEY,
                partitionId = identifier,
                data = ByteString(Cbor.encode(cloudBindingKey!!.toCoseKey().toDataItem()))
            )
            storageTable.insert(
                key = REGISTRATION_CONTEXT,
                partitionId = identifier,
                data = registrationContext!!
            )

            // We need E2EE before entering the second stage of registration - this is because we
            // need the data exchanged in this stage (e.g. the passphrase) to be encrypted so only
            // the Secure Enclave on the server side can see it.
            setupE2EE(false)

            // Note that the second registration phase modifies the registrationContext so we need
            // to update it once the operation completes.
            val stage2Request0 = CloudSecureAreaProtocol.RegisterStage2Request0(passphrase)
            val stage2Response0 =
                CloudSecureAreaProtocol.Command.fromCbor(communicateE2EE(stage2Request0.toCbor()))
                        as CloudSecureAreaProtocol.RegisterStage2Response0
            registrationContext = ByteString(stage2Response0.serverState)
            storageTable.update(
                key = REGISTRATION_CONTEXT,
                partitionId = identifier,
                data = registrationContext!!
            )
            storageTable.insert(
                key = PASSPHRASE_CONSTRAINTS,
                partitionId = identifier,
                data = ByteString(passphraseConstraints.toCbor())
            )
        } catch (e: Throwable) {
            throw CloudException(e)
        }
    }

    /**
     * The [PassphraseConstraints] configured at registration time.
     *
     * @return the [PassphraseConstraints] passed to to the [register] method.
     * @throws IllegalStateException if not registered with a Cloud Secure Area instance.
     */
    suspend fun getPassphraseConstraints(): PassphraseConstraints {
        val encoded = storageTable.get(key = PASSPHRASE_CONSTRAINTS, partitionId = identifier)
            ?: throw IllegalStateException("Not registered with CSA")
        return PassphraseConstraints.fromCbor(encoded.toByteArray())
    }

    suspend fun unregister() {
        // TODO: should we send a RPC to the server to let them know we're bailing?
        storageTable.delete(key = DEVICE_ATTESTATION_ID, partitionId = identifier)
        storageTable.delete(key = BINDING_KEY, partitionId = identifier)
        storageTable.delete(key = REGISTRATION_CONTEXT, partitionId = identifier)
        storageTable.delete(key = PASSPHRASE_CONSTRAINTS, partitionId = identifier)
        deviceAttestationId = null
        cloudBindingKey = null
        registrationContext = null
        skDevice = null
    }

    private fun validateCloudBindingKeyAttestation(
        attestation: X509CertChain,
        expectedDeviceChallenge: ByteArray,
        onAuthorizeRootOfTrust: (cloudSecureAreaRootOfTrust: X509Cert) -> Boolean,
    ) {
        if (!attestation.validate()) {
            throw CloudException("Error verifying attestation chain")
        }

        val rootX509Cert = attestation.certificates.last()
        if (!onAuthorizeRootOfTrust(rootX509Cert)) {
            throw CloudException("Root X509Cert not authorized by app")
        }

        val decodedAttestation = MultipazExtension.fromCbor(
            attestation.certificates[0]
                .getExtensionValue(OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid)!!
        )
        check(decodedAttestation.cloudKeyAttestation != null &&
                decodedAttestation.cloudKeyAttestation.challenge == ByteString(expectedDeviceChallenge)) {
            "Challenge in attestation does match what's expected"
        }
    }

    private suspend fun setupE2EE(forceSetup: Boolean) {
        // No need to setup E2EE if it's already up...
        if (!forceSetup && skDevice != null) {
            return
        }
        var response: ByteArray
        try {
            val request0 = E2EESetupRequest0(registrationContext!!.toByteArray())
            response = communicate(serverUrl, request0.toCbor())
            val response0 = CloudSecureAreaProtocol.Command.fromCbor(response) as E2EESetupResponse0
            val deviceNonce = Random.Default.nextBytes(32)
            val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val dataToSign = Cbor.encode(
                buildCborArray {
                    add(eDeviceKey.publicKey.toCoseKey().toDataItem())
                    add(response0.cloudNonce)
                    add(deviceNonce)
                }
            )
            val signature = platformSecureArea.sign(
                "DeviceBindingKey",
                dataToSign
            )
            val deviceAssertion = DeviceCheck.generateAssertion(
                secureArea = platformSecureArea,
                deviceAttestationId = deviceAttestationId!!,
                assertion = AssertionNonce(ByteString(response0.cloudNonce))
            )
            val request1 = E2EESetupRequest1(
                eDeviceKey = eDeviceKey.publicKey.toCoseKey(),
                deviceNonce = deviceNonce,
                signature = signature,
                deviceAssertion = deviceAssertion,
                serverState = response0.serverState
            )
            response = communicate(serverUrl, request1.toCbor())
            val response1 = CloudSecureAreaProtocol.Command.fromCbor(response) as E2EESetupResponse1
            val dataSignedByTheCloud = Cbor.encode(
                buildCborArray {
                    add(response1.eCloudKey.toDataItem())
                    add(response0.cloudNonce)
                    add(deviceNonce)
                }
            )
            try {
                Crypto.checkSignature(
                    cloudBindingKey!!,
                    dataSignedByTheCloud,
                    Algorithm.ES256,
                    response1.signature
                )
            } catch(e: SignatureVerificationException) {
                throw CloudException("Error verifying signature", e)
            }

            // Now we can derive SKDevice and SKCloud
            val zab = Crypto.keyAgreement(eDeviceKey, response1.eCloudKey.ecPublicKey)
            val salt = Crypto.digest(
                Algorithm.SHA256,
                Cbor.encode(
                    buildCborArray {
                        add(deviceNonce)
                        add(response0.cloudNonce)
                    }
                )
            )
            skDevice = Hkdf.deriveKey(
                Algorithm.HMAC_SHA256,
                zab,
                salt,
                "SKDevice".encodeToByteArray(),
                32
            )
            skCloud = Hkdf.deriveKey(
                Algorithm.HMAC_SHA256,
                zab,
                salt,
                "SKCloud".encodeToByteArray(),
                32
            )
            e2eeContext = response1.serverState
            encryptedCounter = 1
            decryptedCounter = 1
        } catch (e: Exception) {
            throw CloudException(e)
        }
    }

    private fun encryptToCloud(messagePlaintext: ByteArray): ByteArray {
        // The IV and these constants are specified in ISO/IEC 18013-5:2021 clause 9.1.1.5.
        val iv = buildByteString {
            appendUInt32(0x00000000)
            val ivIdentifier = 0x00000001
            appendUInt32(ivIdentifier)
            appendUInt32(encryptedCounter++)
        }.toByteArray()
        return Crypto.encrypt(Algorithm.A128GCM, skDevice!!, iv, messagePlaintext)
    }

    private fun decryptFromCloud(messageCiphertext: ByteArray): ByteArray {
        val iv = buildByteString {
            appendUInt32(0x00000000)
            val ivIdentifier = 0x00000000
            appendUInt32(ivIdentifier)
            appendUInt32(decryptedCounter++)
        }.toByteArray()
        return Crypto.decrypt(Algorithm.A128GCM, skCloud!!, iv, messageCiphertext)
    }

    // This is internal rather than private b/c it's used in testPassphraseCannotBeChanged()
    internal suspend fun communicateE2EE(requestData: ByteArray): ByteArray {
        return communicateE2EEInternal(requestData, 0)
    }

    private suspend fun communicateE2EEInternal(
        requestData: ByteArray,
        callDepth: Int
    ): ByteArray {
        val requestWrapper = E2EERequest(
            encryptToCloud(requestData),
            e2eeContext!!
        )
        val (httpResponseCode, response) = communicateLowlevel(serverUrl, requestWrapper.toCbor())

        if (httpResponseCode == HttpStatusCode.BadRequest.value) {
            // This status code means that decryption failed
            if (callDepth > 10) {
                throw CloudException("Error reinitializing E2EE after $callDepth attempts")
            }
            Logger.d(TAG, "Received status 400, reinitializing E2EE")
            setupE2EE(true)
            return communicateE2EEInternal(requestData, callDepth + 1)
        } else if (httpResponseCode != HttpStatusCode.OK.value) {
            throw CloudException("Excepted status code 200 or 400, got $httpResponseCode")
        }
        val responseWrapper = CloudSecureAreaProtocol.Command.fromCbor(response) as E2EEResponse
        e2eeContext = responseWrapper.e2eeContext
        return decryptFromCloud(responseWrapper.encryptedResponse)
    }

    override suspend fun createKey(
        alias: String?,
        createKeySettings: CreateKeySettings
    ): CloudKeyInfo {
        if (alias != null) {
            // If the key with the given alias exists, it is silently overwritten.
            storageTable.delete(
                key = alias,
                partitionId = identifier
            )
        }
        val cSettings = if (createKeySettings is CloudCreateKeySettings) {
            createKeySettings
        } else {
            // Use default settings if user passed in a generic SecureArea.CreateKeySettings.
            val builder = CloudCreateKeySettings.Builder(createKeySettings.nonce)
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
            builder.build()
        }
        setupE2EE(false)
        try {
            // Default for validFrom and validUntil is Jan 1, 1970 to Feb 7, 2106
            var validFrom: Long = 0
            var validUntil = Int.MAX_VALUE * 1000L * 2
            if (cSettings.validFrom != null) {
                validFrom = cSettings.validFrom.toEpochMilliseconds()
            }
            if (cSettings.validUntil != null) {
                validUntil = cSettings.validUntil.toEpochMilliseconds()
            }
            val request0 = CreateKeyRequest0(
                cSettings.algorithm.name,
                validFrom,
                validUntil,
                cSettings.passphraseRequired,
                cSettings.userAuthenticationRequired,
                CloudUserAuthType.encodeSet(cSettings.userAuthenticationTypes),
                cSettings.attestationChallenge.toByteArray()
            )
            val response0 = CloudSecureAreaProtocol.Command.fromCbor(communicateE2EE(request0.toCbor())) as CreateKeyResponse0
            val newKeyAlias = storageTable.insert(
                key = alias,
                partitionId = identifier,
                data = ByteString()
            )
            val localKeyInfo = platformSecureArea.createKey(
                alias = getLocalKeyAlias(newKeyAlias),
                createKeySettings = cloudSecureAreaGetPlatformSecureAreaCreateKeySettings(
                    challenge = ByteString(response0.cloudChallenge),
                    algorithm = Algorithm.ESP256,
                    userAuthenticationRequired = cSettings.userAuthenticationRequired,
                    userAuthenticationTypes = cSettings.userAuthenticationTypes
                )
            )
            val request1 = CreateKeyRequest1(
                localKeyInfo.publicKey.toCoseKey(),
                localKeyInfo.attestation.certChain,
                response0.serverState
            )
            val response1 = CloudSecureAreaProtocol.Command.fromCbor(communicateE2EE(request1.toCbor())) as CreateKeyResponse1
            storageTable.update(
                key = newKeyAlias,
                partitionId = identifier,
                data = ByteString(response1.serverState)
            )
            saveKeyMetadata(newKeyAlias, cSettings, response1.remoteKeyAttestation)
            return getKeyInfo(newKeyAlias)
        } catch (e: Exception) {
            throw CloudException(e)
        }
    }

    override suspend fun batchCreateKey(
        numKeys: Int,
        createKeySettings: CreateKeySettings
    ): BatchCreateKeyResult {
        val cSettings = if (createKeySettings is CloudCreateKeySettings) {
            createKeySettings
        } else {
            // Use default settings if user passed in a generic SecureArea.CreateKeySettings.
            CloudCreateKeySettings.Builder(createKeySettings.nonce)
                .setUserAuthenticationRequired(
                    required = createKeySettings.userAuthenticationRequired,
                    types = setOf(
                        CloudUserAuthType.PASSCODE,
                        CloudUserAuthType.BIOMETRIC,
                    )
                )
                .build()
        }
        setupE2EE(false)
        try {
            // Default for validFrom and validUntil is Jan 1, 1970 to Feb 7, 2106
            var validFrom: Long = 0
            var validUntil = Int.MAX_VALUE * 1000L * 2
            if (cSettings.validFrom != null) {
                validFrom = cSettings.validFrom.toEpochMilliseconds()
            }
            if (cSettings.validUntil != null) {
                validUntil = cSettings.validUntil.toEpochMilliseconds()
            }
            val request0 = BatchCreateKeyRequest0(
                cSettings.algorithm.name,
                validFrom,
                validUntil,
                cSettings.passphraseRequired,
                cSettings.userAuthenticationRequired,
                CloudUserAuthType.encodeSet(cSettings.userAuthenticationTypes),
                cSettings.attestationChallenge.toByteArray()
            )
            val response0 = CloudSecureAreaProtocol.Command.fromCbor(communicateE2EE(request0.toCbor())) as BatchCreateKeyResponse0
            val newKeyAliases = mutableListOf<String>()
            val localKeys = mutableListOf<CoseKey>()
            val localKeyAttestations = mutableListOf<X509CertChain>()
            repeat(numKeys) {
                val newKeyAlias = storageTable.insert(
                    key = null,
                    partitionId = identifier,
                    data = ByteString()
                )
                val localKeyInfo = platformSecureArea.createKey(
                    alias = getLocalKeyAlias(newKeyAlias),
                    createKeySettings = cloudSecureAreaGetPlatformSecureAreaCreateKeySettings(
                        challenge = ByteString(response0.cloudChallenge),
                        algorithm = Algorithm.ESP256,
                        userAuthenticationRequired = cSettings.userAuthenticationRequired,
                        userAuthenticationTypes = cSettings.userAuthenticationTypes
                    )
                )
                localKeys.add(localKeyInfo.publicKey.toCoseKey())
                if (localKeyInfo.attestation.certChain != null) {
                    localKeyAttestations.add(localKeyInfo.attestation.certChain)
                }
                newKeyAliases.add(newKeyAlias)
            }
            val request1 = BatchCreateKeyRequest1(
                localKeys,
                localKeyAttestations,
                response0.serverState
            )
            val response1 = CloudSecureAreaProtocol.Command.fromCbor(communicateE2EE(request1.toCbor())) as BatchCreateKeyResponse1
            val keyInfos = mutableListOf<KeyInfo>()
            var n = 0
            for (newKeyAlias in newKeyAliases) {
                storageTable.update(
                    key = newKeyAlias,
                    partitionId = identifier,
                    data = ByteString(response1.serverStates[n])
                )
                val newKeyAttestation = X509CertChain(
                    listOf(response1.remoteKeyAttestationLeafs[n]) + response1.commonCertChain.certificates
                )
                saveKeyMetadata(newKeyAlias, cSettings, newKeyAttestation)
                keyInfos.add(getKeyInfo(newKeyAlias))
                n++
            }
            return BatchCreateKeyResult(
                keyInfos = keyInfos,
                openid4vciKeyAttestationJws = response1.openid4vciKeyAttestationCompactSerialization
            )
        } catch (e: Exception) {
            throw CloudException(e)
        }
    }

    override suspend fun deleteKey(alias: String) {
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

    private suspend fun<T> interactionHelper(
        alias: String,
        unlockReason: UnlockReason,
        op: suspend (unlockData: KeyUnlockData?) -> T,
    ): T {
        return try {
            op.invoke(null)
        } catch (e: CloudKeyLockedException) {
            when (e.reason) {
                CloudKeyLockedException.Reason.WRONG_PASSPHRASE -> {
                    val unlockDataProvider = coroutineContext[KeyUnlockDataProvider.Key]
                        ?: DefaultKeyUnlockDataProvider
                    // DefaultKeyUnlockDataProvider.getKeyUnlockData checks passphrase/PIN before
                    // returning it here, so retry loop is internal to the provider. We expect
                    // other providers to do the same, as we do not want to subject non-interactive
                    // providers to handle repeated calls when unlock data fails to unlock the key.
                    val unlockData = unlockDataProvider.getKeyUnlockData(
                        secureArea = this,
                        alias = alias,
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
        unlockReason: UnlockReason
    ): EcSignature {
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
    ): EcSignature {
        var resultingSignature: EcSignature? = null
        val keyContext = storageTable.get(
            key = alias,
            partitionId = identifier
        )
        setupE2EE(false)
        var response: ByteArray

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

        val request0 = SignRequest0(dataToSign, keyContext!!.toByteArray())
        response = communicateE2EE(request0.toCbor())
        val response0 = CloudSecureAreaProtocol.Command.fromCbor(response) as SignResponse0
        val dataToSignLocally = Cbor.encode(
            buildCborArray {
                add(response0.cloudNonce)
            }
        )
        val signatureLocal = platformSecureArea.sign(
            alias = getLocalKeyAlias(alias),
            dataToSign = dataToSignLocally,
        )
        val request1 = SignRequest1(
            signatureLocal,
            (keyUnlockData as? CloudKeyUnlockData)?.passphrase,
            response0.serverState
        )
        do {
            var tryAgain = false

            response = communicateE2EE(request1.toCbor())
            val response1 = CloudSecureAreaProtocol.Command.fromCbor(response) as SignResponse1
            when (response1.result) {
                CloudSecureAreaProtocol.RESULT_OK -> {
                    resultingSignature = response1.signature
                }

                CloudSecureAreaProtocol.RESULT_WRONG_PASSPHRASE -> {
                    throw CloudKeyLockedException(
                        CloudKeyLockedException.Reason.WRONG_PASSPHRASE,
                        "Wrong passphrase supplied"
                    )
                }

                CloudSecureAreaProtocol.RESULT_TOO_MANY_PASSPHRASE_ATTEMPTS -> {
                    delayForBruteforceMitigation(response1.waitDurationMillis.milliseconds)
                    tryAgain = true
                }

                else -> throw CloudException("Unexpected result ${response1.result}")
            }
        } while (tryAgain)
        return resultingSignature!!
    }

    override suspend fun keyAgreement(
        alias: String,
        otherKey: EcPublicKey,
        unlockReason: UnlockReason
    ): ByteArray {
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
    ): ByteArray {
        var zab: ByteArray? = null
        val keyContext = storageTable.get(key = alias, partitionId = identifier)
        setupE2EE(false)
        var response: ByteArray

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

        val request0 = KeyAgreementRequest0(otherKey.toCoseKey(), keyContext!!.toByteArray())
        response = communicateE2EE(request0.toCbor())
        val response0 = CloudSecureAreaProtocol.Command.fromCbor(response) as KeyAgreementResponse0
        val dataToSignLocally = Cbor.encode(
            buildCborArray {
                add(response0.cloudNonce)
            }
        )
        val signatureLocal = platformSecureArea.sign(
            alias = getLocalKeyAlias(alias),
            dataToSign = dataToSignLocally,
        )
        val request1 = KeyAgreementRequest1(
            signatureLocal,
            (keyUnlockData as? CloudKeyUnlockData)?.passphrase,
            response0.serverState
        )
        do {
            var tryAgain = false

            response = communicateE2EE(request1.toCbor())
            val response1 = CloudSecureAreaProtocol.Command.fromCbor(response) as KeyAgreementResponse1
            when (response1.result) {
                CloudSecureAreaProtocol.RESULT_OK -> {
                    zab = response1.zab
                }

                CloudSecureAreaProtocol.RESULT_WRONG_PASSPHRASE -> {
                    throw CloudKeyLockedException(
                        CloudKeyLockedException.Reason.WRONG_PASSPHRASE,
                        "Wrong passphrase supplied"
                    )
                }

                CloudSecureAreaProtocol.RESULT_TOO_MANY_PASSPHRASE_ATTEMPTS -> {
                    delayForBruteforceMitigation(response1.waitDurationMillis.milliseconds)
                    tryAgain = true
                }

                else -> throw CloudException("Unexpected result ${response1.result}")
            }
        } while (tryAgain)
        return zab!!
    }

    private suspend fun checkPassphrase(
        passphrase: String,
    ): Int {
        setupE2EE(false)
        val request = CloudSecureAreaProtocol.CheckPassphraseRequest(passphrase)
        val response = communicateE2EE(request.toCbor())
        val commandResponse = CloudSecureAreaProtocol.Command.fromCbor(response) as CloudSecureAreaProtocol.CheckPassphraseResponse
        return commandResponse.result
    }

    override suspend fun getKeyInfo(alias: String): CloudKeyInfo {
        val data = storageTable.get(key = METADATA_PREFIX + alias, partitionId = identifier)
            ?: throw IllegalArgumentException("No key with given alias")
        val keyMetadata = KeyMetadata.fromCbor(data.toByteArray())
        val userAuthenticationTypes = CloudUserAuthType.decodeSet(keyMetadata.userAuthenticationTypes)
        return CloudKeyInfo(
            alias,
            KeyAttestation(
                keyMetadata.attestationCertChain.certificates[0].ecPublicKey,
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

    override suspend fun getKeyInvalidated(alias: String): Boolean {
        return platformSecureArea.getKeyInvalidated(getLocalKeyAlias(alias))
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
    }

    internal fun getLocalKeyAlias(alias: String): String {
        return "${identifier}_alias_${alias}"
    }

    @CborSerializable(
        schemaHash = "w-5iNr7XcEFY2B2L8dT64GO06QiTsDV87YdGHeocruI"
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

    private object DefaultKeyUnlockDataProvider: KeyUnlockDataProvider {
        override suspend fun getKeyUnlockData(
            secureArea: SecureArea,
            alias: String,
            unlockReason: UnlockReason
        ): KeyUnlockData {
            check(secureArea is CloudSecureArea)
            val unlockData = CloudKeyUnlockData(secureArea, alias)
            val promptModel = try {
                PromptModel.get(coroutineContext)
            } catch (_: PromptModelNotAvailableException) {
                throw KeyLockedException("Key is locked and PromptModel is not available to unlock interactively")
            }
            val passphraseConstraints = secureArea.getPassphraseConstraints()
            val humanReadable = promptModel.toHumanReadable(unlockReason, passphraseConstraints)
            unlockData.passphrase = promptModel.requestPassphrase(
                title = humanReadable.title,
                subtitle = humanReadable.subtitle,
                passphraseConstraints = passphraseConstraints,
                passphraseEvaluator = { enteredPassphrase: String ->
                    when (val result = secureArea.checkPassphrase(enteredPassphrase)) {
                        CloudSecureAreaProtocol.RESULT_WRONG_PASSPHRASE ->
                            PassphraseEvaluation.TryAgain

                        CloudSecureAreaProtocol.RESULT_TOO_MANY_PASSPHRASE_ATTEMPTS ->
                            PassphraseEvaluation.TooManyAttempts

                        CloudSecureAreaProtocol.RESULT_OK ->
                            PassphraseEvaluation.OK

                        else -> {
                            Logger.w(TAG, "Unexpected result $result when checking passphrase")
                            PassphraseEvaluation.OK
                        }
                    }
                }
            )
            if (unlockData.passphrase == null) {
                throw KeyLockedException("User canceled passphrase prompt")
            }
            return unlockData
        }
    }

    // TODO: override batchCreateKey and implement server-side command to avoid roundtrips.

    companion object {
        const val IDENTIFIER_PREFIX = "CloudSecureArea"

        private const val TAG = "CloudSecureArea"

        // Special keys in storage
        private const val DEVICE_ATTESTATION_ID = "[DeviceAttestationId]"
        private const val BINDING_KEY = "[BindingKey]"
        private const val REGISTRATION_CONTEXT = "[RegistrationContext]"
        private const val PASSPHRASE_CONSTRAINTS = "[PassphraseConstraints]"

        // Prefix for metadata storage key
        private const val METADATA_PREFIX = "@"

        // Prefix to use for the partition for the platform-specific SecureArea used for local keys.
        //
        private const val PLATFORM_SECURE_AREA_IDENTIFIER_PREFIX = "CloudSecureArea_"

        /**
         * Creates an instance of [CloudSecureArea].
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
        ): CloudSecureArea {
            val secureArea = CloudSecureArea(
                storage.getTable(tableSpec),
                identifier,
                serverUrl,
                httpClientEngineFactory
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
