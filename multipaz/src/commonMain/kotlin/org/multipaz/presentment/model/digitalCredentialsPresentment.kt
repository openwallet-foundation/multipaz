package org.multipaz.presentment.model

import kotlinx.coroutines.flow.MutableStateFlow
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
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.OpenID4VP
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
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
 */
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
            source = source
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
 */
suspend fun digitalCredentialsPresentment(
    protocol: String,
    data: JsonObject,
    appId: String?,
    origin: String,
    preselectedDocuments: List<Document>,
    source: PresentmentSource,
): JsonObject {
    var result: Pair<String, JsonObject>? = null
    val mechanism = object : DigitalCredentialsPresentmentMechanism(
        appId = appId,
        origin = origin,
        protocol = protocol,
        data = data,
        preselectedDocuments = preselectedDocuments
    ) {
        override fun sendResponse(
            protocol: String,
            data: JsonObject
        ) {
            result = Pair(protocol, data)
        }

        override fun close() {
        }
    }
    val dismissable = MutableStateFlow<Boolean>(false)
    digitalCredentialsPresentment(
        documentTypeRepository = source.documentTypeRepository,
        source = source,
        mechanism = mechanism,
        dismissable = dismissable,
        showConsentPrompt = { credentialPresentmentData, preselectedDocuments, requester, trustPoint -> null }
    )

    return buildJsonObject {
        put("protocol", result!!.first)
        put("data", result!!.second)
    }
}

internal suspend fun digitalCredentialsPresentment(
    documentTypeRepository: DocumentTypeRepository,
    source: PresentmentSource,
    mechanism: DigitalCredentialsPresentmentMechanism,
    dismissable: MutableStateFlow<Boolean>,
    showConsentPrompt: suspend (
        credentialPresentmentData: CredentialPresentmentData,
        preselectedDocuments: List<Document>,
        requester: Requester,
        trustPoint: TrustPoint?
    ) -> CredentialPresentmentSelection?,
) {
    Logger.i(TAG, "mechanism.protocol: ${mechanism.protocol}")
    Logger.i(TAG, "mechanism.request: ${mechanism.data}")
    dismissable.value = false
    when (mechanism.protocol) {
        "openid4vp", "openid4vp-v1-unsigned", "openid4vp-v1-signed" -> digitalCredentialsOpenID4VPProtocol(
            presentmentMechanism = mechanism,
            source = source,
            showConsentPrompt = showConsentPrompt,
        )
        "org.iso.mdoc", "org-iso-mdoc" -> digitalCredentialsMdocApiProtocol(
            documentTypeRepository = documentTypeRepository,
            presentmentMechanism = mechanism,
            source = source,
            showConsentPrompt = showConsentPrompt,
        )
        else -> throw Error("Protocol ${mechanism.protocol} is not supported")
    }
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun digitalCredentialsOpenID4VPProtocol(
    presentmentMechanism: DigitalCredentialsPresentmentMechanism,
    source: PresentmentSource,
    showConsentPrompt: suspend (
        credentialPresentmentData: CredentialPresentmentData,
        preselectedDocuments: List<Document>,
        requester: Requester,
        trustPoint: TrustPoint?
    ) -> CredentialPresentmentSelection?,
) {
    val version = when (presentmentMechanism.protocol) {
        "openid4vp" -> OpenID4VP.Version.DRAFT_24
        "openid4vp-v1-unsigned", "openid4vp-v1-signed" -> OpenID4VP.Version.DRAFT_29
        else -> throw IllegalStateException("Unexpected protocol ${presentmentMechanism.protocol}")
    }
    var requesterCertChain: X509CertChain? = null
    val preReq = presentmentMechanism.data

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
        preselectedDocuments = presentmentMechanism.preselectedDocuments,
        source = source,
        showConsentPrompt = showConsentPrompt,
        appId = presentmentMechanism.appId,
        origin = presentmentMechanism.origin,
        request = req,
        requesterCertChain = requesterCertChain,
    )
    presentmentMechanism.sendResponse(
        protocol = presentmentMechanism.protocol,
        data = response
    )
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun digitalCredentialsMdocApiProtocol(
    documentTypeRepository: DocumentTypeRepository,
    presentmentMechanism: DigitalCredentialsPresentmentMechanism,
    source: PresentmentSource,
    showConsentPrompt: suspend (
        credentialPresentmentData: CredentialPresentmentData,
        preselectedDocuments: List<Document>,
        requester: Requester,
        trustPoint: TrustPoint?
    ) -> CredentialPresentmentSelection?,
) {
    val arfRequest = presentmentMechanism.data
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
        add(presentmentMechanism.origin)
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
    val encodedSessionTranscript = Cbor.encode(sessionTranscript)

    val deviceResponse = buildDeviceResponse(
        sessionTranscript = sessionTranscript,
        status = DeviceResponse.STATUS_OK,
    ) {
        val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(deviceRequestBase64.fromBase64Url()))
        deviceRequest.verifyReaderAuthentication(sessionTranscript)
        for (docRequest in deviceRequest.docRequests) {
            val zkRequested = docRequest.docRequestInfo?.zkRequest != null

            val request = docRequest.toMdocRequest(
                documentTypeRepository = documentTypeRepository,
                mdocCredential = null,
                requesterOrigin = presentmentMechanism.origin,
                requesterAppId = presentmentMechanism.appId
            )
            val trustPoint = source.findTrustPoint(request.requester)

            val presentmentData = docRequest.getPresentmentData(
                documentTypeRepository = documentTypeRepository,
                source = source,
                keyAgreementPossible = listOf()
            )
            if (presentmentData == null) {
                Logger.w(TAG, "No document found for docType ${docRequest.docType}")
                // No document was found
                continue
            }

            val selection = if (source.skipConsentPrompt) {
                presentmentData.select(presentmentMechanism.preselectedDocuments)
            } else {
                showConsentPrompt(
                    presentmentData,
                    presentmentMechanism.preselectedDocuments,
                    request.requester,
                    trustPoint
                )
            }
            if (selection == null) {
                throw PresentmentCanceled("User canceled at document selection time")
            }

            val match = selection.matches[0]
            val mdocCredential = match.credential as MdocCredential

            var zkSystemMatch: ZkSystem? = null
            var zkSystemSpec: ZkSystemSpec? = null
            if (zkRequested) {
                val requesterSupportedZkSpecs = docRequest.docRequestInfo.zkRequest.systemSpecs
                val zkSystemRepository = source.zkSystemRepository
                if (zkSystemRepository != null) {
                    // Find the first ZK System that the requester supports and matches the document
                    for (zkSpec in requesterSupportedZkSpecs) {
                        val zkSystem = zkSystemRepository.lookup(zkSpec.system)
                        if (zkSystem == null) {
                            continue
                        }

                        val matchingZkSystemSpec = zkSystem.getMatchingSystemSpec(
                            zkSystemSpecs = requesterSupportedZkSpecs,
                            requestedClaims = request.requestedClaims
                        )
                        if (matchingZkSystemSpec != null) {
                            zkSystemMatch = zkSystem
                            zkSystemSpec = matchingZkSystemSpec
                            break
                        }
                    }
                }
            }

            val document = MdocDocument.fromPresentment(
                sessionTranscript = sessionTranscript,
                credential = mdocCredential,
                requestedClaims = request.requestedClaims,
            )
            if (zkSystemMatch != null) {
                val zkDocument = zkSystemMatch.generateProof(
                    zkSystemSpec = zkSystemSpec!!,
                    document = document,
                    sessionTranscript = sessionTranscript
                )
                addZkDocument(zkDocument)
            } else {
                addDocument(document)
            }
            mdocCredential.increaseUsageCount()
        }
    }

    val encrypter = Hpke.getEncrypter(
        cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
        receiverPublicKey = recipientPublicKey,
        info = encodedSessionTranscript
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
    presentmentMechanism.sendResponse(
        protocol = presentmentMechanism.protocol,
        data = data
    )
}
