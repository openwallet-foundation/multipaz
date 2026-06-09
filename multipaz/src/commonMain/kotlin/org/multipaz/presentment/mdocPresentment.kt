package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.document.Document
import org.multipaz.eventlogger.EventPresentmentData
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceNamespaces
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.OtherDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.OpenID4VP.processTransactions
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.util.zlibDeflate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

private const val TAG = "mdocPresentment"

/**
 * Present ISO mdoc credentials according to ISO/IEC 18013-5:2021.
 *
 * @param deviceRequest The device request.
 * @param eReaderKey The ephemeral reader key, if available.
 * @param sessionTranscript the session transcript.
 * @param source the source of truth used for presentment.
 * @param keyAgreementPossible the list of curves for which key agreement is possible.
 * @param requesterAppId the appId if an app is making the request or `null`.
 * @param requesterOrigin the origin or `null`.
 * @param creationTime the time to use for `creationTime` when presenting credentials such as SD-JWT+KB VCs.
 * @param preselectedDocuments the list of documents the user may have preselected earlier (for
 *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
 *   if the user didn't preselect.
 * @param onWaitingForUserInput called when waiting for input from the user (consent or authentication)
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @return a [MdocResponse] containing [DeviceResponse] and [EventPresentmentData].
 * @throws PresentmentCanceledException if the user canceled in a consent prompt.
 * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    MdocTransportClosedException::class,
    Iso18013PresentmentTimeoutException::class,
    PresentmentCanceledException::class,
    PresentmentCannotSatisfyRequestException::class
)
suspend fun mdocPresentment(
    deviceRequest: DeviceRequest,
    eReaderKey: EcPublicKey?,
    sessionTranscript: DataItem,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    requesterAppId: String?,
    requesterOrigin: String?,
    creationTime: Instant = Clock.System.now(),
    preselectedDocuments: List<Document> = emptyList(),
    onWaitingForUserInput: () -> Unit = {},
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): MdocResponse {
    val credentialsPresented = mutableSetOf<SecureAreaBoundCredential>()
    lateinit var eventData: EventPresentmentData
    if (Logger.isDebugEnabled) {
        Logger.dCbor(TAG, "DeviceRequest", deviceRequest.toDataItem())
    }

    val deviceResponse = buildDeviceResponse(
        sessionTranscript = sessionTranscript,
        status = DeviceResponse.STATUS_OK,
        eReaderKey = eReaderKey,
    ) {
        val iso18013Response = try {
            deviceRequest.execute(
                presentmentSource = source,
                keyAgreementPossible = keyAgreementPossible,
            )
        } catch (e: Iso18015ResponseException) {
            throw PresentmentCannotSatisfyRequestException("Error satisfying the request", e)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw IllegalStateException("Error satisfying request", e)
        }
        val requester = Requester(
            certChain = deviceRequest.getRequester(),
            appId = requesterAppId,
            origin = requesterOrigin,
        )
        onWaitingForUserInput()
        val trustMetadata = source.resolveTrust(requester)
        val selection = source.showConsentPrompt(
            requester = requester,
            trustMetadata = trustMetadata,
            consentData = ConsentData.fromCredentialQueryResult(
                credentialQueryResult = iso18013Response,
                source = source
            ),
            preselectedDocuments = preselectedDocuments,
            onDocumentsInFocus = onDocumentsInFocus
        )
        if (selection == null) {
            throw PresentmentCanceledException("User canceled consent prompt")
        }

        for (match in selection.matches) {
            match.source as CredentialMatchSourceIso18013
            val zkRequested = match.source.docRequest.docRequestInfo?.zkRequest != null

            var zkSystemMatch: ZkSystem? = null
            var zkSystemSpec: ZkSystemSpec? = null
            if (zkRequested) {
                val requesterSupportedZkSpecs = match.source.docRequest.docRequestInfo.zkRequest.systemSpecs
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
                            requestedClaims = match.claims.keys.toList()
                        )
                        if (matchingZkSystemSpec != null) {
                            zkSystemMatch = zkSystem
                            zkSystemSpec = matchingZkSystemSpec
                            break
                        }
                    }
                }
            }

            if (zkRequested && zkSystemSpec == null) {
                Logger.w(TAG, "Reader requested ZK proof but no compatible ZkSpec was found.")
            }

            // The session transcript to use depends on whether the response is to be encrypted
            val sessionTranscriptToUse = match.source.docRequest.docRequestInfo?.docResponseEncryption?.let {
                buildCborArray {
                    add(sessionTranscript.asArray[0])
                    add(Tagged(
                        tagNumber = Tagged.ENCODED_CBOR,
                        taggedItem = Bstr(Cbor.encode(it.dataItem))
                    ))
                    add(sessionTranscript.asArray[2])
                }
            } ?: sessionTranscript

            when (match.credential) {
                is MdocCredential -> {
                    val document = MdocDocument.fromPresentment(
                        sessionTranscript = sessionTranscriptToUse,
                        eReaderKey = eReaderKey,
                        credential = match.credential,
                        requestedClaims = match.claims.keys.toList() as List<MdocRequestedClaim>,
                        deviceNamespaces = computeTransactionResponse(match),
                        errors = mapOf()
                    )

                    if (zkSystemMatch != null) {
                        val zkDocument = zkSystemMatch.generateProof(
                            zkSystemSpec = zkSystemSpec!!,
                            document = document,
                            sessionTranscript = sessionTranscriptToUse
                        )
                        match.source.docRequest.docRequestInfo?.docResponseEncryption?.let { encryptionParameters ->
                            addEncryptedDocuments(
                                encryptionParameters = encryptionParameters,
                                docRequestId = match.source.docRequest.docRequestId
                            ) {
                                addZkDocument(zkDocument)
                            }
                        } ?: addZkDocument(zkDocument)
                    } else {
                        match.source.docRequest.docRequestInfo?.docResponseEncryption?.let { encryptionParameters ->
                            addEncryptedDocuments(
                                encryptionParameters = encryptionParameters,
                                docRequestId = match.source.docRequest.docRequestId
                            ) {
                                addDocument(document)
                            }
                        } ?: addDocument(document)
                    }
                }
                is KeyBoundSdJwtVcCredential -> {
                    val sessionTranscriptToUseBytes = Tagged(
                        tagNumber = Tagged.ENCODED_CBOR,
                        taggedItem = Bstr(Cbor.encode(sessionTranscriptToUse))
                    )
                    // The audience depends on readerAuthAll and/or readerAuth
                    val audience = if (deviceRequest.readerAuthAll.isNotEmpty()) {
                        val listOfReaderAuthAll = buildCborArray {
                            deviceRequest.readerAuthAll.forEach {
                                add(it.toDataItem())
                            }
                        }
                        Crypto.digest(
                            algorithm = Algorithm.SHA256,
                            message = Cbor.encode(
                                Tagged(
                                    tagNumber = Tagged.ENCODED_CBOR,
                                    taggedItem = Bstr(Cbor.encode(listOfReaderAuthAll))
                                )
                            )
                        ).toBase64Url()
                    } else if (match.source.docRequest.readerAuth != null) {
                        Crypto.digest(
                            algorithm = Algorithm.SHA256,
                            message = Cbor.encode(
                                Tagged(
                                    tagNumber = Tagged.ENCODED_CBOR,
                                    taggedItem = Bstr(Cbor.encode(match.source.docRequest.readerAuth!!.toDataItem()))
                                )
                            )
                        ).toBase64Url()
                    } else {
                        "none"
                    }
                    val sdJwtVc = SdJwt.fromCompactSerialization(match.credential.issuerProvidedData.decodeToString())
                    val pathsToDisclose = match.claims.map { (requestedClaim, _) ->
                        requestedClaim as MdocRequestedClaim
                        check(requestedClaim.namespaceName == "_") {
                            "Expected _ for namespace, got ${requestedClaim.namespaceName}"
                        }
                        val path = match.source.docRequest.docRequestInfo?.dataElementIdentifierMapping[requestedClaim.dataElementName]
                        check(path != null) {
                            "No path for data element ${requestedClaim.dataElementName}"
                        }
                        path
                    }
                    val filteredSdJwtVc = sdJwtVc.filter(pathsToDisclose)
                    val transactionResponse = processTransactions(
                        credential = match.credential,
                        transactionData = match.transactionData,
                        docRequestId = match.source.docRequest.docRequestId
                    )
                    val sdJwtKb = filteredSdJwtVc.present(
                        signingKey = AsymmetricKey.AnonymousSecureAreaBased(
                            alias = match.credential.alias,
                            secureArea = match.credential.secureArea,
                            keyInfo = match.credential.secureArea.getKeyInfo(match.credential.alias),
                            unlockReason = PresentmentUnlockReason(match.credential),
                        ),
                        nonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(sessionTranscriptToUseBytes)).toBase64Url(),
                        audience = audience,
                        creationTime = creationTime
                    ) {
                        if (!match.transactionData.isEmpty()) {
                            for ((key, response) in transactionResponse) {
                                put(key, response)
                            }
                        }
                    }
                    val otherDocument = OtherDocument(
                        docFormat = "sd-jwt+kb",
                        data = ByteString(sdJwtKb.compactSerialization.encodeToByteArray().zlibDeflate())
                    )
                    match.source.docRequest.docRequestInfo?.docResponseEncryption?.let { encryptionParameters ->
                        addEncryptedDocuments(
                            encryptionParameters = encryptionParameters,
                            docRequestId = match.source.docRequest.docRequestId
                        ) {
                            addOtherDocument(otherDocument)
                        }
                    } ?: addOtherDocument(otherDocument)
                }
                else -> throw IllegalStateException("No support for presenting credential of type ${match.credential.credentialType}")
            }

            match.credential.increaseUsageCount()
            credentialsPresented.add(match.credential)
        }

        eventData = EventPresentmentData.fromPresentmentSelection(
            selection = selection,
            requester = requester,
            trustMetadata = trustMetadata
        )
    }
    if (Logger.isDebugEnabled) {
        Logger.dCbor(TAG, "DeviceResponse", deviceResponse.toDataItem())
    }
    return MdocResponse(
        deviceResponse = deviceResponse,
        eventData = eventData
    )
}

internal suspend fun computeTransactionResponse(
    match: CredentialPresentmentSetOptionMemberMatch
): DeviceNamespaces {
    val transactionResponseMap = match.transactionData.associate { transaction ->
        Pair(transaction.type.mdocResponseNamespace, buildMap {
            val alg = transaction.getHashAlgorithm()
            alg?.let {
                put("transaction_data_hash_alg", it.coseAlgorithmIdentifier!!.toDataItem())
            }
            put("transaction_data_hash",
                transaction.getHash(alg ?: Algorithm.SHA256).toByteArray().toDataItem())
            (match.source as? CredentialMatchSourceIso18013)?.let { source ->
                // This is generally not available anywhere is the ISO 18013 response,
                // but it is needed to verify the transaction, so we keep it in the
                // transaction response.
                put("doc_request_id", source.docRequest.docRequestId.toDataItem())
            }
            transaction.type.applyCbor(
                transactionData = transaction,
                credential = match.credential
            )?.let { extra ->
                for ((key, value) in extra) {
                    put(key, value)
                }
            }
        })
    }
    return buildDeviceNamespaces {
        for ((namespace, values) in transactionResponseMap) {
            addNamespace(namespace) {
                for ((key, value) in values) {
                    addDataElement(key, value)
                }
            }
        }
    }
}