package org.multipaz.server.enrollment

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.util.toLowerCasePreservingASCIIRules
import io.ktor.util.toUpperCasePreservingASCIIRules
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1String
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509Crl
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.cache
import org.multipaz.rpc.client.RpcAuthorizedServerClient
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.server.common.getBaseUrl
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.util.fromGlob
import org.multipaz.util.truncateToWholeSeconds
import java.lang.IllegalStateException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Enum that describes types of server-side identity (i.e. a combination of a private key
 * and a certificate chain) used in Multipaz.
 *
 * @property commonName X500 common name prefix used in certificates for this identity. Root
 *    certificates will use common name structured as "$commonName Root at https://...."
 *    and second-level certificates will use "$commonName at https://....".
 */
enum class ServerIdentity(
    val commonName: String
) {
    /**
     * An identity used by a CA server to remotely enroll other servers, i.e. provide
     * required identities to them.
     *
     * By default all multipaz servers only trust the server running at
     * "https://issuer.multipaz.org/records". This can be changed using "enrollment_server_url"
     * setting.
     */
    ENROLLMENT("Enrollment"),

    /**
     * An identity used to sign credentials.
     *
     * For ISO mdoc, the root certificate is IACA certificate and the leaf certificate is
     * DS certificate.
     */
    CREDENTIAL_SIGNING("OpenID4VCI"),

    /**
     * An identity for cloud secure area.
     */
    CLOUD_SECURE_AREA_BINDING("CSA Binding"),

    /**
     * An identity for digital credentials readers.
     *
     * It identifies a reader as trusted to a digital-credential-holding app ("wallet").
     */
    VERIFIER("Verifier"),

    /**
     * An identity for OpenID and cloud secure area key attestations.
     *
     * It certifies key(s) as trusted to OpenID4VCI server.
     */
    KEY_ATTESTATION("OpenID Key Attestation"),

    /**
     * An identity for OpenID Wallet attestations.
     *
     * It identifies a client app ("wallet") to an OpenID4VCI server.
     */
    WALLET_ATTESTATION("OpenID Client Attestation"),

    /**
     * An identity for OpenID client assertion.
     *
     * General-purpose OpenID client authorization method. It is similar to the newer and more
     * robust [WALLET_ATTESTATION].
     */
    CLIENT_ASSERTION("OpenID Client Assertion"),

    /**
     * An internal identity for the System of Record clients.
     *
     * Multipaz issuance servers use this to access the System of Record.
     */
    RECORDS_CLIENT("System of Records Client");

    /**
     * A name for the identity that is formatted for use in JSON and in URLs (lowercase,
     * underscore-separated).
     */
    val jsonName get() = name.toLowerCasePreservingASCIIRules()

    companion object {
        /**
         * Create [ServerIdentity] from its JSON name (see [jsonName]).
         */
        fun fromJsonName(jsonName: String) =
            valueOf(jsonName.toUpperCasePreservingASCIIRules())
    }
}

/**
 * Obtain a server identity (private key + certificate chain) of a particular type.
 *
 * First, consult the server configuration. Setting "server_identities" if present, must be
 * a JSON object. Field with the name `serverIdentity.jsonName`, if present, must contain a
 * serialized [AsymmetricKey] that holds the required identity.
 *
 * Otherwise, an previously-created identity is looked up in the database.
 *
 * Otherwise, if "enrollment_server_url" setting is null or points to non-localhost url when the
 * server's baseUrl is a localhost url, a new private key is generated and a certificate is created
 * locally on this server (signed using key obtained using `getRootIdentity` function).
 *
 * Otherwise, a request is made to an enrollment server (set by "enrollment_server_url" setting,
 * "https://issuer.multipaz.org/records" by default) to issue a new certificate using
 * [Enrollment] interface.
 *
 * The private key alias and the certificate chain are then stored in the database, so they can
 * be used in the future.
 */
suspend fun getServerIdentity(serverIdentity: ServerIdentity): AsymmetricKey.X509Certified =
    EnrollmentImpl.getServerIdentity(serverIdentity).await()

private class CachedIdentity(val signingKey: AsymmetricKey)

/**
 * Reads CA/root certificate for the given server identity type issued locally on this server.
 *
 * @param serverIdentity type of the server identity to read.
 * @param createOnRequest when true, if an identity is not found in the configuration and in
 *      the database, it is generated (and stored in the database for future use).
 * @return root certificate for the requested identity
 * @throws IllegalStateException if [createOnRequest] is `false` and requested identity does not
 *      exist in either configuration or database.
 */
suspend fun getLocalRootCertificate(
    serverIdentity: ServerIdentity,
    createOnRequest: Boolean
): X509Cert {
    val certChain = getRootIdentity(serverIdentity, createOnRequest).certChain
    check(certChain.certificates.size == 1)
    return certChain.certificates.first()
}

/**
 * Reads CA/root identity (private key and certificate) for the given server identity type.
 *
 * First, consult the server configuration. Setting "root_identities" if present, must be
 * a JSON object. Field with the name `serverIdentity.jsonName`, if present, must contain a
 * serialized [AsymmetricKey] that holds the required root identity.
 *
 * Otherwise a new private key and self-signed certificate is created and cached in the database for
 * future use, except when [createOnRequest] is set to false.
 *
 * @param serverIdentity type of the server identity to read.
 * @param createOnRequest when true, if an identity is not found in the configuration and in
 *      the database, it is generated (and stored in the database for future use).
 * @return private key and certificate chain (of length 1) for the requested identity
 * @throws IllegalStateException if [createOnRequest] is `false` and requested identity does not
 *      exist in either configuration or database.
 */
private suspend fun getRootIdentity(
    serverIdentity: ServerIdentity,
    createOnRequest: Boolean
): AsymmetricKey.X509Certified =
    BackendEnvironment.cache(CachedIdentity::class, serverIdentity) { configuration, _ ->
        Logger.i(TAG, "Loading $serverIdentity root key and certificate")
        configuration.getValue("root_identities")?.let {
            Json.parseToJsonElement(it).jsonObject[serverIdentity.jsonName]?.jsonObject?.let { json ->
                val secureAreaRepository = BackendEnvironment.getInterface(SecureAreaRepository::class)
                return@cache CachedIdentity(AsymmetricKey.parse(json, secureAreaRepository))
            }
        }
        val secureArea = BackendEnvironment.getInterface(SecureAreaProvider::class)!!.get()
        val rootSigningKeyDataTable = BackendEnvironment.getTable(rootSigningKeyDataTableSpec)
        val rootSigningKeyData = rootSigningKeyDataTable.get(serverIdentity.name)?.let {
            Logger.i(TAG, "Loaded $serverIdentity root key and certificate")
            SigningKeyData.fromCbor(it.toByteArray())
        } ?: run {
            if (!createOnRequest) {
                throw IllegalStateException("$serverIdentity not available")
            }
            Logger.i(TAG, "Generating $serverIdentity root key and certificate")
            createRootIdentity(
                secureArea = secureArea,
                serverIdentity = serverIdentity
            ).also {
                Logger.i(TAG, "Saving $serverIdentity root key and certificate")
                rootSigningKeyDataTable.insert(
                    key = serverIdentity.name,
                    data = ByteString(it.toCbor())
                )
            }
        }
        CachedIdentity(
            AsymmetricKey.X509CertifiedSecureAreaBased(
                certChain = rootSigningKeyData.certChain,
                secureArea = secureArea,
                keyInfo = secureArea.getKeyInfo(rootSigningKeyData.alias)
            )
        )
    }.signingKey as AsymmetricKey.X509Certified

// For ISO mdoc DS certificate, maximum is 457 days
private val CERTIFICATE_DURATION = 183.days

private suspend fun createRootIdentity(
    secureArea: SecureArea,
    serverIdentity: ServerIdentity
): SigningKeyData {
    val keyInfo = secureArea.createKey(
        alias = null,
        createKeySettings = CreateKeySettings(
            algorithm = Algorithm.ESP384
        )
    )
    val issuerUrl = BackendEnvironment.getBaseUrl()
    val crlUrl = "$issuerUrl/crl/${serverIdentity.jsonName}"
    val enrollmentInfo = BackendEnvironment.getInterface(Configuration::class)!!
        .getValue("enrollment_info")?.let {
            Json.parseToJsonElement(it).jsonObject
        }

    val issuer = X500Name(
        buildMap {
            put(
                OID.COMMON_NAME.oid, ASN1String(
                    "${serverIdentity.commonName} Root at $issuerUrl"
                )
            )
            put(
                OID.COUNTRY_NAME.oid,
                ASN1String(enrollmentInfo?.get("country")?.jsonPrimitive?.content ?: "US")
            )
            enrollmentInfo?.get("state_or_province")?.jsonPrimitive?.content?.let {
                put(OID.STATE_OR_PROVINCE_NAME.oid, ASN1String(it))
            }
            enrollmentInfo?.get("locality")?.jsonPrimitive?.content?.let {
                put(OID.LOCALITY_NAME.oid, ASN1String(it))
            }
            enrollmentInfo?.get("organization")?.jsonPrimitive?.content?.let {
                put(OID.ORGANIZATION_NAME.oid, ASN1String(it))
            }
            enrollmentInfo?.get("organizational_unit")?.jsonPrimitive?.content?.let {
                put(OID.ORGANIZATIONAL_UNIT_NAME.oid, ASN1String(it))
            }
        }
    )
    val now = Clock.System.now().truncateToWholeSeconds()
    val rootCertificate = buildX509Cert(
        publicKey = keyInfo.publicKey,
        signingKey = AsymmetricKey.anonymous(secureArea, keyInfo.alias),
        serialNumber = ASN1Integer.fromRandom(128),
        subject = issuer,
        issuer = issuer,
        validFrom = now,
        validUntil = now + (15 * 365).days
    ) {
        // Certificate set-up follows the rules for IACA, as they seem to make sense for all our
        // root certificates.
        includeSubjectKeyIdentifier()
        includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        // For IACA mandated in 18013-5 table B.1
        setKeyUsage(setOf(X509KeyUsage.CRL_SIGN, X509KeyUsage.KEY_CERT_SIGN))
        if (serverIdentity == ServerIdentity.VERIFIER
            || serverIdentity == ServerIdentity.KEY_ATTESTATION) {
            // Verifier root certificate is used to issue reader certificate, which is used to
            // issue ephemeral key certificates, so there is one intermediate certificate required.
            // Key attestation certificate is also use in CSA to generate additional certificate
            // in the chain.
            setBasicConstraints(true, 1)
        } else {
            // For IACA mandated in 18013-5 table B.1: critical, CA=true, pathLenConstraint=0
            setBasicConstraints(true, 0)
        }
        // For IACA mandated in 18013-5 table B.1: non-critical, Email or URL
        addExtension(
            OID.X509_EXTENSION_ISSUER_ALT_NAME.oid,
            false,
            ASN1.encode(
                ASN1Sequence(listOf(
                    ASN1TaggedObject(
                        ASN1TagClass.CONTEXT_SPECIFIC,
                        ASN1Encoding.PRIMITIVE,
                        6,
                        issuerUrl.encodeToByteArray()
                    )
                ))
            )
        )
        // For IACA mandated in 18013-5 table B.1: non-critical, The ‘reasons’ and ‘cRL Issuer’
        // fields shall not be used.
        addExtension(
            OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid,
            false,
            ASN1.encode(
                ASN1Sequence(listOf(
                    ASN1Sequence(listOf(
                        ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, 0, ASN1.encode(
                            ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, 0, ASN1.encode(
                                ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.PRIMITIVE, 6,
                                    crlUrl.encodeToByteArray()
                                )
                            ))
                        ))
                    ))
                ))
            )
        )
    }
    return SigningKeyData(
        certChain = X509CertChain(listOf(rootCertificate)),
        alias = keyInfo.alias
    )
}

suspend fun generateServerIdentityLeafCertificate(
    serverIdentity: ServerIdentity,
    enrollmentRequest: Enrollment.EnrollmentRequest,
    now: Instant = Clock.System.now().truncateToWholeSeconds(),
    expiration: Instant = now + CERTIFICATE_DURATION
): X509CertChain {
    val commonName = "${serverIdentity.commonName} at ${enrollmentRequest.url}"
    val subject = X500Name(buildMap {
        put(OID.COMMON_NAME.oid, ASN1String(commonName))
        enrollmentRequest.country?.let {
            put(OID.COUNTRY_NAME.oid, ASN1String(it))
        }
        enrollmentRequest.stateOrProvince?.let {
            put(OID.STATE_OR_PROVINCE_NAME.oid, ASN1String(it))
        }
        enrollmentRequest.locality?.let {
            put(OID.LOCALITY_NAME.oid, ASN1String(it))
        }
        enrollmentRequest.organization?.let {
            put(OID.ORGANIZATION_NAME.oid, ASN1String(it))
        }
        enrollmentRequest.organizationalUnit?.let {
            put(OID.ORGANIZATIONAL_UNIT_NAME.oid, ASN1String(it))
        }
    })
    val signingKey = getRootIdentity(serverIdentity, createOnRequest = true)
    val certifyingChain = signingKey.certChain
    val issuerCert = certifyingChain.certificates.first()
    val certificate = buildX509Cert(
        publicKey = enrollmentRequest.keyAttestation.publicKey,
        signingKey = signingKey,
        serialNumber = IssuedCertificateData.recordIssuedCertificate(
            serverIdentity = serverIdentity,
            publicKey = enrollmentRequest.keyAttestation.publicKey,
            subject = subject,
            expiration = expiration
        ),
        subject = subject,
        issuer = certifyingChain.certificates.first().subject,
        validFrom = now,
        validUntil = expiration
    ) {
        includeSubjectKeyIdentifier()
        setAuthorityKeyIdentifierToCertificate(issuerCert)
        when (serverIdentity) {
            ServerIdentity.VERIFIER ->
                // Reader certificate is used to issue ephemeral key certificates
                setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            ServerIdentity.KEY_ATTESTATION ->
                // CSA issues additional certificate for the certified key, allow that
                setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN, X509KeyUsage.DIGITAL_SIGNATURE))
            else ->
                // For DS mandated in 18013-5 table B.3: critical: digital signature bits set
                setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
        }
        // For DS mandated in 18013-5 table B.3: non-critical, Email or URL
        addExtension(
            OID.X509_EXTENSION_ISSUER_ALT_NAME.oid,
            false,
            issuerCert.getExtensionValue(OID.X509_EXTENSION_ISSUER_ALT_NAME.oid)!!
        )
        // For DS defined in 18013-5 table B.3: non-critical, The ‘reasons’ and ‘cRL Issuer’
        // fields shall not be used.
        addExtension(
            OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid,
            false,
            issuerCert.getExtensionValue(OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid)!!
        )
        when (serverIdentity) {
            ServerIdentity.CREDENTIAL_SIGNING ->
                // 18013-5 table B.3: non-critical, Extended Key usage
                addExtension(
                    OID.X509_EXTENSION_EXTENDED_KEY_USAGE.oid,
                    true,
                    ASN1.encode(
                        ASN1Sequence(
                            listOf(
                                ASN1ObjectIdentifier(OID.ISO_18013_5_MDL_DS.oid),
                                ASN1ObjectIdentifier(OID.ISO_23220_4_MDOC_DS.oid)
                            )
                        )
                    )
                )

            ServerIdentity.VERIFIER ->
                // 18013-5 table B.3: non-critical, Extended Key usage
                addExtension(
                    OID.X509_EXTENSION_EXTENDED_KEY_USAGE.oid,
                    true,
                    ASN1.encode(
                        ASN1Sequence(
                            listOf(
                                ASN1ObjectIdentifier(OID.ISO_18013_5_MDL_READER_AUTH.oid),
                                ASN1ObjectIdentifier(OID.ISO_23220_4_MDOC_READER_AUTH.oid)
                            )
                        )
                    )
                )

            else -> {}
        }
    }
    return X509CertChain(buildList {
        add(certificate)
        addAll(certifyingChain.certificates)
    })
}

/**
 * "Enrolls" a server by creating a certificate of the requested type for its private key
 * using [Enrollment] interface.
 *
 * Only create certificates for servers with urls that match "ca_allow_enrollment" list.
 *
 * @param url url of the server to enroll
 * @param serverIdentity identity to provide
 * @param requestId must be non-null if the request to enroll came from the server being enrolled;
 *     this allows enrolling server to ensure that it is not being force-enrolled by a malicious
 *     third party.
 */
suspend fun enrollServer(
    url: String,
    serverIdentity: ServerIdentity,
    requestId: String? = null
) {
    // For now, only enroll servers that match our list. In the future we may consider
    // allowing manual enrollments (with requestId == null).
    try {
        checkServerTrust(url, "ca_allow_enrollment")
    } catch (err: IllegalStateException) {
        Logger.e(TAG, "Attempt to enroll an untrusted server: '$url'")
        throw InvalidRequestException(err.message)
    }

    Logger.i(TAG, "Enrolling '$url' as '$serverIdentity'...")
    val exceptionMap = RpcExceptionMap.Builder().build()
    val dispatcher = RpcAuthorizedServerClient.connect(
        exceptionMap = exceptionMap,
        rpcEndpointUrl = "$url/push",
        callingServerUrl = BackendEnvironment.getBaseUrl(),
        signingKey = getRootIdentity(ServerIdentity.ENROLLMENT, createOnRequest = true)
    )
    val enrollment = EnrollmentStub(
        endpoint = "enrollment",
        dispatcher = dispatcher,
        notifier = RpcNotifier.SILENT,
    )
    withContext(RpcAuthClientSession()) {
        val now = Clock.System.now().truncateToWholeSeconds()
        val expiration = now + CERTIFICATE_DURATION
        val nonce = ByteString(Random.nextBytes(15))
        val enrollmentRequest = enrollment.request(requestId, serverIdentity, nonce, expiration)
        if (enrollmentRequest.url != url) {
            throw InvalidRequestException("Unexpected url in enrollment request: '${enrollmentRequest.url}'")
        }
        val certChain = generateServerIdentityLeafCertificate(
            serverIdentity, enrollmentRequest, now, expiration)
        enrollment.enroll(requestId, serverIdentity, enrollmentRequest.alias, certChain)
        Logger.i(TAG, "Enrolled '$url' as '$serverIdentity'")
    }
}

/**
 * Reads CA certificate revocation list for the given server identity type issued
 * locally on this server.
 *
 * @param serverIdentity type of the server identity to read.
 * @param createOnRequest when true, if an identity is not found in the configuration and in
 *      the database, it is generated (and stored in the database for future use).
 * @return certificate revocation list for the requested identity
 * @throws IllegalStateException if [createOnRequest] is `false` and requested identity does not
 *      exist in either configuration or database.
 */
suspend fun getCrl(
    serverIdentity: ServerIdentity,
    createOnRequest: Boolean
): X509Crl {
    val root = getRootIdentity(serverIdentity, createOnRequest)
    val now = Clock.System.now().truncateToWholeSeconds()
    val builder = X509Crl.Builder(
        signingKey = root,
        issuer = root.getX500Subject(),
        thisUpdate = now,
        nextUpdate = now + 24.hours
    )
    // Empty CRL for now
    return builder.build()
}

/**
 * Checks if the given url can be trusted according to a given server setting name.
 *
 * Allowed url must be an HTTPS url and must match one of the host/path patterns listed in the
 * named setting.
 *
 * @param url url to check
 * @param settingName server setting that should hold a list of patterns
 */
suspend fun checkServerTrust(url: String, settingName: String) {
    val parsedUrl = Url(url)
    val baseUrl = BackendEnvironment.getBaseUrl()
    if (EnrollmentImpl.LOCALHOST.matchEntire(baseUrl) == null
        && parsedUrl.protocol != URLProtocol.HTTPS) {
        throw IllegalStateException("Only allow https servers")
    }
    val host = parsedUrl.host
    val path = parsedUrl.encodedPath.decodeURLPart().ifEmpty { "/" }
    val trustedServers = BackendEnvironment.getInterface(Configuration::class)!!
        .getValue(settingName)
    if (trustedServers != null) {
        val trustedServerArray = Json.parseToJsonElement(trustedServers).jsonArray
        for (patternJson in trustedServerArray) {
            val pattern = patternJson.jsonPrimitive.content
            val pathIndex = pattern.indexOf('/')
            val hostPattern = Regex.fromGlob(
                if (pathIndex < 0) pattern else pattern.take(pathIndex))
            val pathPattern = Regex.fromGlob(
                if (pathIndex < 0) "/**" else pattern.substring(pathIndex))
            if (hostPattern.matchEntire(host) != null &&
                pathPattern.matchEntire(path) != null) {
                // Valid
                return
            }
        }
    }
    throw IllegalStateException("Not a trusted host: '$url' ['$host' '$path' '$trustedServers']")
}

private const val TAG = "ServerIdentity"

private val rootSigningKeyDataTableSpec = StorageTableSpec(
    name = "RootSigningKeyData",
    supportPartitions = false,
    supportExpiration = false
)
