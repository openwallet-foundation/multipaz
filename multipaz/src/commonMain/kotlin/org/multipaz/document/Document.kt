/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.document

import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import kotlin.concurrent.Volatile

/**
 * This class represents a document created in [DocumentStore].
 *
 * [Document] can be created using [DocumentStore.createDocument]. Once a [Document] is created
 * it persists in the storage and can be looked up using [DocumentStore.lookupDocument] even
 * after the application restarts. [Document] can be deleted using [DocumentStore.deleteDocument]
 * method.
 *
 * Documents in this store are identified by an identifier [Document.identifier] which is
 * automatically assigned and is unique per document in a [DocumentStore].
 *
 * Arbitrary data can be stored in documents using the [AbstractDocumentMetadata] returned
 * by [metadata]. Applications that use this library should supply their [AbstractDocumentMetadata]
 * factory using [DocumentStore.Builder.setDocumentMetadataFactory] if the built-in
 * [DocumentMetadata] isn't sufficient.
 *
 * Each document may have a number of *Credentials*
 * associated with it. These credentials are intended to be used in ways specified by the
 * underlying document format but the general idea is that they are created on
 * the device and then sent to the issuer for certification. The issuer then returns
 * some format-specific data related to the credential.
 *
 * Using Mobile Driving License and MDOCs according
 * to [ISO/IEC 18013-5:2021](https://www.iso.org/standard/69084.html) as an example,
 * the credential plays the role of *DeviceKey* and the issuer-signed
 * data includes the *Mobile Security Object* which includes the
 * credential and is signed by the issuer. This is used for anti-cloning and to return data signed
 * by the device. The way it works in this API is that the application can create
 * one of [SecureAreaBoundCredential] subclasses, typically using a companion `create` method.
 * With this in hand, the application can use [SecureAreaBoundCredential.getAttestation] and send
 * the attestation to the issuer for certification. The issuer will then craft document-format
 * specific data (for ISO/IEC 18013-5:2021 it will be a signed MSO which references
 * the public part of the newly created credential) and send it back
 * to the app. The application can then call
 * [Credential.certify] which would add any issuer provided authentication data to the
 * credential and make it ready for use in presentation. To retrieve all credentials
 * which still require certification, use [getPendingCredentials], and to retrieve all
 * certified credentials, use [getCertifiedCredentials].
 *
 * At document presentation time the application first receives the request
 * from a remote reader using a specific document presentation protocol, such
 * as ISO/IEC 18013-5:2021. The details of the document-specific request includes
 * enough information (for example, the *DocType* if using ISO/IEC 18013-5:2021)
 * for the application to locate a suitable [Document] from a [DocumentStore].
 * See [DocumentRequest] for more information about how to generate the response for
 * the remote reader given a [Document] instance.
 *
 * There is nothing mDL/MDOC specific about this type, it can be used for any kind
 * of document regardless of format, presentation, or issuance protocol used.
 *
 * @param store the [DocumentStore] that holds this [Document].
 * @param identifier the persistent id of the document which can be used with [DocumentStore].
 */
class Document internal constructor(
    internal val store: DocumentStore,
    val identifier: String,
) {
    // Protects credentialCache and allCredentialsLoaded
    // NB: when this lock is held, don't attempt to call anything that requires also
    // DocumentStore.lock, as it will cause a deadlock, as there are code paths that
    // obtain this lock when DocumentStore.lock is already held.
    private val lock = Mutex()
    private val credentialCache = mutableMapOf<String, Credential>()
    private var allCredentialsLoaded = false
    lateinit var metadata: AbstractDocumentMetadata
        internal set

    @Volatile
    internal var deleted = false
        private set

    /** Clear cached credentials; only used for testing */
    internal suspend fun deleteCache() = lock.withLock {
        credentialCache.clear()
    }

    /**
     * Returns the list of identifiers for all credentials for this document.
     */
    suspend fun getCredentialIdentifiers(): List<String> {
        return Credential.enumerate(this)
    }

    /**
     * Returns the list of all credentials for this document.
     */
    suspend fun getCredentials(): List<Credential> {
        return lock.withLock {
            if (!allCredentialsLoaded) {
                for (credentialIdentifier in getCredentialIdentifiers()) {
                    lookupCredentialNoLock(credentialIdentifier)
                }
                allCredentialsLoaded = true
            }
            credentialCache.values.toList()
        }
    }

    /**
     * Returns the credential with the given identifier.
     */
    suspend fun lookupCredential(credentialIdentifier: String): Credential? {
        return lock.withLock {
            lookupCredentialNoLock(credentialIdentifier)
        }
    }

    private suspend fun lookupCredentialNoLock(credentialIdentifier: String): Credential? {
        check(lock.isLocked)
        return credentialCache.getOrPut(credentialIdentifier) {
            store.credentialLoader.loadCredential(this, credentialIdentifier)
                ?: return null
        }
    }

    // Called from Credential.addToDocument, init block is executed behind identity
    // cache lock before attempting to add to the cache.
    internal suspend fun addCredential(
        newCredential: Credential,
        init: suspend ()-> Unit
    ) {
        lock.withLock {
            init()
            check(!credentialCache.containsKey(newCredential.identifier))
            credentialCache[newCredential.identifier] = newCredential
        }
        store.emitOnDocumentChanged(identifier)
    }

    /**
     * Returns the list of all pending credentials.
     */
    suspend fun getPendingCredentials(): List<Credential> {
        return getCredentials().filter { !it.isCertified }
    }

    /**
     * Returns the list of all pending credentials for the given domain.
     */
    suspend fun getPendingCredentialsForDomain(domain: String): List<Credential> {
        return getCredentials().filter { !it.isCertified && it.domain == domain }
    }

    /**
     * Returns the credential which is a replacement for the credential with the given
     * [credentialIdentifier].
     */
    suspend fun getReplacementCredentialFor(credentialIdentifier: String): Credential? {
        return getCredentials().firstOrNull { it.replacementForIdentifier == credentialIdentifier }
    }

    /**
     * Returns all certified credentials.
     */
    suspend fun getCertifiedCredentials(): List<Credential> {
        return getCredentials().filter { it.isCertified }
    }

    /**
     * Returns all certified credentials for the given domain.
     */
    suspend fun getCertifiedCredentialsForDomain(domain: String): List<Credential> {
        return getCredentials().filter { it.isCertified && it.domain == domain }
    }

    internal suspend fun saveMetadata(blob: ByteString) {
        if (deleted) {
            Logger.w(TAG, "Attempt to save deleted document '$identifier'")
        } else {
            store.getDocumentTable().update(identifier, blob)
            store.emitOnDocumentChanged(identifier)
        }
    }

    // Called from DocumentStore.deleteDocument
    internal suspend fun deleteDocument() {
        deleted = true
        store.emitOnDocumentDeleted(identifier)
        for (credential in getCredentials()) {
            credential.deleteCredential()
        }
        val documentTable = store.getDocumentTable()
        lock.withLock {
            credentialCache.clear()
            documentTable.delete(identifier)
        }
    }

    /**
     * Deletes the credential with the given identifier.
     */
    suspend fun deleteCredential(credentialIdentifier: String) {
        lookupCredential(credentialIdentifier)?.let { credential ->
            lock.withLock {
                credential.deleteCredential()
                credentialCache.remove(credential.identifier)
            }
        }
        getReplacementCredentialFor(credentialIdentifier)?.replacementForDeleted()
    }

    /**
     * Finds a suitable certified credential to use.
     *
     * @param domain The domain to pick the credential from.
     * @param now Pass current time to ensure that the selected slot's validity period or
     * `null` to not consider validity times.
     * @return A credential in the domain or `null` if none was found.
     */
    suspend fun findCredential(
        domain: String,
        now: Instant?,
    ): Credential? {
        var candidate: Credential? = null
        getCredentials().filter {
            it.isCertified && it.domain == domain && (
                    now == null || (now >= it.validFrom && now <= it.validUntil)
                    )
        }.forEach { credential ->
            // If we already have a candidate, prefer this one if its usage count is lower
            candidate?.let { candidateCredential ->
                if (credential.usageCount < candidateCredential.usageCount) {
                    candidate = credential
                }
            } ?: run {
                candidate = credential
            }

        }
        return candidate
    }

    /**
     * Goes through all credentials and deletes the ones which are invalidated.
     */
    suspend fun deleteInvalidatedCredentials() {
        for (pendingCredential in getCredentials()) {
            deleteCredentialIfInvalidated(pendingCredential)
        }
    }

    private suspend fun deleteCredentialIfInvalidated(credential: Credential) {
        try {
            if (credential.isInvalidated()) {
                Logger.i(TAG, "Deleting invalidated credential ${credential.identifier}")
                deleteCredential(credential.identifier)
            }
        } catch (err: IllegalArgumentException) {
            // TODO: watch this and figure out what causes this state (race condition?)
            // Once we are in this state, there is no other good way to recover. It is important
            // that secure area implementations do not use IllegalArgumentException for transient
            // errors (like server connections).
            Logger.e(TAG, "Error accessing credential ${credential.identifier}, deleting it", err)
            deleteCredential(credential.identifier)
        }
    }

    /**
     * Returns whether a usable credential exists at a given point in time.
     *
     * @param at the point in time to check for.
     * @returns `true` if an usable credential exists for the given time, `false` otherwise
     */
    suspend fun hasUsableCredential(at: Instant = Clock.System.now()): Boolean {
        val credentials = getCertifiedCredentials()
        if (credentials.isEmpty()) {
            return false
        }
        for (credential in credentials) {
            if (at >= credential.validFrom && at < credential.validUntil) {
                return true
            }
        }
        return false
    }

    data class UsableCredentialResult(
        val numCredentials: Int,
        val numCredentialsAvailable: Int
    )

    /**
     * Returns whether an usable credential exists at a given point in time.
     *
     * @param at the point in time to check for.
     * @returns `true` if an usable credential exists for the given time, `false` otherwise
     */
    suspend fun countUsableCredentials(at: Instant = Clock.System.now()): UsableCredentialResult {
        val credentials = getCertifiedCredentials()
        if (credentials.isEmpty()) {
            return UsableCredentialResult(0, 0)
        }
        var numCredentials = 0
        var numCredentialsAvailable = 0
        for (credential in credentials) {
            numCredentials++
            val validFrom = credential.validFrom
            val validUntil = credential.validUntil
            if (at >= validFrom && at < validUntil) {
                if (credential.usageCount == 0) {
                    numCredentialsAvailable++
                }
            }
        }
        return UsableCredentialResult(numCredentials, numCredentialsAvailable)
    }

    companion object {
        private const val TAG = "Document"

        val defaultTableSpec = StorageTableSpec(
            name = "Documents",
            supportPartitions = false,
            supportExpiration = false
        )
    }
}