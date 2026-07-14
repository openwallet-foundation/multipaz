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

package org.multipaz.documenttype

import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.presentment.TransactionData
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim
import org.multipaz.util.fromBase64Url

/**
 * A class that contains the metadata of Document and transaction types.
 *
 * The repository is initially empty, but in the [org.multipaz.documenttype.knowntypes] package
 * there are well known document types which can be added using the [addDocumentType] method.
 *
 * Applications also may add their own document and transaction types.
 */
class DocumentTypeRepository {
    private val _documentTypes = mutableListOf<DocumentType>()
    private val _transactionTypes = mutableListOf<TransactionType<*>>()
    private val _extraSingleDocumentCannedRequests = mutableListOf<SingleDocumentCannedRequest>()

    /**
     * Get all the Document Types that are in the repository.
     */
    val documentTypes: List<DocumentType>
        get() = _documentTypes

    /**
     * Additional sample requests, not necessarily associated with a particular document type.
     */
    val extraSingleDocumentCannedRequests: List<SingleDocumentCannedRequest>
        get() = _extraSingleDocumentCannedRequests
    /**
     * All the transaction types in the repository.
     */
    val transactionTypes: List<TransactionType<*>>
        get() = _transactionTypes

    /**
     * Add a Document Type to the repository.
     *
     * @param documentType the Document Type to add
     */
    fun addDocumentType(documentType: DocumentType) =
        _documentTypes.add(documentType)

    /**
     * Add a [TransactionType] to the registry.
     *
     * @param transactionType new [TransactionType]
     */
    fun addTransactionType(transactionType: TransactionType<*>) {
        for (existingType in transactionTypes) {
            check(existingType.identifier != transactionType.identifier)
            check(existingType.kbJwtResponseClaimName != transactionType.kbJwtResponseClaimName)
            check(existingType.mdocResponseNamespace != transactionType.mdocResponseNamespace)
            check(existingType.mdocRequestInfoKeyName != transactionType.mdocRequestInfoKeyName)
        }
        _transactionTypes.add(transactionType)
    }

    /**
     * Adds a single [SingleDocumentCannedRequest] to the [extraSingleDocumentCannedRequests] list.
     *
     * @param cannedRequest new [SingleDocumentCannedRequest] to add.
     */
    fun addExtraSingleDocumentCannedRequest(cannedRequest: SingleDocumentCannedRequest) {
        _extraSingleDocumentCannedRequests.add(cannedRequest)
    }

    /**
     * Gets the first [DocumentType] in [documentTypes] with a given ISO mdoc doc type.
     *
     * @param mdocDocType the mdoc doc type.
     * @return the [DocumentType] or `null` if not found.
     */
    fun getDocumentTypeForMdoc(mdocDocType: String): DocumentType? =
        _documentTypes.find {
            it.mdocDocumentType?.docType?.equals(mdocDocType) ?: false
        }

    /**
     * Gets the first [DocumentType] in [documentTypes] with a given VCT.
     *
     * @param vct the type e.g. `urn:eudi:pid:1`.
     * @return the [DocumentType] or `null` if not found.
     */
    fun getDocumentTypeForJson(vct: String): DocumentType? =
        _documentTypes.find {
            it.jsonDocumentType?.vct?.equals(vct) ?: false
        }

    /**
     * Gets the first [DocumentType] in [documentTypes] with a given mdoc namespace.
     *
     * @param mdocNamespace the mdoc namespace name.
     * @return the [DocumentType] or null if not found.
     */
    fun getDocumentTypeForMdocNamespace(mdocNamespace: String): DocumentType? {
        for (documentType in _documentTypes) {
            if (documentType.mdocDocumentType == null) {
                continue
            }
            for ((nsName, _) in documentType.mdocDocumentType.namespaces) {
                if (nsName == mdocNamespace) {
                    return documentType
                }
            }
        }
        return null
    }

    /**
     * Looks up a [DocumentAttribute] for a [RequestedClaim].
     *
     * @param requestedClaim a [RequestedClaim].
     * @return the [DocumentAttribute], if found.
     */
    fun getDocumentAttributeForRequestedClaim(requestedClaim: RequestedClaim): DocumentAttribute? {
        when (requestedClaim) {
            is JsonRequestedClaim -> {
                requestedClaim.vctValues.forEach { vct ->
                    val documentType = getDocumentTypeForJson(vct)
                        ?: return null
                    val jsonDocumentType = documentType.jsonDocumentType!!
                    val identifier = requestedClaim.claimPath.toList().joinToString(".") {
                        it.jsonPrimitive.content
                    }
                    return jsonDocumentType.getDocumentAttribute(identifier)
                }
            }
            is MdocRequestedClaim -> {
                val documentType = getDocumentTypeForMdoc(mdocDocType = requestedClaim.docType)
                    ?: getDocumentTypeForMdocNamespace(mdocNamespace = requestedClaim.namespaceName)
                    ?: return null
                val mdocDocumentType = documentType.mdocDocumentType!!
                return mdocDocumentType
                    .namespaces[requestedClaim.namespaceName]
                    ?.dataElements[requestedClaim.dataElementName]
                    ?.attribute
            }
        }
        return null
    }

    /**
     * Find a transaction type by identifier
     *
     * @param identifier transaction identifier, see [TransactionType.identifier]
     * @return registered [TransactionType] or null, if not found in the repository
     */
    fun getTransactionTypeByIdentifier(identifier: String): TransactionType<*>? =
        _transactionTypes.find { it.identifier == identifier }

    /**
     * Parses OpenID4VP JSON-encoded transaction data.
     *
     * @param base64UrlEncodedJson encoded transaction data (array of base64url-encoded items)
     * @return map of credential id to the list of applicable transaction data items
     */
    fun parseJsonTransactions(
        base64UrlEncodedJson: List<String>,
    ): Map<String, List<TransactionData<*>>> {
        val map = mutableMapOf<String, MutableList<TransactionData<*>>>()
        for (base64UrlText in base64UrlEncodedJson) {
            val data = Json.parseToJsonElement(
                base64UrlText.fromBase64Url().decodeToString()
            ).jsonObject
            val credentialIds = (data["credential_ids"] as? JsonArray)
                ?: throw IllegalArgumentException("Missing 'credential_ids' in transaction data")
            val typeId = (data["type"] as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalArgumentException("Missing or invalid 'type' in transaction data")
            val type = getTransactionTypeByIdentifier(typeId)
                ?: throw IllegalArgumentException("Unknown transaction type '$typeId'")
            val parsed = type.parseJson(base64UrlText.encodeToByteString())
            for (id in credentialIds) {
                map.getOrPut(id.jsonPrimitive.content) { mutableListOf() }.add(parsed)
            }
        }
        return map.mapValues { (_, list) -> list.toList() }
    }
}