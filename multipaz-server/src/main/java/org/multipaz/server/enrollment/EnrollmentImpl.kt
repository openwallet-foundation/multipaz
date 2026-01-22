package org.multipaz.server.enrollment

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.rpc.annotation.RpcState
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.RpcAuthError
import org.multipaz.rpc.handler.RpcAuthException
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.rpc.handler.RpcAuthInspectorSignature
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.server.common.baseUrl
import org.multipaz.server.common.getBaseUrl
import org.multipaz.server.common.enrollmentServerUrl
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Eager
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.util.truncateToWholeSeconds
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Implements [Enrollment] interface.
 */
@RpcState(
    endpoint = "enrollment",
    creatable = true
)@CborSerializable
class EnrollmentImpl: Enrollment, RpcAuthInspector by serverAuth {
    override suspend fun resetEnrollmentKey() {
        provisioningServerNextUpdate = Instant.DISTANT_PAST
    }

    override suspend fun request(
        requestId: String?,
        identity: ServerIdentity,
        nonce: ByteString,
        expiration: Instant,
    ): Enrollment.EnrollmentRequest {
        if (requestId != null && enrollmentsMap[identity]?.requestId != requestId) {
            throw InvalidRequestException("Enrollment was not requested")
        }
        val whenToUpdate = expiration - MIN_VALIDITY_DURATION
        if (whenToUpdate < Clock.System.now()) {
            throw InvalidRequestException("Expiration is too soon")
        }
        val secureArea = BackendEnvironment.getInterface(SecureAreaProvider::class)!!.get()
        val keyInfo = secureArea.createKey(
            alias = null,
            createKeySettings = CreateKeySettings(
                nonce = nonce,
                validUntil = whenToUpdate
            )
        )
        val enrollmentInfo = BackendEnvironment.getInterface(Configuration::class)!!
            .getValue("enrollment_info")?.let {
                Json.parseToJsonElement(it).jsonObject
            }
        Logger.i(TAG, "Created private key for $identity: ${keyInfo.alias}")
        return Enrollment.EnrollmentRequest(
            alias = keyInfo.alias,
            url = BackendEnvironment.getBaseUrl(),
            keyAttestation = keyInfo.attestation,
            organization = enrollmentInfo?.get("organization")?.jsonPrimitive?.content,
            organizationalUnit = enrollmentInfo?.get("organizational_unit")?.jsonPrimitive?.content,
            locality = enrollmentInfo?.get("locality")?.jsonPrimitive?.content,
            stateOrProvince = enrollmentInfo?.get("state_or_province")?.jsonPrimitive?.content,
            country = enrollmentInfo?.get("country")?.jsonPrimitive?.content,
        )
    }

    override suspend fun enroll(
        requestId: String?,
        identity: ServerIdentity,
        alias: String,
        certChain: X509CertChain
    ) {
        if (requestId != null && enrollmentsMap[identity]?.requestId != requestId) {
            throw InvalidRequestException("Enrollment was not requested")
        }
        Logger.i(TAG, "Received enrollment for '$identity'")
        val secureArea = BackendEnvironment.getInterface(SecureAreaProvider::class)!!.get()
        // Set up expiration to re-enroll ahead of the certificate expiration
        val expiration = certChain.certificates.first().validityNotAfter - MIN_VALIDITY_DURATION
        if (expiration < Clock.System.now()) {
            throw InvalidRequestException("Certificate expires too soon")
        }
        val signingKey = AsymmetricKey.X509CertifiedSecureAreaBased(
            secureArea = secureArea,
            certChain = certChain,
            keyInfo = secureArea.getKeyInfo(alias)
        )
        enrollmentsMap[identity]?.responseChannel?.apply {
            send(signingKey)
        }
        val data = ByteString(SigningKeyData(
            alias = alias,
            certChain = certChain
        ).toCbor())
        val table = BackendEnvironment.getTable(enrollmentsTable)
        enrollmentsLock.withLock {
            if (table.get(identity.name) != null) {
                table.update(key = identity.name, data = data, expiration = expiration)
            } else {
                table.insert(key = identity.name, data = data, expiration = expiration)
            }
            Logger.i(TAG, "Cached enrollment record for '$identity'")
            enrollmentsMap = buildMap {
                putAll(enrollmentsMap)
                put(identity, ServerIdentityRecord(
                    signingKeyDeferred = Eager(CompletableDeferred(signingKey)),
                    requestId = null,
                    expiration = expiration,
                    responseChannel = null
                ))
            }
        }
    }

    private class ServerIdentityRecord(
        // Lazy deferred seems exotic, but that's what's needed here. We do not want to launch
        // enrollment until ServerIdentityRecord is created and registered.
        var signingKeyDeferred: Lazy<Deferred<AsymmetricKey.X509Certified>>,
        val requestId: String? = null,
        val expiration: Instant? = null,
        val responseChannel: Channel<AsymmetricKey.X509Certified>? = null
    ) {
        companion object {
            fun fromKey(key: AsymmetricKey): ServerIdentityRecord {
                val cert = (key as AsymmetricKey.X509Certified).certChain.certificates.first()
                return ServerIdentityRecord(
                    signingKeyDeferred = Eager(CompletableDeferred(key)),
                    expiration = cert.validityNotAfter - MIN_VALIDITY_DURATION
                )
            }
        }
    }

    companion object {
        private val MIN_VALIDITY_DURATION = 30.days

        private const val TAG = "EnrollmentImpl"

        private val enrollmentsLock = Mutex()
        @Volatile
        private var enrollmentsMap = mapOf<ServerIdentity, ServerIdentityRecord>()

        private val enrollmentsTable = StorageTableSpec(
            name = "Enrollments",
            supportPartitions = false,
            supportExpiration = true
        )

        suspend fun getServerIdentity(
            serverIdentity: ServerIdentity,
        ): Deferred<AsymmetricKey.X509Certified> {
            val record = enrollmentsMap[serverIdentity]
            val validRecord = if (record != null &&
                (record.expiration == null || record.expiration > Clock.System.now())) {
                record
            } else {
                enrollmentsLock.withLock {
                    loadServerIdentity(
                        serverIdentity = serverIdentity,
                        backendEnvironment = BackendEnvironment.get(currentCoroutineContext()),
                    ).also {
                        enrollmentsMap = buildMap {
                            putAll(enrollmentsMap)
                            put(serverIdentity, it)
                        }
                    }
                }
            }
            return validRecord.signingKeyDeferred.value
        }

        private suspend fun loadServerIdentity(
            serverIdentity: ServerIdentity,
            backendEnvironment: BackendEnvironment
        ): ServerIdentityRecord {
            val configuration = backendEnvironment.getInterface(Configuration::class)!!
            val storage = backendEnvironment.getInterface(Storage::class)!!

            // First, try to load from the config
            configuration.getValue("server_identities")?.let {
                val keyName = serverIdentity.jsonName
                Json.parseToJsonElement(it).jsonObject[keyName]?.let { keyJson ->
                    val secureAreaRepository =
                        backendEnvironment.getInterface(SecureAreaRepository::class)
                    val loadedKey = AsymmetricKey.parse(keyJson, secureAreaRepository)
                    return ServerIdentityRecord.fromKey(loadedKey)
                }
            }

            // Then, try to load from the database
            val enrolled = storage.getTable(enrollmentsTable).get(serverIdentity.name)
            if (enrolled != null) {
                val keyData = SigningKeyData.fromCbor(enrolled.toByteArray())
                val secureArea = backendEnvironment.getInterface(SecureAreaProvider::class)!!.get()
                val loadedKey = AsymmetricKey.X509CertifiedSecureAreaBased(
                    secureArea = secureArea,
                    keyInfo = secureArea.getKeyInfo(keyData.alias),
                    certChain = keyData.certChain
                )
                return ServerIdentityRecord.fromKey(loadedKey)
            }

            // Request enrollment from the server
            // Channel needs some capacity so that sending the result to the channel is not blocked.
            val responseChannel = Channel<AsymmetricKey.X509Certified>(capacity = 1)
            val requestId = Random.nextBytes(15).toBase64Url()
            val signingKeyDeferred = lazy {
                // Launch lazily to avoid race conditions. By the time this is launched,
                // ServerIdentityRecord has been created and inserted in enrollmentsMap.
                check(enrollmentsMap.containsKey(serverIdentity))
                CoroutineScope(Dispatchers.IO).async {
                    val baseUrl = configuration.baseUrl
                    val enrollmentUrl = configuration.enrollmentServerUrl
                    // If no enrollment record is found, check if we are running on localhost
                    // When running on localhost, server-based enrollment is not possible unless
                    // records server is also on localhost. When self-enrolling, the root
                    // certificate will not be trusted (unless a trusted key/certificate is
                    // specified in the config file, which is not generally recommended).
                    val selfEnroll = enrollmentUrl == null ||
                        (LOCALHOST.matchEntire(baseUrl) != null &&
                                LOCALHOST.matchEntire(enrollmentUrl) == null)
                    if (selfEnroll) {
                        Logger.i(TAG, "Self-enrolling '$serverIdentity'")
                        withContext(backendEnvironment) {
                            val enrollment = EnrollmentImpl()
                            val now = Clock.System.now().truncateToWholeSeconds()
                            val expiration = now + 180.days
                            val request = enrollment.request(
                                requestId = requestId,
                                identity = serverIdentity,
                                nonce = ByteString(Random.nextBytes(15)),
                                expiration = expiration
                            )
                            val certChain = generateServerIdentityLeafCertificate(
                                serverIdentity = serverIdentity,
                                enrollmentRequest = request,
                                now = now,
                                expiration = expiration
                            )
                            enrollment.enroll(
                                requestId = requestId,
                                identity = serverIdentity,
                                alias = request.alias,
                                certChain = certChain
                            )
                        }
                    } else {
                        // Request enrollment from the server
                        val httpClient = backendEnvironment.getInterface(HttpClient::class)!!
                        val url = "$enrollmentUrl/enroll"
                        Logger.i(TAG, "Enrolling '$serverIdentity' using '$url'")
                        val response = httpClient.submitForm(
                            url = url,
                            formParameters = parameters {
                                append("url", baseUrl)
                                append("identity", serverIdentity.name)
                                append("request_id", requestId)
                            }
                        )
                        if (response.status != HttpStatusCode.OK) {
                            throw IllegalStateException("Could not enroll '$serverIdentity'")
                        }
                    }
                    responseChannel.receive()
                }
            }
            return ServerIdentityRecord(
                signingKeyDeferred = signingKeyDeferred,
                requestId = requestId,
                responseChannel = responseChannel
            )
        }

        private val provisioningServerKeyLock = Mutex()
        @Volatile
        private var provisioningServerNextUpdate: Instant = Instant.DISTANT_PAST
        @Volatile
        private var provisioningServerPublicKey: Deferred<EcPublicKey>? = null

        private val serverAuth = RpcAuthInspectorSignature { serverUrl ->
            val now = Clock.System.now()
            if (provisioningServerNextUpdate <= now) {
                provisioningServerKeyLock.withLock {
                    if (provisioningServerNextUpdate <= now) {
                        provisioningServerNextUpdate = now + 5.minutes
                        val env = BackendEnvironment.get(currentCoroutineContext())
                        provisioningServerPublicKey = CoroutineScope(Dispatchers.IO).async {
                            loadProvisioningServerPublicKey(env, serverUrl)
                        }
                    }
                }
            }
            provisioningServerPublicKey!!.await()
        }

        private suspend fun loadProvisioningServerPublicKey(
            backendEnvironment: BackendEnvironment,
            serverUrl: String
        ): EcPublicKey {
            val enrollmentUrl = backendEnvironment.getInterface(Configuration::class)!!.enrollmentServerUrl
            if (enrollmentUrl != serverUrl) {
                throw RpcAuthException(
                    message = "Invalid enrollment server: '$serverUrl'",
                    rpcAuthError = RpcAuthError.FAILED
                )
            }
            val httpClient = backendEnvironment.getInterface(HttpClient::class)!!
            val response = httpClient.get("$enrollmentUrl/ca/enrollment")
            if (response.status != HttpStatusCode.OK) {
                throw RpcAuthException(
                    message = "Could not reach provisioning server: '$enrollmentUrl/ca/enrollment'",
                    rpcAuthError = RpcAuthError.FAILED
                )
            }
            return X509Cert.fromPem(response.readRawBytes().decodeToString()).ecPublicKey
        }

        /** Pattern that matches localhost urls */
        internal val LOCALHOST = Regex("http://localhost([:/].*)?")
    }
}