package org.multipaz.presentment.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.claim.Claim
import org.multipaz.claim.findMatchingClaim
import org.multipaz.credential.Credential
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DocRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.EReaderKey
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.presentment.SimpleCredentialPresentmentData
import org.multipaz.request.RequestedClaim
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "mdocPresentment"

/**
 * Present ISO mdoc credentials according to ISO/IEC 18013-5:2021.
 *
 * @param deviceRequest The device request.
 * @param eReaderKey The ephemeral reader key, if available.
 * @param sessionTranscript the session transcript.
 * @param source the source of truth used for presentment.
 * @param keyAgreementPossible the list of curves for which key agreement is possible.
 * @param onWaitingForUserInput called when waiting for input from the user (consent or authentication)
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @return a [DeviceResponse].
 * @throws PresentmentCanceled if the user canceled in a consent prompt.
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    MdocTransportClosedException::class,
    Iso18013PresentmentTimeoutException::class,
    PresentmentCanceled::class
)
suspend fun mdocPresentment(
    deviceRequest: DeviceRequest,
    eReaderKey: EcPublicKey?,
    sessionTranscript: DataItem,
    source: PresentmentSource,
    keyAgreementPossible: List<EcCurve>,
    onWaitingForUserInput: () -> Unit = {},
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): DeviceResponse {
    deviceRequest.verifyReaderAuthentication(sessionTranscript = sessionTranscript)
    // TODO: transfer deviceRequest into a ISO 18013-5 Second Edition-specific CredentialPresentmentData
    //   so multiple document requests will appear in a single consent prompt.
    //
    return buildDeviceResponse(
        sessionTranscript = sessionTranscript,
        status = DeviceResponse.STATUS_OK,
        eReaderKey = eReaderKey,
    ) {
        for (docRequest in deviceRequest.docRequests) {
            val zkRequested = docRequest.docRequestInfo?.zkRequest != null

            val request = docRequest.toMdocRequest(
                documentTypeRepository = source.documentTypeRepository,
                mdocCredential = null
            )

            val presentmentData = docRequest.getPresentmentData(
                documentTypeRepository = source.documentTypeRepository,
                source = source,
                keyAgreementPossible = keyAgreementPossible
            )
            if (presentmentData == null) {
                Logger.w(TAG, "No document found for docType ${docRequest.docType}")
                // No document was found
                continue
            }
            onWaitingForUserInput()
            val selection = source.showConsentPrompt(
                requester = request.requester,
                trustMetadata = source.resolveTrust(request.requester),
                credentialPresentmentData = presentmentData,
                preselectedDocuments = emptyList(),
                onDocumentsInFocus = onDocumentsInFocus
            )
            if (selection == null) {
                throw PresentmentCanceled("User canceled consent prompt")
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

            val document = MdocDocument.fromPresentment(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                credential = mdocCredential,
                requestedClaims = request.requestedClaims,
                deviceNamespaces = buildDeviceNamespaces {},
                errors = mapOf()
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
}

// TODO: this is just temporary until we have an equivalent of DcqlQuery.execute() for DeviceRequest
suspend fun DocRequest.getPresentmentData(
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
            val requesterSupportedZkSpecs = docRequestInfo.zkRequest.systemSpecs
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
    if (matches.isEmpty()) {
        throw IllegalStateException("No credentials matching request")
    }
    return SimpleCredentialPresentmentData(matches)
}
