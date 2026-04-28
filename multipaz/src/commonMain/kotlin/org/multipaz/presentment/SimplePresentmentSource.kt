package org.multipaz.presentment

import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.eventlogger.EventLogger
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.prompt.ShowConsentPromptFn
import org.multipaz.prompt.promptModelRequestConsent
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.time.Clock
import kotlin.time.Instant


private data class CredentialForPresentment(
    val credential: Credential?,
    val credentialKeyAgreement: Credential?
)

/**
 * An implementation of [PresentmentSource] for when using ISO mdoc and IETF SD-JWT VC credentials.
 *
 * This implementation assumes that [Credential]s for a [Document] are organized by _domain_ corresponding to the
 * type of credential.
 *
 * @property documentStore the [DocumentStore] which holds credentials that can be presented.
 * @property documentTypeRepository a [DocumentTypeRepository] which holds metadata for document types.
 * @property zkSystemRepository the [ZkSystemRepository] to use or `null`.
 * @property eventLogger an [EventLogger] for logging events or `null`.
 * @property resolveTrustFn a function which can be used to determine if a requester is trusted.
 * @property showConsentPrompt a [ShowConsentPromptFn] used show a consent prompt is required.
 * @property preferSignatureToKeyAgreement whether to use mdoc ECDSA authentication even if mdoc MAC authentication
 *   is possible (ISO mdoc only).
 * @property domainsMdocSignature the domains to use for [org.multipaz.mdoc.credential.MdocCredential] instances using
 * mdoc ECDSA authentication, will be tried in order.
 * @property domainsMdocKeyAgreement the domains to use for [org.multipaz.mdoc.credential.MdocCredential] instances
 * using mdoc MAC authentication, will be tried in order.
 * @property domainsKeylessSdJwt the domains to use for [KeylessSdJwtVcCredential] instances, will be tried in order.
 * @property domainsKeyBoundSdJwt the domains to use for [org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential]
 * instances, will be tried in order.
 */
class SimplePresentmentSource(
    override val documentStore: DocumentStore,
    override val documentTypeRepository: DocumentTypeRepository,
    override val zkSystemRepository: ZkSystemRepository? = null,
    override val eventLogger: EventLogger? = null,
    private val resolveTrustFn: suspend (requester: Requester) -> TrustMetadata? = { requester -> null },
    private val showConsentPromptFn: ShowConsentPromptFn = ::promptModelRequestConsent,
    private val getBadgesFn: suspend (document: Document) -> List<DocumentBadge> = { document -> emptyList() },
    val preferSignatureToKeyAgreement: Boolean = true,
    val domainsMdocSignature: List<String> = emptyList(),
    val domainsMdocKeyAgreement: List<String> = emptyList(),
    val domainsKeylessSdJwt: List<String> = emptyList(),
    val domainsKeyBoundSdJwt: List<String> = emptyList(),
): PresentmentSource(
    documentStore = documentStore,
    documentTypeRepository = documentTypeRepository,
    zkSystemRepository = zkSystemRepository,
    eventLogger = eventLogger
) {
    override suspend fun resolveTrust(requester: Requester): TrustMetadata? {
        return resolveTrustFn(requester)
    }

    override suspend fun showConsentPrompt(
        requester: Requester,
        trustMetadata: TrustMetadata?,
        credentialPresentmentData: CredentialPresentmentData,
        preselectedDocuments: List<Document>,
        onDocumentsInFocus: (documents: List<Document>) -> Unit
    ): CredentialPresentmentSelection? {
        return showConsentPromptFn(
            requester,
            trustMetadata,
            credentialPresentmentData,
            preselectedDocuments,
            onDocumentsInFocus
        )
    }

    override suspend fun getBadges(document: Document): List<DocumentBadge> {
        return getBadgesFn(document)
    }

    private suspend fun Document.findCredential(domains: List<String>, now: Instant): Credential? {
        for (domain in domains) {
            findCredential(domain, now)?.let {
                return it
            }
        }
        return null
    }

    override suspend fun selectCredential(
        document: Document,
        requestedClaims: List<RequestedClaim>,
        keyAgreementPossible: List<EcCurve>,
    ): Credential? {
        check(requestedClaims.isNotEmpty())
        val now = Clock.System.now()
        val credsForPresentment = when (requestedClaims[0]) {
            is MdocRequestedClaim -> {
                CredentialForPresentment(
                    credential = document.findCredential(domains = domainsMdocSignature, now = now),
                    credentialKeyAgreement = document.findCredential(domains = domainsMdocKeyAgreement, now = now)
                )
            }
            is JsonRequestedClaim -> {
                if (document.getCertifiedCredentials().firstOrNull() is KeylessSdJwtVcCredential) {
                    CredentialForPresentment(
                        credential = document.findCredential(domains = domainsKeylessSdJwt, now = now),
                        credentialKeyAgreement = null
                    )
                } else {
                    CredentialForPresentment(
                        credential = document.findCredential(domains = domainsKeyBoundSdJwt, now = now),
                        credentialKeyAgreement = null
                    )
                }
            }
        }
        if (!preferSignatureToKeyAgreement && credsForPresentment.credentialKeyAgreement != null) {
            credsForPresentment.credentialKeyAgreement as SecureAreaBoundCredential
            val keyInfo = credsForPresentment.credentialKeyAgreement.secureArea.getKeyInfo(
                credsForPresentment.credentialKeyAgreement.alias
            )
            if (keyAgreementPossible.contains(keyInfo.algorithm.curve!!)) {
                return credsForPresentment.credentialKeyAgreement
            }
        }
        return credsForPresentment.credential
    }

    // Companion object needed for multipaz-swift, see SimplePresentmentSourceExt.swift
    companion object
}
