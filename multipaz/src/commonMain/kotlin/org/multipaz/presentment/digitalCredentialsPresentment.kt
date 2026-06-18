package org.multipaz.presentment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsMdocApi
import org.multipaz.eventlogger.EventPresentmentDigitalCredentialsOpenID4VP
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.openid.OpenID4VP
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.prompt.PromptUiNotAvailableException
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.request.RequesterIdentity
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "digitalCredentialsPresentment"

/**
 * Present credentials according to the [W3C Digital Credentials API](https://www.w3.org/TR/digital-credentials/).
 *
 * Note: this variant with [String] instead of [JsonObject] only exists for interoperability with Swift.
 *
 * @param protocol the `protocol` field in the [DigitalCredentialGetRequest](https://www.w3.org/TR/digital-credentials/#the-digitalcredentialgetrequest-dictionary) dictionary.
 * @param data a string with JSON from the `data` field in the [DigitalCredentialGetRequest](https://www.w3.org/TR/digital-credentials/#the-digitalcredentialgetrequest-dictionary) dictionary.
 * @param appId the id of the application making the request, if available, for example `com.example.app` on Android or `<teamId>.<bundleId>` on iOS.
 * @param origin the origin of the requester.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param source the source of truth used for presentment.
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @return a string with JSON with the result, this is a JSON object containing the `protocol` and `data` fields in [DigitalCredential](https://www.w3.org/TR/digital-credentials/#the-digitalcredential-interface) interface.
 * @throws PromptDismissedException if the user dismissed a prompt.
 * @throws PromptModelNotAvailableException if `coroutineContext` does not have [PromptModel].
 * @throws PromptUiNotAvailableException if the UI layer hasn't bound any UI for [PromptModel].
 * @throws PresentmentCanceledException if the user canceled in a consent prompt.
 * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    PresentmentCanceledException::class
)
suspend fun digitalCredentialsPresentment(
    protocol: String,
    data: String,
    appId: String?,
    origin: String,
    preselectedDocuments: List<Document>,
    source: PresentmentSource,
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
): String {
    return Json.encodeToString(
        digitalCredentialsPresentment(
            protocol = protocol,
            data = Json.decodeFromString<JsonObject>(data),
            appId = appId,
            origin = origin,
            preselectedDocuments = preselectedDocuments,
            source = source,
            onDocumentsInFocus = onDocumentsInFocus
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
 * @param source the source of truth used for presentment.
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @return JSON with the result, this is a JSON object containing the `protocol` and `data` fields in [DigitalCredential](https://www.w3.org/TR/digital-credentials/#the-digitalcredential-interface) interface.
 * @throws PromptDismissedException if the user dismissed a prompt.
 * @throws PromptModelNotAvailableException if `coroutineContext` does not have [PromptModel].
 * @throws PromptUiNotAvailableException if the UI layer hasn't bound any UI for [PromptModel].
 * @throws PresentmentCanceledException if the user canceled in a consent prompt.
 * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    PresentmentCanceledException::class,
    PresentmentCannotSatisfyRequestException::class
)
suspend fun digitalCredentialsPresentment(
    protocol: String,
    data: JsonObject,
    appId: String?,
    origin: String,
    preselectedDocuments: List<Document>,
    source: PresentmentSource,
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
): JsonObject {
    when (protocol) {
        "openid4vp", "openid4vp-v1-unsigned", "openid4vp-v1-signed", "openid4vp-v1-multisigned" -> {
            return digitalCredentialsOpenID4VPProtocol(
                protocol = protocol,
                data = data,
                appId = appId,
                origin = origin,
                preselectedDocuments = preselectedDocuments,
                source = source,
                onDocumentsInFocus = onDocumentsInFocus
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
                onDocumentsInFocus = onDocumentsInFocus
            )
        }
        else -> {
            throw IllegalStateException("Protocol $protocol is not supported")
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
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): JsonObject {
    val version = when (protocol) {
        "openid4vp" -> OpenID4VP.Version.DRAFT_24
        "openid4vp-v1-unsigned", "openid4vp-v1-signed", "openid4vp-v1-multisigned" -> OpenID4VP.Version.DRAFT_29
        else -> throw IllegalStateException("Unexpected protocol $protocol")
    }
    val requesterIdentities = mutableListOf<RequesterIdentity>()
    val signedRequest = data["request"]
    val req = if (signedRequest != null) {
        val jws = Json.parseToJsonElement(signedRequest.jsonPrimitive.content)
        val info = JsonWebSignature.getInfo(jws.jsonPrimitive.content)
        check(info.x5c != null) { "x5c missing in JWS" }
        JsonWebSignature.verify(jws.jsonPrimitive.content, info.x5c.certificates.first().ecPublicKey)
        val clientId = (info.claimsSet["client_id"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("'client_id' is not given in the request")
        requesterIdentities.add(OpenID4VPRequesterIdentity(info.x5c, clientId))
        info.x5c.validate()
        info.claimsSet
    } else if (data.containsKey("signatures")) {
        val payload = (data["payload"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("'payload' is missing in multisigned request")
        for (item in data["signatures"]!!.jsonArray) {
            item as? JsonObject ?:
                throw IllegalArgumentException("'signatures' is invalid in multisigned request")
            val header = item["protected"]!!.jsonPrimitive.content
            val signature = item["signature"]!!.jsonPrimitive.content
            val headerObj = Json.parseToJsonElement(header.fromBase64Url().decodeToString()).jsonObject
            if (!headerObj.containsKey("x5c")) {
                // we only support X509-certified keys, ignore others
                continue
            }
            val x5c = X509CertChain.fromX5c(headerObj["x5c"]!!)
            val toBeVerified = "$header.$payload".encodeToByteArray()
            val ecSignature = EcSignature.fromCoseEncoded(signature.fromBase64Url())
            val algorithm = Algorithm.fromJoseAlgorithmIdentifier(headerObj["alg"]!!.jsonPrimitive.content)
            Crypto.checkSignature(
                publicKey = x5c.certificates.first().ecPublicKey,
                message = toBeVerified,
                algorithm = algorithm,
                signature = ecSignature
            )
            val clientId = (headerObj["client_id"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("'client_id' is not given in the request")
            requesterIdentities.add(OpenID4VPRequesterIdentity(x5c, clientId))
            x5c.validate()
        }
        Json.parseToJsonElement(payload.fromBase64Url().decodeToString()).jsonObject
    } else {
        data
    }
    val responseObject = OpenID4VP.generateResponse(
        version = version,
        preselectedDocuments = preselectedDocuments,
        source = source,
        appId = appId,
        origin = origin,
        request = req,
        requesterIdentities = requesterIdentities,
        onDocumentsInFocus = onDocumentsInFocus
    )

    source.eventLogger?.addEventAsync(
        EventPresentmentDigitalCredentialsOpenID4VP(
            presentmentData = responseObject.eventData,
            appId = appId,
            origin = origin,
            protocol = protocol,
            requestJson = Json.encodeToString(data),
            responseJson = Json.encodeToString(responseObject.response),
            vpToken = Json.encodeToString(responseObject.vpToken),
            state = responseObject.state
        )
    )

    return buildJsonObject {
        put("protocol", protocol)
        put("data", responseObject.response)
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
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): JsonObject {
    val arfRequest = data
    val deviceRequestBase64 = arfRequest["deviceRequest"]!!.jsonPrimitive.content
    val encryptionInfoBase64 = arfRequest["encryptionInfo"]!!.jsonPrimitive.content

    val encryptionInfo = Cbor.decode(encryptionInfoBase64.fromBase64Url())
    Logger.iCbor(TAG, "encryptionInfo", encryptionInfo)
    if (encryptionInfo.asArray[0].asTstr != "dcapi") {
        throw IllegalArgumentException("Malformed EncryptionInfo")
    }
    val recipientPublicKey = encryptionInfo.asArray[1].asMap[Tstr("recipientPublicKey")]!!
        .asCoseKey.ecPublicKey

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
    val responseObject = mdocPresentment(
        deviceRequest = deviceRequest,
        eReaderKey = null,
        sessionTranscript = sessionTranscript,
        source = source,
        keyAgreementPossible = emptyList(),
        requesterAppId = appId,
        requesterOrigin = origin,
        preselectedDocuments = preselectedDocuments,
        onWaitingForUserInput = {},
        onDocumentsInFocus = onDocumentsInFocus,
    )

    val encrypter = Hpke.getEncrypter(
        cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
        receiverPublicKey = recipientPublicKey,
        info = Cbor.encode(sessionTranscript)
    )
    val ciphertext = encrypter.encrypt(
        plaintext = Cbor.encode(responseObject.deviceResponse.toDataItem()),
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

    val responseData = buildJsonObject {
        put("response", encryptedResponse.toBase64Url())
    }

    source.eventLogger?.addEventAsync(
        EventPresentmentDigitalCredentialsMdocApi(
            presentmentData = responseObject.eventData,
            appId = appId,
            origin = origin,
            protocol = protocol,
            requestJson = Json.encodeToString(data),
            responseJson = Json.encodeToString(responseData),
            deviceResponse = responseObject.deviceResponse.toDataItem()
        )
    )

    return buildJsonObject {
        put("protocol", protocol)
        put("data", responseData)
    }
}
