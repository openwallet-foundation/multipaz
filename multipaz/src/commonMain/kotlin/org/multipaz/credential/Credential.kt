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
package org.multipaz.credential

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborBuilder
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.claim.Claim
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.DocumentUtil
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.StorageTableSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.buildCborMap
import kotlin.concurrent.Volatile

/**
 * Base class for credentials.
 *
 * When a [Credential] is created, it is not certified. In order to use the [Credential]
 * for presentation, call [Credential.certify] to certify the credential. This is usually
 * done by sending metadata about the [Credential] to the issuer and getting issuer-signed
 * data back, including the validity-period the credential.
 *
 * An unique identifier for the credential is available in the [Credential.identifier]
 * property which is set at credential creation time.
 *
 * Since credentials can be valid for only a limited time, applications are likely wanting
 * to replace them on a regular basis. To this end, the [Credential] class has the
 * [Credential.replacementForIdentifier] property. For a high-level
 * helper using this infrastructure, see [DocumentUtil.managedCredentialHelper].
 *
 * Each credential has a _domain_ associated with it which is a textual string chosen
 * by the application. This can be used to group credentials together and update a
 * single domain independently of another one. For example, a [Document] may have
 * - 10 [MDocCredential]s for normal use in the domain "mdoc", each requiring user authentication
 *   to present.
 * - 10 [MDocCredential]s for age-attestation in the domain "mdoc_age_attestation" which
 *   are provisioned with only age attributes and the portrait image. These don't require user
 *   authentication to present.
 * At presentation time the application can pick a credential from "mdoc_age_attestation" domain
 * if the verifier's request only includes age attestation. Since user authentication is not
 * required to present these, this could be used to implement a pre-consent flow where the
 * information is shared at the moment the user taps their device against the verifier.
 *
 * A credential may be invalidated and this is tracked in the [Credential.isInvalidated]
 * property. For example this may happen if underlying key material for the credential
 * is no longer usable, see [SecureArea.getKeyInvalidated] for example.
 *
 * [Credential] may be subclassed (for example, see [SecureAreaBoundCredential] and
 * [MdocCredential]) and applications and libraries may bring their own implementations. An
 * application will need to register its implementations with the [CredentialLoader] instance
 * they use with their [DocumentStore] instance.
 *
 * Each concrete implementation of [Credential] must have `constructor(document: Document,
 * dataItem: DataItem)` to construct an instance from serialized data. This is used by
 * [CredentialLoader.loadCredential] which is used when loading a [Document] instance from disk
 * and deserializing its [Credential] instances.
 */
abstract class Credential {
    // NB: when this lock is held, don't attempt to call anything that requires also
    // Document.lock or DocumentStore.lock as it will cause a deadlock, as there are code
    // paths that obtain this lock when Document.lock and possibly DocumentStore.lock are
    // already held.
    private val lock = Mutex()

    /**
     * A stable identifier for the Credential instance.
     */
    internal var _identifier: String? = null

    /**
     * Identifies credential type.
     *
     * Each leaf [Credential] subclass must have a unique type.
     */
    abstract val credentialType: String

    val identifier: String
        get() = _identifier!!

    /**
     * The domain of the credential.
     */
    lateinit var domain: String
        private set

    /**
     * How many times the credential has been used in an identity presentation.
     */
    @Volatile
    var usageCount = 0
        protected set

    /**
     * Indicates whether the credential has been certified yet.
     */
    val isCertified get() = _issuerProvidedData != null

    /**
     * Constructs a new [Credential].
     *
     * @param document the document to add the credential to.
     * @param asReplacementForIdentifier the identifier of the credential this credential
     *     will replace, if not null.
     * @param domain the domain of the credential.
     */
    protected constructor(
        document: Document,
        asReplacementForIdentifier: String?,
        domain: String
    ) {
        this.domain = domain
        this.document = document
        this.replacementForIdentifier = asReplacementForIdentifier
    }

    /**
     * Constructs a Credential from serialized data.
     *
     * [deserialize] providing actual serialized data must be called before using this object.
     *
     * @param document the [Document] that the credential belongs to.
     */
    protected constructor(
        document: Document,
    ) {
        this.document = document
    }

    suspend fun addToDocument() {
        check(_identifier == null)
        val table = document.store.storage.getTable(credentialTableSpec)
        val blob = ByteString(Cbor.encode(toDataItem()))
        document.addCredential(this) {
            _identifier = table.insert(key = null, partitionId = document.identifier, data = blob)
        }
    }

    /**
     * Initialize this object using serialized data.
     */
    open suspend fun deserialize(dataItem: DataItem) {
        domain = dataItem["domain"].asTstr
        usageCount = dataItem["usageCount"].asNumber.toInt()

        if (dataItem.hasKey("data")) {
            _issuerProvidedData = ByteString(dataItem["data"].asBstr)
            _validFrom = Instant.fromEpochMilliseconds(dataItem["validFrom"].asNumber)
            _validUntil = Instant.fromEpochMilliseconds(dataItem["validUntil"].asNumber)
        } else {
            _issuerProvidedData = null
            _validFrom = null
            _validUntil = null
        }

        replacementForIdentifier = dataItem.getOrNull("replacementForAlias")?.asTstr
    }

    /**
     * Indicates whether the credential has been invalidated.
     */
    open suspend fun isInvalidated(): Boolean = false

    /**
     * The issuer-provided data associated with the credential.
     *
     * @throws IllegalStateException if the credential is not yet certified.
     */
    val issuerProvidedData: ByteString
        get() = _issuerProvidedData
            ?: throw IllegalStateException("This credential is not yet certified")
    @Volatile
    private var _issuerProvidedData: ByteString? = null

    /**
     * The point in time the issuer-provided data is valid from.
     *
     * @throws IllegalStateException if the credential is not yet certified.
     */
    val validFrom: Instant
        get() = _validFrom
            ?: throw IllegalStateException("This credential is not yet certified")
    @Volatile
    private var _validFrom: Instant? = null

    /**
     * The point in time the issuer-provided data is valid until.
     *
     * @throws IllegalStateException if the credential is not yet certified.
     */
    val validUntil: Instant
        get() = _validUntil
            ?: throw IllegalStateException("This credential is not yet certified")
    @Volatile
    private var _validUntil: Instant? = null

    /**
     * The [Document] that the credential belongs to.
     */
    val document: Document

    @Volatile
    var replacementForIdentifier: String? = null
        private set

    /**
     * Cleans up additional credential data, such as a [SecureArea] key.
     *
     * NB: credential is deleted from [DocumentStore]-managed storage separately either
     * using [deleteFromStorage] or more efficient [deleteAllFromStorage].
     *
     * After a credential is cleared, it should no longer be used.
     */
    protected open suspend fun clear() {
    }

    /**
     * Deletes the credential from [DocumentStore]-managed storage.
     *
     * After deletion, this object should no longer be used.
     */
    private suspend fun deleteFromStorage() {
        val table = document.store.storage.getTable(credentialTableSpec)
        table.delete(partitionId = document.identifier, key = identifier)
    }

    // Called by Document.deleteCredential
    internal suspend fun deleteCredential() {
        clear()
        deleteFromStorage()
    }

    // Called by Document.deleteDocument
    internal suspend fun clearCredential() = clear()

    /**
     * Increases usage count of the credential.
     *
     * This should be called when a credential has been presented to a verifier.
     */
    suspend fun increaseUsageCount() {
        lock.withLock {
            usageCount += 1
            save()
        }
        document.store.emitOnDocumentChanged(document.identifier)
    }

    /**
     * Certifies the credential.
     *
     * @param issuerProvidedAuthenticationData the issuer-provided static authentication data.
     */
    open suspend fun certify(issuerProvidedAuthenticationData: ByteString) {
        check(!isCertified) { "Credential is already certified" }

        _issuerProvidedData = issuerProvidedAuthenticationData
        val validity = extractValidityFromIssuerData()
        _validFrom = validity.first
        _validUntil = validity.second
        val replacementForIdentifier = lock.withLock {
            val replacementForIdentifier = this.replacementForIdentifier
            this.replacementForIdentifier = null
            save()
            replacementForIdentifier
        }

        if (replacementForIdentifier != null) {
            document.deleteCredential(replacementForIdentifier)
        }
    }

    /**
     * Extract validity from the issuer-provided data
     *
     * Must return the validity range for this credential: first element in the pair is used
     * to initialize [Credential.validFrom] and the second one [Credential.validUntil].
     */
    protected abstract suspend fun extractValidityFromIssuerData(): Pair<Instant, Instant>

    // Deleted identifier for which this one is a replacement
    // Called by Document.deleteCredential()
    suspend fun replacementForDeleted() {
        lock.withLock {
            replacementForIdentifier = null
            save()
        }
    }

    /**
     * Method which can be overridden by [Credential] subclasses to add any additional information
     * when serializing a credential.
     *
     * @param builder a [MapBuilder] which can be used to add data.
     */
    open fun addSerializedData(builder: MapBuilder<CborBuilder>) {}

    /**
     * Serializes the credential.
     *
     * @return a [DataItem] with all of the credential information.
     */
    private fun toDataItem(): DataItem {
        return buildCborMap {
            put("credentialType", credentialType)  // used by CredentialFactory
            put("domain", domain)
            put("usageCount", usageCount.toLong())
            if (replacementForIdentifier != null) {
                put("replacementForAlias", replacementForIdentifier!!)
            }
            if (isCertified) {
                put("data", issuerProvidedData.toByteArray())
                put("validFrom", validFrom.toEpochMilliseconds())
                put("validUntil", validUntil.toEpochMilliseconds())
            }
            addSerializedData(this)
        }
    }

    private suspend fun save() {
        check(lock.isLocked)
        val table = document.store.storage.getTable(credentialTableSpec)
        val blob = ByteString(Cbor.encode(toDataItem()))
        table.update(partitionId = document.identifier, key = identifier, data = blob)
    }

    /**
     * Gets the claims in the credential.
     *
     * If a [DocumentTypeRepository] is passed, it will be used to look up the document type
     * and if a type is found, it'll be used to populate the [Claim.attribute] field of
     * the resulting claims.
     *
     * @param documentTypeRepository a [DocumentTypeRepository] or `null`.
     * @return a list of claims with values.
     */
    abstract suspend fun getClaims(
        documentTypeRepository: DocumentTypeRepository?
    ): List<Claim>

    companion object {
        private const val TAG = "Credential"

        private val credentialTableSpec = StorageTableSpec(
            name = "Credentials",
            supportPartitions = true,  // partition id is document id
            supportExpiration = false
        )


        internal suspend fun deleteAllFromStorage(document: Document) {
            val table = document.store.storage.getTable(credentialTableSpec)
            table.deletePartition(partitionId = document.identifier)
        }

        // Only for use in Document
        internal suspend fun enumerate(document: Document): List<String> {
            val table = document.store.storage.getTable(credentialTableSpec)
            return table.enumerate(partitionId = document.identifier)
        }

        // Only for use in CredentialFactory
        internal suspend fun load(document: Document, identifier: String): ByteString? {
            val table = document.store.storage.getTable(credentialTableSpec)
            return table.get(partitionId = document.identifier, key = identifier)
        }
    }

}