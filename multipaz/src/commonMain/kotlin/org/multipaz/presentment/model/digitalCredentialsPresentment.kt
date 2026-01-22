package org.multipaz.presentment.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.openid.OpenID4VP
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.prompt.PromptUiNotAvailableException
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "digitalCredentialsPresentment"

/**
 * Present credentials according to the [W3C Digital Credentials API](https://www.w3.org/TR/digital-credentials/).
 *
 * @param protocol the `protocol` field in the [DigitalCredentialGetRequest](https://www.w3.org/TR/digital-credentials/#the-digitalcredentialgetrequest-dictionary) dictionary.
 * @param data a string with JSON from the `data` field in the [DigitalCredentialGetRequest](https://www.w3.org/TR/digital-credentials/#the-digitalcredentialgetrequest-dictionary) dictionary.
 * @param appId the id of the application making the request, if available, for example `com.example.app` on Android or `<teamId>.<bundleId>` on iOS.
 * @param origin the origin of the requester.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @return a string with JSON with the result, this is a JSON object containing the `protocol` and `data` fields in [DigitalCredential](https://www.w3.org/TR/digital-credentials/#the-digitalcredential-interface) interface.
 * @throws PromptDismissedException if the user dismissed a prompt.
 * @throws PromptModelNotAvailableException if `coroutineContext` does not have [PromptModel].
 * @throws PromptUiNotAvailableException if the UI layer hasn't bound any UI for [PromptModel].
 * @throws PresentmentCanceled if the user canceled in a consent prompt.
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    PresentmentCanceled::class
)
suspend fun digitalCredentialsPresentment(
    protocol: String,
    data: String,
    appId: String?,
    origin: String,
    preselectedDocuments: List<Document>,
    source: PresentmentSource,
): String {
    return Json.encodeToString(
        digitalCredentialsPresentment(
            protocol = protocol,
            data = Json.decodeFromString<JsonObject>(data),
            appId = appId,
            origin = origin,
            preselectedDocuments = preselectedDocuments,
            source = source,
        )
    )
}

/**
 * Present credentials according to the [W3C Digital Credentials API](https://www.w3.org/TR/digital-credentials/).
 *
 * @param protocol the `protocol` field in the [DigitalCredentialGetRequest](https://www.w3.org/TR/digital-credentials/#the-digitalcredentialgetrequest-dictionary) dictionary.
 * @param data the `data` field in the [DigitalCredentialGetRequest](https://www.w3.org/TR/digital-credentials/#the-digitalcredentialgetrequest-dictionary) dictionary.
 * @param appId the id of the application making the request, if available, for example `com.example.app` on Android or `<teamId>.<bundleId>` on iOS.
 * @param origin the origin of the requester.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @return JSON with the result, this is a JSON object containing the `protocol` and `data` fields in [DigitalCredential](https://www.w3.org/TR/digital-credentials/#the-digitalcredential-interface) interface.
 * @throws PromptDismissedException if the user dismissed a prompt.
 * @throws PromptModelNotAvailableException if `coroutineContext` does not have [PromptModel].
 * @throws PromptUiNotAvailableException if the UI layer hasn't bound any UI for [PromptModel].
 * @throws PresentmentCanceled if the user canceled in a consent prompt.
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    PresentmentCanceled::class
)
suspend fun digitalCredentialsPresentment(
    protocol: String,
    data: JsonObject,
    appId: String?,
    origin: String,
    preselectedDocuments: List<Document>,
    source: PresentmentSource,
): JsonObject {
    when (protocol) {
        "openid4vp", "openid4vp-v1-unsigned", "openid4vp-v1-signed" -> {
            return digitalCredentialsOpenID4VPProtocol(
                protocol = protocol,
                data = data,
                appId = appId,
                origin = origin,
                preselectedDocuments = preselectedDocuments,
                source = source,
            )
        }
        "org.iso.mdoc", "org-iso-mdoc" -> {
            return digitalCredentialsMdocApiProtocol(
                protocol = protocol,
                data = data,
                appId = appId,
                origin = origin,
                preselectedDocuments = preselectedDocuments,
                source = source,
            )
        }
        else -> {
            throw Error("Protocol ${protocol} is not supported")
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun digitalCredentialsOpenID4VPProtocol(
    protocol: String,
    data: JsonObject,
    appId: String?,
    origin: String,
    preselectedDocuments: List<Document>,
    source: PresentmentSource,
): JsonObject {
    val version = when (protocol) {
        "openid4vp" -> OpenID4VP.Version.DRAFT_24
        "openid4vp-v1-unsigned", "openid4vp-v1-signed" -> OpenID4VP.Version.DRAFT_29
        else -> throw IllegalStateException("Unexpected protocol ${protocol}")
    }
    var requesterCertChain: X509CertChain? = null
    val preReq = data

    val signedRequest = preReq["request"]
    val req = if (signedRequest != null) {
        val jws = Json.parseToJsonElement(signedRequest.jsonPrimitive.content)
        val info = JsonWebSignature.getInfo(jws.jsonPrimitive.content)
        check(info.x5c != null) { "x5c missing in JWS" }
        JsonWebSignature.verify(jws.jsonPrimitive.content, info.x5c.certificates.first().ecPublicKey)
        requesterCertChain = info.x5c
        for (cert in requesterCertChain.certificates) {
            println("cert: ${cert.toPem()}")
        }
        info.claimsSet
    } else {
        preReq
    }
    val response = OpenID4VP.generateResponse(
        version = version,
        preselectedDocuments = preselectedDocuments,
        source = source,
        appId = appId,
        origin = origin,
        request = req,
        requesterCertChain = requesterCertChain,
    )
    return buildJsonObject {
        put("protocol", protocol)
        put("data", response)
    }
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun digitalCredentialsMdocApiProtocol(
    protocol: String,
    data: JsonObject,
    appId: String?,
    origin: String,
    preselectedDocuments: List<Document>,
    source: PresentmentSource,
): JsonObject {
    val arfRequest = data
    val deviceRequestBase64 = arfRequest["deviceRequest"]!!.jsonPrimitive.content
    val encryptionInfoBase64 = arfRequest["encryptionInfo"]!!.jsonPrimitive.content

    val encryptionInfo = Cbor.decode(encryptionInfoBase64.fromBase64Url())
    Logger.iCbor(TAG, "encryptionInfo", encryptionInfo)
    if (encryptionInfo.asArray.get(0).asTstr != "dcapi") {
        throw IllegalArgumentException("Malformed EncryptionInfo")
    }
    val recipientPublicKey = encryptionInfo.asArray.get(1).asMap.get(Tstr
        ("recipientPublicKey"))!!.asCoseKey.ecPublicKey

    val dcapiInfo = buildCborArray {
        add(encryptionInfoBase64)
        add(origin)
    }

    Logger.iCbor(TAG, "dcapiInfo", dcapiInfo)
    val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
    val sessionTranscript = buildCborArray {
        add(Simple.NULL) // DeviceEngagementBytes
        add(Simple.NULL) // EReaderKeyBytes
        addCborArray {
            add("dcapi")
            add(dcapiInfoDigest)
        }
    }

    val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(deviceRequestBase64.fromBase64Url()))
    deviceRequest.verifyReaderAuthentication(sessionTranscript)
    val deviceResponse = mdocPresentment(
        deviceRequest = deviceRequest,
        eReaderKey = null,
        sessionTranscript = sessionTranscript,
        source = source,
        keyAgreementPossible = emptyList(),
        onWaitingForUserInput = {},
        onDocumentsInFocus = {},
    )

    val encrypter = Hpke.getEncrypter(
        cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
        receiverPublicKey = recipientPublicKey,
        info = Cbor.encode(sessionTranscript)
    )
    val ciphertext = encrypter.encrypt(
        plaintext = Cbor.encode(deviceResponse.toDataItem()),
        aad = ByteArray(0),
    )
    val encryptedResponse =
        Cbor.encode(
            buildCborArray {
                add("dcapi")
                addCborMap {
                    put("enc", encrypter.encapsulatedKey.toByteArray())
                    put("cipherText", ciphertext)
                }
            }
        )

    val data = buildJsonObject {
        put("response", encryptedResponse.toBase64Url())
    }
    return buildJsonObject {
        put("protocol", protocol)
        put("data", data)
    }
}
