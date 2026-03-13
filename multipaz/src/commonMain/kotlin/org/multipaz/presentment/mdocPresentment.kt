package org.multipaz.presentment

import org.multipaz.cbor.DataItem
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.document.Document
import org.multipaz.eventlogger.EventPresentmentData
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.Iso18015ResponseException
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
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
 * @param requesterAppId the appId if an app is making the request or `null`.
 * @param requesterOrigin the origin or `null`.
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
    preselectedDocuments: List<Document> = emptyList(),
    onWaitingForUserInput: () -> Unit = {},
    onDocumentsInFocus: (documents: List<Document>) -> Unit
): MdocResponse {
    val credentialsPresented = mutableSetOf<MdocCredential>()
    lateinit var eventData: EventPresentmentData

    val deviceResponse = buildDeviceResponse(
        sessionTranscript = sessionTranscript,
        status = DeviceResponse.STATUS_OK,
        eReaderKey = eReaderKey,
    ) {
        val presentmentData = try {
            deviceRequest.execute(
                presentmentSource = source,
                keyAgreementPossible = keyAgreementPossible
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
            credentialPresentmentData = presentmentData,
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

            val document = MdocDocument.fromPresentment(
                sessionTranscript = sessionTranscript,
                eReaderKey = eReaderKey,
                credential = match.credential as MdocCredential,
                requestedClaims = match.claims.keys.toList() as List<MdocRequestedClaim>,
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
            match.credential.increaseUsageCount()
            credentialsPresented.add(match.credential)
        }

        eventData = EventPresentmentData.fromPresentmentSelection(
            selection = selection,
            requester = requester,
            trustMetadata = trustMetadata
        )
    }
    return MdocResponse(
        deviceResponse = deviceResponse,
        eventData = eventData
    )
}
