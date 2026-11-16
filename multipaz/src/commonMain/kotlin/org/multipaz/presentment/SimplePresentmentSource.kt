package org.multipaz.presentment

import org.multipaz.credential.Credential
import org.multipaz.crypto.EcCurve
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.eventlogger.EventLogger
import org.multipaz.facematch.FaceMatcher
import org.multipaz.mdoc.credential.KeyBoundMdocCredential
import org.multipaz.mdoc.credential.KeylessMdocCredential
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.prompt.ShowConsentPromptFn
import org.multipaz.prompt.promptModelRequestConsent
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import kotlin.time.Clock
import kotlin.time.Instant

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
 * @property requesterHistory a [RequesterHistory] for tracking relying party interactions or `null`.
 * @property getFaceMatcherFn a function returning the [FaceMatcher] to use or `null`.
 * @property resolveTrustFn a function which can be used to determine if a requester is trusted.
 * @property showConsentPrompt a [ShowConsentPromptFn] used show a consent prompt is required.
 * @property preferSignatureToKeyAgreement whether to use mdoc ECDSA authentication even if mdoc MAC authentication
 *   is possible (ISO mdoc only).
 * @property domainsMdocSignature the domains to use for [KeyBoundMdocCredential] instances using
 * mdoc ECDSA authentication, will be tried in order.
 * @property domainsMdocKeyAgreement the domains to use for [KeyBoundMdocCredential] instances
 * using mdoc MAC authentication, will be tried in order.
 * @property domainsKeylessMdoc the domains to use for [KeylessMdocCredential] instances, will be tried in order.
 * @property domainsKeylessSdJwt the domains to use for [KeylessSdJwtVcCredential] instances, will be tried in order.
 * @property domainsKeyBoundSdJwt the domains to use for [KeyBoundSdJwtVcCredential]
 * instances, will be tried in order.
 */
class SimplePresentmentSource(
    override val documentStore: DocumentStore,
    override val documentTypeRepository: DocumentTypeRepository,
    override val zkSystemRepository: ZkSystemRepository? = null,
    override val eventLogger: EventLogger? = null,
    override val requesterHistory: RequesterHistory? = null,
    private val getFaceMatcherFn: () -> FaceMatcher? = { null },
    private val resolveTrustFn: suspend (requester: Requester) -> TrustedRequesterIdentity? = { null },
    private val showConsentPromptFn: ShowConsentPromptFn = ::promptModelRequestConsent,
    private val getBadgesFn: suspend (document: Document) -> List<DocumentBadge> = { document -> emptyList() },
    private val getFaceMatchingModeFn: suspend (
        credential: Credential,
        requesterIdentities: List<RequesterIdentity>
    ) -> FaceMatchingMode = { _, _ -> FaceMatchingMode.ONLY_IF_REQUESTED },
    val preferSignatureToKeyAgreement: Boolean = true,
    val domainsMdocSignature: List<String> = emptyList(),
    val domainsMdocKeyAgreement: List<String> = emptyList(),
    val domainsKeylessMdoc: List<String> = emptyList(),
    val domainsKeylessSdJwt: List<String> = emptyList(),
    val domainsKeyBoundSdJwt: List<String> = emptyList(),
): PresentmentSource(
    documentStore = documentStore,
    documentTypeRepository = documentTypeRepository,
    zkSystemRepository = zkSystemRepository,
    eventLogger = eventLogger,
    requesterHistory = requesterHistory,
) {
    override fun getFaceMatcher(): FaceMatcher? = getFaceMatcherFn()

    override suspend fun resolveTrust(requester: Requester): TrustedRequesterIdentity? {
        return resolveTrustFn(requester)
    }

    override suspend fun showConsentPrompt(
        requester: Requester,
        trustedRequesterIdentity: TrustedRequesterIdentity?,
        presentmentData: PresentmentData,
        preselectedDocuments: List<Document>,
        onDocumentsInFocus: (documents: List<Document>) -> Unit
    ): PresentmentSelection? {
        return showConsentPromptFn(
            requester,
            trustedRequesterIdentity,
            presentmentData,
            preselectedDocuments,
            onDocumentsInFocus
        )
    }

    override suspend fun getBadges(document: Document): List<DocumentBadge> {
        return getBadgesFn(document)
    }

    override suspend fun getFaceMatchingMode(
        credential: Credential,
        requesterIdentities: List<RequesterIdentity>
    ): FaceMatchingMode {
        return getFaceMatchingModeFn(credential, requesterIdentities)
    }

    private suspend inline fun <reified T : Credential> Document.findCredentialOfType(
        domains: List<String>,
        now: Instant,
    ): T? {
        for (domain in domains) {
            var candidate: T? = null
            getCertifiedCredentialsForDomain(domain)
                .filterIsInstance<T>()
                .filter { now >= it.validFrom && now <= it.validUntil }
                .forEach { cred ->
                    if (candidate == null || cred.usageCount < candidate.usageCount) {
                        candidate = cred
                    }
                }
            if (candidate != null) {
                return candidate
            }
        }
        return null
    }

    private suspend fun selectMdocCredential(
        document: Document,
        keyAgreementPossible: List<EcCurve>,
        keyBindingRequired: Boolean,
        now: Instant,
    ): Credential? {
        val sigCred = document.findCredentialOfType<KeyBoundMdocCredential>(domains = domainsMdocSignature, now = now)
        val kaCred = document.findCredentialOfType<KeyBoundMdocCredential>(domains = domainsMdocKeyAgreement, now = now)
        if (!preferSignatureToKeyAgreement && kaCred != null) {
            val keyInfo = kaCred.secureArea.getKeyInfo(kaCred.alias)
            if (keyInfo.algorithm.curve?.let { keyAgreementPossible.contains(it) } == true) {
                return kaCred
            }
        }
        if (sigCred != null) {
            return sigCred
        }
        if (!keyBindingRequired) {
            return document.findCredentialOfType<KeylessMdocCredential>(domains = domainsKeylessMdoc, now = now)
        }
        return null
    }

    private suspend fun selectSdJwtCredential(
        document: Document,
        keyBindingRequired: Boolean,
        now: Instant,
    ): Credential? {
        document.findCredentialOfType<KeyBoundSdJwtVcCredential>(domains = domainsKeyBoundSdJwt, now = now)?.let {
            return it
        }
        if (!keyBindingRequired) {
            return document.findCredentialOfType<KeylessSdJwtVcCredential>(domains = domainsKeylessSdJwt, now = now)
        }
        return null
    }

    override suspend fun selectCredential(
        document: Document,
        requestedClaims: List<RequestedClaim>,
        keyAgreementPossible: List<EcCurve>,
        credential: Credential?,
        keyBindingRequired: Boolean,
    ): Credential? {
        val now = Clock.System.now()
        if (requestedClaims.isNotEmpty()) {
            return when (requestedClaims[0]) {
                is MdocRequestedClaim -> selectMdocCredential(
                    document = document,
                    keyAgreementPossible = keyAgreementPossible,
                    keyBindingRequired = keyBindingRequired,
                    now = now,
                )
                is JsonRequestedClaim -> selectSdJwtCredential(
                    document = document,
                    keyBindingRequired = keyBindingRequired,
                    now = now,
                )
            }
        }
        if (credential != null) {
            return when (credential) {
                is MdocCredential -> selectMdocCredential(
                    document = document,
                    keyAgreementPossible = keyAgreementPossible,
                    keyBindingRequired = keyBindingRequired,
                    now = now,
                )
                is SdJwtVcCredential -> selectSdJwtCredential(
                    document = document,
                    keyBindingRequired = keyBindingRequired,
                    now = now,
                )
                else -> selectMdocCredential(
                    document = document,
                    keyAgreementPossible = keyAgreementPossible,
                    keyBindingRequired = keyBindingRequired,
                    now = now,
                ) ?: selectSdJwtCredential(
                    document = document,
                    keyBindingRequired = keyBindingRequired,
                    now = now,
                )
            }
        }
        return selectMdocCredential(
            document = document,
            keyAgreementPossible = keyAgreementPossible,
            keyBindingRequired = keyBindingRequired,
            now = now,
        ) ?: selectSdJwtCredential(
            document = document,
            keyBindingRequired = keyBindingRequired,
            now = now,
        )
    }

    // Companion object needed for multipaz-swift, see SimplePresentmentSourceExt.swift
    companion object
}
