package org.multipaz.provisioning

import kotlinx.io.bytestring.ByteString
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.AbstractDocumentMetadata
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.DocumentUtil
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.util.Logger
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val TAG = "DocumentProvisioningHandler"

/**
 * Implementation of [AbstractDocumentMetadataHandler] suitable for most uses.
 *
 * This implementation uses [DocumentUtil.managedCredentialHelper] with per-document settings
 * obtained using [getDocumentProvisioningSettings].
 *
 * Applications can fine-tune what kind of credentials to retrieve for a particular document and/or issuer
 * by overriding [getDocumentProvisioningSettings] if the default [DocumentProvisioningSettings] is not suitable.
 *
 * The default settings are to request two domains of credentials, one with user authentication required and a domain
 * for without. This is to enable an optional "pre-consent" experience, with this setup a wallet app can simply check
 * if it has credentials in the no-auth-required domain and if so offer a setting for the user to present the
 * credential to e.g. select RPs without any consent or authentication.
 *
 * However, some issuers will not want to mint credentials without user authentication and will enforce this by
 * e.g. checking the Android Keystore key attestation for whether the key is configured to require user
 * authentication. For such issuers, the application can disable requesting such credentials by tweaking
 * the settings for that particular issuer and/or credential type.
 *
 * @param secureArea credentials will be bound to keys from this [SecureArea]
 * @param documentStore new [Document] will be created in this [DocumentStore]
 * @param metadataHandler interface that initializes and updates document metadata; it may be
 *  provided if [DocumentStore] uses an [AbstractDocumentMetadata] factory (see
 *  [DocumentStore.Builder.setDocumentMetadataFactory]).
 * @param defaultDocumentProvisioningSettings the default [DocumentProvisioningSettings] to use.
 */
open class DocumentProvisioningHandler(
    val secureArea: SecureArea,
    val documentStore: DocumentStore,
    val metadataHandler: AbstractDocumentMetadataHandler? = null,
    val defaultDocumentProvisioningSettings: DocumentProvisioningSettings = DocumentProvisioningSettings()
): AbstractDocumentProvisioningHandler {

    /**
     * Function to select which [DocumentProvisioningSettings] to use when provisioning.
     *
     * The default implementation just returns [defaultDocumentProvisioningSettings], applications can subclass
     * to override on a per-document or per-issuer basis.
     *
     * @param document the [Document] that is being provisioned or refreshed.
     * @param credentialMetadata metadata about the credential from the issuer.
     * @param issuerMetadata the issuer that we're provisioning from.
     * @return the settings to use.
     */
    open suspend fun getDocumentProvisioningSettings(
        document: Document,
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata
    ): DocumentProvisioningSettings = defaultDocumentProvisioningSettings

    override suspend fun createDocument(
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata,
        documentAuthorizationData: ByteString?
    ): Document =
        documentStore.createDocument(
            displayName = credentialMetadata.display.text,
            typeDisplayName = credentialMetadata.display.text,
            cardArt = credentialMetadata.display.logo,
            issuerLogo = issuerMetadata.display.logo,
            authorizationData = documentAuthorizationData,
            metadata = metadataHandler?.initializeDocumentMetadata(
                credentialDisplay = credentialMetadata.display,
                issuerDisplay = issuerMetadata.display,
                authorizationData = documentAuthorizationData
            )
        )

    override suspend fun updateDocument(
        document: Document,
        display: Display?,
        documentAuthorizationData: ByteString?
    ) {
        document.edit {
            if (!provisioned && document.getCertifiedCredentials().isNotEmpty()) {
                provisioned = true
            }
            documentAuthorizationData?.let {
                authorizationData = documentAuthorizationData
            }
            if (display != null) {
                displayName = display.text
                display.logo?.let { cardArt = it }
                metadataHandler?.apply {
                    metadata = updateDocumentMetadata(
                        document = document,
                        credentialDisplay = display
                    )
                }
            }
        }
    }

    override suspend fun cleanupDocumentOnError(document: Document, err: Throwable) {
        documentStore.deleteDocument(document.identifier)
    }

    override suspend fun cleanupCredentialsOnError(
        pendingCredentials: List<Credential>,
        err: Throwable
    ) {
        // Since we're using DocumentUtil.managedCredentialHelper() there is no need to clean up
        // these pending credentials as they'll be reused the next time
    }

    override suspend fun getPendingKeyBoundCredentials(
        document: Document,
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata,
        createKeySettings: CreateKeySettings
    ): List<SecureAreaBoundCredential> {
        val settings = getDocumentProvisioningSettings(document, credentialMetadata, issuerMetadata)
        val now = Clock.System.now()
        val noUserAuthBatchSize = if (settings.requestNoUserAuth) {
            if (settings.requestUserAuth) {
                credentialMetadata.maxBatchSize / 2  // NB: if maxBatchSize = 1, this will be zero
            } else {
                credentialMetadata.maxBatchSize
            }
        } else {
            if (!settings.requestUserAuth) {
                Logger.w(TAG, "Both requestUserAuth and requestNoUserAuth set to false")
                return emptyList()
            }
            0
        }
        if (settings.requestUserAuth) {
            val batchSize = credentialMetadata.maxBatchSize - noUserAuthBatchSize
            doDomain(now, document, settings, createKeySettings, credentialMetadata.format,
                batchSize, true)
        }
        if (settings.requestNoUserAuth) {
            doDomain(now, document, settings, createKeySettings, credentialMetadata.format,
                noUserAuthBatchSize, false)
        }
        if (!settings.requestUserAuth && !settings.requestNoUserAuth) {
            Logger.w(TAG, "Both requestUserAuth and requestNoUserAuth are false, no credentials will be retrieved")
        }
        return document.getPendingCredentials() as List<SecureAreaBoundCredential>
    }

    private suspend fun doDomain(
        now: Instant,
        document: Document,
        settings: DocumentProvisioningSettings,
        createKeySettings: CreateKeySettings,
        format: CredentialFormat,
        maxBatchSize: Int,
        userAuth: Boolean,
    ) {
        val cks = CreateKeySettings(
            algorithm = createKeySettings.algorithm,
            nonce = createKeySettings.nonce,
            userAuthenticationRequired = userAuth,
            userAuthenticationTimeout = if (userAuth) { settings.userAuthTimeout } else { 0.seconds },
            validFrom = null,
            validUntil = null
        )
        val domain = when (format) {
            is CredentialFormat.Mdoc -> if (userAuth) settings.mdocUserAuthDomain else settings.mdocNoUserAuthDomain
            is CredentialFormat.SdJwt -> if (userAuth) settings.sdJwtUserAuthDomain else settings.sdJwtNoUserAuthDomain
        }
        DocumentUtil.managedCredentialHelper(
            document = document,
            domain = domain,
            createCredential = { credentialIdentifierToReplace ->
                when (format) {
                    is CredentialFormat.Mdoc -> {
                        MdocCredential.create(
                            document = document,
                            asReplacementForIdentifier = credentialIdentifierToReplace,
                            domain = domain,
                            secureArea = secureArea,
                            docType = format.docType,
                            createKeySettings = cks
                        )
                    }

                    is CredentialFormat.SdJwt -> {
                        KeyBoundSdJwtVcCredential.create(
                            document = document,
                            asReplacementForIdentifier = credentialIdentifierToReplace,
                            domain = domain,
                            secureArea = secureArea,
                            vct = format.vct,
                            createKeySettings = cks
                        )
                    }
                }
            },
            now = now,
            numCredentials = min(settings.keyBoundCredentialNumPerDomain, maxBatchSize),
            maxUsesPerCredential = settings.keyBoundCredentialMaxUses,
            minValidTime = settings.minValidTime,
            dryRun = false
        )
    }

    override suspend fun getPendingKeylessCredentials(
        document: Document,
        credentialMetadata: CredentialMetadata,
        issuerMetadata: ProvisioningMetadata
    ): List<Credential> {
        val settings = getDocumentProvisioningSettings(document, credentialMetadata, issuerMetadata)
        val now = Clock.System.now()
        DocumentUtil.managedCredentialHelper(
            document = document,
            domain = settings.sdJwtKeylessDomain,
            createCredential = { credentialIdentifierToReplace ->
                // KeylessSdJwtVcCredential is the only keyless credential which is supported.
                require(credentialMetadata.format is CredentialFormat.SdJwt)
                KeylessSdJwtVcCredential.create(
                    document = document,
                    asReplacementForIdentifier = credentialIdentifierToReplace,
                    domain = settings.sdJwtKeylessDomain,
                    vct = credentialMetadata.format.vct
                )
            },
            now = now,
            numCredentials = settings.keylessCredentialNumPerDomain,
            maxUsesPerCredential = settings.keylessCredentialMaxUses,
            minValidTime = settings.minValidTime,
            dryRun = false
        )
        return document.getPendingCredentials()
    }

    /**
     * Manager document metadata when the document is created and when the metadata is updated
     * from the server.
     */
    interface AbstractDocumentMetadataHandler {
        /**
         * Initializes metadata object when the document is first created.
         *
         * @param credentialDisplay display data from the issuer's credential configuration
         * @param issuerDisplay display data for the issuer itself
         * @param authorizationData data for creating a provisioning session later
         */
        suspend fun initializeDocumentMetadata(
            credentialDisplay: Display,
            issuerDisplay: Display,
            authorizationData: ByteString?
        ): AbstractDocumentMetadata?

        /**
         * Updates metadata for the existing document.
         *
         * @param document document being updated
         * @param credentialDisplay customized display data for the provisioned credentials
         */
        suspend fun updateDocumentMetadata(
            document: Document,
            credentialDisplay: Display
        ): AbstractDocumentMetadata?
    }

    // Companion object needed for multipaz-swift, see DocumentProvisioningHandlerExt.swift
    companion object
}