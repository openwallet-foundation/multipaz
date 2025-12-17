package org.multipaz.csa.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.multipaz.asn1.ASN1
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.securearea.cloud.CloudSecureAreaServer
import org.multipaz.securearea.cloud.SimplePassphraseFailureEnforcer
import org.multipaz.server.request.certificateAuthority
import org.multipaz.server.request.push
import java.security.Security
import kotlin.time.Duration.Companion.seconds

/**
 * Defines server endpoints for HTTP GET and POST.
 */
fun Application.configureRouting(serverEnvironment: Deferred<ServerEnvironment>) {
    Security.addProvider(BouncyCastleProvider())
    val keyMaterial = lazy {
        KeyMaterial.create(serverEnvironment)
    }
    val cloudSecureArea = lazy {
        createCloudSecureArea(serverEnvironment, keyMaterial.value)
    }
    CoroutineScope(Dispatchers.IO).launch {
        withContext(serverEnvironment.await()) {
            keyMaterial.value.await()
            cloudSecureArea.value.await()
        }
    }
    routing {
        push(serverEnvironment)
        certificateAuthority()
        post("/") {
            handlePost(call, cloudSecureArea.value.await())
        }
        get("/") {
            handleGet(call, keyMaterial.value.await())
        }
    }
}

private const val TAG = "ApplicationExt"

private suspend fun handleGet(
    call: ApplicationCall,
    keyMaterial: KeyMaterial
) {
    val sb = StringBuilder()
    sb.append(
        """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Cloud Secure Area - Server Reference Implementation</title>
            </head>
            <body>
            <h1>Cloud Secure Area - Server Reference Implementation</h1>
            <p><b>Note: This reference implementation is not production quality. Use at your own risk.</b></p>            
            <h2>Attestation Root</h2>
    """.trimIndent()
    )

    for (certificate in keyMaterial.attestationKey.certChain.certificates) {
        sb.append("<h3>Certificate</h3>")
        sb.append("<pre>")
        sb.append(ASN1.print(ASN1.decode(certificate.tbsCertificate)!!))
        sb.append("</pre>")
    }
    sb.append("<h2>Cloud Binding Key Attestation Root</h2>")
    for (certificate in keyMaterial.cloudBindingKey.certChain.certificates) {
        sb.append("<h3>Certificate</h3>")
        sb.append("<pre>")
        sb.append(ASN1.print(ASN1.decode(certificate.tbsCertificate)!!))
        sb.append("</pre>")
    }
    sb.append(
        """
    </body>
    </html>
    """.trimIndent()
    )
    call.respondText(
        contentType = ContentType.Text.Html,
        text = sb.toString()
    )
}

private suspend fun handlePost(
    call: ApplicationCall,
    cloudSecureArea: CloudSecureAreaServer
) {
    val request = call.receive<ByteArray>()
    val remoteHost = call.request.host()
    val (status, body) = cloudSecureArea.handleCommand(request, remoteHost)
    call.respondBytes(
        status = HttpStatusCode(status, ""),
        contentType = ContentType.Application.Cbor
    ) { body }
}

private fun createCloudSecureArea(
    backendEnvironmentDeferred: Deferred<BackendEnvironment>,
    keyMaterialDeferred: Deferred<KeyMaterial>
): Deferred<CloudSecureAreaServer> = CoroutineScope(Dispatchers.Default).async {
    val backendEnvironment = backendEnvironmentDeferred.await()
    val keyMaterial = keyMaterialDeferred.await()
    val settings = CloudSecureAreaSettings(backendEnvironment.getInterface(Configuration::class)!!)
    CloudSecureAreaServer(
        serverSecureAreaBoundKey = keyMaterial.serverSecureAreaBoundKey.toByteArray(),
        attestationKey = keyMaterial.attestationKey,
        cloudRootAttestationKey = keyMaterial.cloudBindingKey,
        e2eeKeyLimitSeconds = settings.cloudSecureAreaRekeyingIntervalSeconds,
        iosReleaseBuild = settings.iosReleaseBuild,
        iosAppIdentifiers = settings.iosAppIdentifiers,
        androidGmsAttestation = settings.androidRequireGmsAttestation,
        androidVerifiedBootGreen = settings.androidRequireVerifiedBootGreen,
        androidAppSignatureCertificateDigests = settings.androidRequireAppSignatureCertificateDigests,
        androidAppPackageNames = settings.androidRequireAppPackageNames,
        androidKeystoreSecurityLevel = when (settings.androidRequireKeystoreSecurityLevel) {
            "strong_box" -> AndroidKeystoreSecurityLevel.STRONG_BOX
            "software" -> AndroidKeystoreSecurityLevel.SOFTWARE
            else -> AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT
        },
        openid4vciKeyAttestationIssuer = settings.openid4vciKeyAttestationIssuer,
        openid4vciKeyAttestationKeyStorage = settings.openid4vciKeyAttestationKeyStorage,
        openid4vciKeyAttestationUserAuthentication = settings.openid4vciKeyAttestationUserAuthentication,
        openid4vciKeyAttestationUserAuthenticationNoPassphrase = settings.openid4vciKeyAttestationUserAuthenticationNoPassphrase,
        openid4vciKeyAttestationCertification = settings.openid4vciKeyAttestationCertification,
        passphraseFailureEnforcer = SimplePassphraseFailureEnforcer(
            settings.cloudSecureAreaLockoutNumFailedAttempts,
            settings.cloudSecureAreaLockoutDurationSeconds.seconds
        ),
    )
}


