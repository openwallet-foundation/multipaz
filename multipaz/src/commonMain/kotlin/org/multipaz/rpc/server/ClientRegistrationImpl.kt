package org.multipaz.rpc.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.device.DeviceAttestation
import org.multipaz.device.DeviceAttestationException
import org.multipaz.device.DeviceAttestationValidationData
import org.multipaz.device.toCbor
import org.multipaz.rpc.annotation.RpcState
import org.multipaz.rpc.auth.ClientRegistration
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.RpcAuthError
import org.multipaz.rpc.handler.RpcAuthException
import org.multipaz.rpc.handler.RpcAuthInspectorAssertion
import org.multipaz.util.fromBase64Url
import kotlin.random.Random

@RpcState(
    endpoint = "client_registration",
    creatable = true
)
@CborSerializable
class ClientRegistrationImpl(
    var registrationChallenge: ByteString? = null,
): ClientRegistration {
    override suspend fun challenge(): ByteString {
        return buildByteString { Random.Default.nextBytes(16) }.also {
            registrationChallenge = it
        }
    }

    override suspend fun register(deviceAttestation: DeviceAttestation): String {
        val registrationNonce = this.registrationChallenge
        if (registrationNonce == null) {
            throw InvalidRequestException("registrationNonce was not called")
        }
        val validationData = getClientRequirements().withChallenge(registrationNonce)
        this.registrationChallenge = null
        val clientTable = BackendEnvironment.Companion.getTable(RpcAuthInspectorAssertion.Companion.rpcClientTableSpec)
        try {
            deviceAttestation.validate(validationData)
        } catch (err: DeviceAttestationException) {
            throw RpcAuthException("device attestation: ${err.message}", RpcAuthError.FAILED)
        }
        val clientData = ByteString(deviceAttestation.toCbor())
        return clientTable.insert(key = null, data = clientData)
    }

    companion object {
        private val lock = Mutex()
        private val clientValidationData: DeviceAttestationValidationData? = null

        suspend fun getClientRequirements() =
            lock.withLock {
                clientValidationData ?: run {
                    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
                    val clientRequirements = configuration.getValue("client_requirements")
                    val requirements = clientRequirements?.let {
                        Json.parseToJsonElement(it) as JsonObject
                    }
                    val ios = requirements?.get("ios") as JsonObject?
                    val android = requirements?.get("android") as JsonObject?
                    DeviceAttestationValidationData(
                        attestationChallenge = ByteString(),
                        iosReleaseBuild = ios.bool("release_build"),
                        iosAppIdentifiers = ios.stringSet("app_identifiers"),
                        androidGmsAttestation = android.bool("gms_attestation"),
                        androidVerifiedBootGreen = android.bool("verified_boot_green", true),
                        androidAppSignatureCertificateDigests =
                            android.byteStringSet("app_signature_certificate_digests"),
                        androidAppPackageNames = android.stringSet("app_packages"),
                        androidRequiredKeyMintSecurityLevel = when (android.string("keystore_security_level", "tee")!!) {
                            "software" -> AndroidKeystoreSecurityLevel.SOFTWARE
                            "tee" -> AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT
                            "strong_box" -> AndroidKeystoreSecurityLevel.STRONG_BOX
                            else -> throw IllegalStateException("keystore_security_level invalid")
                        }
                    )
                }
            }

        private fun JsonObject?.bool(name: String, default: Boolean = false): Boolean {
            val value = this?.get(name) ?: return default
            if (value !is JsonPrimitive || value.isString) {
                throw IllegalStateException("$name is not a boolean")
            }
            return when (value.content) {
                "true" -> true
                "false" -> false
                else -> throw IllegalStateException("$name is not a boolean")
            }
        }

        private fun JsonObject?.string(name: String, default: String? = ""): String? {
            val value = this?.get(name) ?: return default
            if (value !is JsonPrimitive || !value.isString) {
                throw IllegalStateException("$name is not a string")
            }
            return value.content
        }

        private fun JsonObject?.byteStringSet(name: String): Set<ByteString> {
            val value = this?.get(name) ?: return setOf()
            if (value !is JsonArray) {
                throw IllegalStateException("$name is not an array")
            }
            return buildSet {
                for (item in value) {
                    if (item !is JsonPrimitive || !item.isString) {
                        throw IllegalStateException("$name must contain list of strings")
                    }
                    // allow both base64url and hex encoding (common for certificate hashes)
                    val bytes = if (item.content.contains(':')) {
                        item.jsonPrimitive.content.split(':').map { byteCode ->
                            byteCode.toInt(16).toByte()
                        }.toByteArray()
                    } else {
                        item.content.fromBase64Url()
                    }
                    add(ByteString(bytes))
                }
            }
        }

        private fun JsonObject?.stringSet(name: String): Set<String> {
            val value = this?.get(name) ?: return setOf()
            if (value !is JsonArray) {
                throw IllegalStateException("$name is not an array")
            }
            return buildSet {
                for (item in value) {
                    if (item !is JsonPrimitive || !item.isString) {
                        throw IllegalStateException("$name must contain list of strings")
                    }
                    add(item.content)
                }
            }
        }
    }
}