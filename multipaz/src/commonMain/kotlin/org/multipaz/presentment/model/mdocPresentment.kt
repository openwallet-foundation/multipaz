package org.multipaz.presentment.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.claim.Claim
import org.multipaz.claim.findMatchingClaim
import org.multipaz.credential.Credential
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.document.Document
import org.multipaz.document.NameSpacedData
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObjectParser
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DocRequest
import org.multipaz.mdoc.response.DeviceResponseGenerator
import org.multipaz.mdoc.response.DocumentGenerator
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.SimpleCredentialPresentmentData
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Constants
import org.multipaz.util.Logger

private const val TAG = "mdocPresentment"

internal suspend fun mdocPresentment(
    documentTypeRepository: DocumentTypeRepository,
    source: PresentmentSource,
    mechanism: MdocPresentmentMechanism,
    dismissable: MutableStateFlow<Boolean>,
    numRequestsServed: MutableStateFlow<Int>,
    showConsentPrompt: suspend (
        credentialPresentmentData: CredentialPresentmentData,
        preselectedDocuments: List<Document>,
        requester: Requester,
        trustPoint: TrustPoint?
    ) -> CredentialPresentmentSelection?,
) {
    val transport = mechanism.transport
    // Wait until state changes to CONNECTED, FAILED, or CLOSED
    transport.state.first {
        it == MdocTransport.State.CONNECTED ||
                it == MdocTransport.State.FAILED ||
                it == MdocTransport.State.CLOSED
    }
    if (transport.state.value != MdocTransport.State.CONNECTED) {
        throw Error("Expected state CONNECTED but found ${transport.state.value}")
    }
    //Logger.iCbor(TAG, "DeviceEngagement", mechanism.encodedDeviceEngagement.toByteArray())
    try {
        var sessionEncryption: SessionEncryption? = null
        var encodedSessionTranscript: ByteArray? = null
        while (true) {
            Logger.i(TAG, "Waiting for message from reader...")
            dismissable.value = true
            val sessionData = transport.waitForMessage()
            dismissable.value = false
            if (sessionData.isEmpty()) {
                Logger.i(TAG, "Received transport-specific session termination message from reader")
                break
            }

            if (sessionEncryption == null) {
                val eReaderKey = SessionEncryption.getEReaderKey(sessionData)
                encodedSessionTranscript =
                    Cbor.encode(
                        buildCborArray {
                            add(Tagged(24, Bstr(mechanism.encodedDeviceEngagement.toByteArray())))
                            add(Tagged(24, Bstr(eReaderKey.encodedCoseKey)))
                            add(mechanism.handover)
                        }
                    )
                sessionEncryption = SessionEncryption(
                    MdocRole.MDOC,
                    mechanism.eDeviceKey,
                    eReaderKey.publicKey,
                    encodedSessionTranscript,
                )
            }
            val (encodedDeviceRequest, status) = sessionEncryption.decryptMessage(sessionData)

            if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                Logger.i(TAG, "Received session termination message from reader")
                break
            }

            val deviceResponseGenerator = DeviceResponseGenerator(Constants.DEVICE_RESPONSE_STATUS_OK)

            val deviceRequestCbor = Cbor.decode(encodedDeviceRequest!!)
            //Logger.iCbor(TAG, "DeviceRequest", deviceRequestCbor)
            val deviceRequest = DeviceRequest.fromDataItem(deviceRequestCbor)
            deviceRequest.verifyReaderAuthentication(sessionTranscript = RawCbor(encodedSessionTranscript!!))
            for (docRequest in deviceRequest.docRequests) {
                val zkRequested = docRequest.docRequestInfo?.zkRequest != null

                val request = docRequest.toMdocRequest(
                    documentTypeRepository = documentTypeRepository,
                    mdocCredential = null
                )
                val trustPoint = source.findTrustPoint(request.requester)

                val presentmentData = docRequest.getPresentmentData(
                    documentTypeRepository = documentTypeRepository,
                    source = source,
                    keyAgreementPossible = listOf(mechanism.eDeviceKey.curve)
                )
                if (presentmentData == null) {
                    Logger.w(TAG, "No document found for docType ${docRequest.docType}")
                    // No document was found
                    continue
                }
                val selection = if (source.skipConsentPrompt) {
                    presentmentData.select(listOf())
                } else {
                    showConsentPrompt(
                        presentmentData,
                        listOf(),
                        request.requester,
                        trustPoint
                    )
                }
                if (selection == null) {
                    throw PresentmentCanceled("User canceled at document selection time")
                }
                val mdocCredential = selection.matches[0].credential as MdocCredential

                var zkSystemMatch: ZkSystem? = null
                var zkSystemSpec: ZkSystemSpec? = null
                if (zkRequested) {
                    val requesterSupportedZkSpecs = docRequest.docRequestInfo!!.zkRequest!!.systemSpecs
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

                if (zkRequested && zkSystemSpec == null) {
                    Logger.w(TAG, "Reader requested ZK proof but no compatible ZkSpec was found.")
                }

                val documentBytes = calcDocument(
                    credential = mdocCredential,
                    requestedClaims = request.requestedClaims,
                    encodedSessionTranscript = encodedSessionTranscript!!,
                    eReaderKey = SessionEncryption.getEReaderKey(sessionData).publicKey,
                )

                if (zkSystemMatch != null) {
                    val zkDocument = zkSystemMatch.generateProof(
                        zkSystemSpec!!,
                        ByteString(documentBytes),
                        ByteString(encodedSessionTranscript)
                    )

                    deviceResponseGenerator.addZkDocument(zkDocument)
                } else {
                    deviceResponseGenerator.addDocument(documentBytes)
                }

                mdocCredential.increaseUsageCount()
            }

            val encodedDeviceResponse = deviceResponseGenerator.generate()
            transport.sendMessage(
                sessionEncryption.encryptMessage(
                    encodedDeviceResponse,
                    if (!mechanism.allowMultipleRequests) {
                        Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                    } else {
                        null
                    }
                )
            )
            numRequestsServed.value = numRequestsServed.value + 1
            if (!mechanism.allowMultipleRequests) {
                Logger.i(TAG, "Response sent, closing connection")
                break
            } else {
                Logger.i(TAG, "Response sent, keeping connection open")
            }
        }
    } catch (err: MdocTransportClosedException) {
        // Nothing to do, this is thrown when transport.close() is called from another coroutine, that
        // is, the X in the top-right
        err.printStackTrace()
        Logger.i(TAG, "Ending holderJob due to MdocTransportClosedException")
    }
}

// TODO: this is just temporary until we have an equivalent of DcqlQuery.execute() for DeviceRequest
internal suspend fun DocRequest.getPresentmentData(
    documentTypeRepository: DocumentTypeRepository,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
): CredentialPresentmentData? {
    val zkRequested = docRequestInfo?.zkRequest != null
    val requestWithoutFiltering = toMdocRequest(
        documentTypeRepository = documentTypeRepository,
        mdocCredential = null
    )
    val documents = source.getDocumentsMatchingRequest(
        request = requestWithoutFiltering,
    )
    val matches = mutableListOf<Pair<Credential, Map<RequestedClaim, Claim>>>()
    for (document in documents) {
        var zkSystemSpec: ZkSystemSpec? = null
        if (zkRequested) {
            val requesterSupportedZkSpecs = docRequestInfo!!.zkRequest!!.systemSpecs
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
                        requestedClaims = requestWithoutFiltering.requestedClaims
                    )
                    if (matchingZkSystemSpec != null) {
                        zkSystemSpec = matchingZkSystemSpec
                        break
                    }
                }
            }
        }
        if (zkRequested && zkSystemSpec == null) {
            Logger.w(TAG, "Reader requested ZK proof but no compatible ZkSpec was found.")
        }
        val mdocCredential = source.selectCredential(
            document = document,
            request = requestWithoutFiltering,
            // Check is zk is requested and a compatible ZK system spec was found
            keyAgreementPossible = if (zkRequested && zkSystemSpec != null) {
                listOf()
            } else {
                keyAgreementPossible
            }
        ) as MdocCredential?
        if (mdocCredential == null) {
            Logger.w(TAG, "No credential found")
            continue
        }

        val claims = mdocCredential.getClaims(documentTypeRepository)
        val claimsToShow = buildMap {
            for (requestedClaim in requestWithoutFiltering.requestedClaims) {
                claims.findMatchingClaim(requestedClaim)?.let {
                    put(requestedClaim as RequestedClaim, it)
                }
            }
        }
        matches.add(Pair(mdocCredential,claimsToShow))
    }
    return SimpleCredentialPresentmentData(matches)
}

private suspend fun calcDocument(
    credential: MdocCredential,
    requestedClaims: List<MdocRequestedClaim>,
    encodedSessionTranscript: ByteArray,
    eReaderKey: EcPublicKey,
): ByteArray {
    val issuerSigned = Cbor.decode(credential.issuerProvidedData)
    val issuerNamespaces = IssuerNamespaces.fromDataItem(issuerSigned["nameSpaces"])
    val issuerAuthCoseSign1 = issuerSigned["issuerAuth"].asCoseSign1
    val encodedMsoBytes = Cbor.decode(issuerAuthCoseSign1.payload!!)
    val encodedMso = Cbor.encode(encodedMsoBytes.asTaggedEncodedCbor)
    val mso = MobileSecurityObjectParser(encodedMso).parse()

    val documentGenerator = DocumentGenerator(
        mso.docType,
        Cbor.encode(issuerSigned["issuerAuth"]),
        encodedSessionTranscript,
    )
    documentGenerator.setIssuerNamespaces(issuerNamespaces.filter(requestedClaims))

    if (credential.secureArea.getKeyInfo(credential.alias).algorithm.isKeyAgreement) {
        documentGenerator.setDeviceNamespacesMac(
            dataElements = NameSpacedData.Builder().build(),
            secureArea = credential.secureArea,
            keyAlias = credential.alias,
            eReaderKey = eReaderKey,
            unlockReason = PresentmentUnlockReason(credential)
        )
    } else {
        documentGenerator.setDeviceNamespacesSignature(
            NameSpacedData.Builder().build(),
            credential.secureArea,
            credential.alias,
            PresentmentUnlockReason(credential),
        )
    }

    return documentGenerator.generate()
}
