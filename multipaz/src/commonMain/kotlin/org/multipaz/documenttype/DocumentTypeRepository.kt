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

import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.RequestedClaim

/**
 * A class that contains the metadata of Document Types.
 *
 * The repository is initially empty, but in the [org.multipaz.documenttype.knowntypes] package
 * there are well known document types which can be added using the [addDocumentType] method.
 *
 * Applications also may add their own document types.
 */
class DocumentTypeRepository {
    private val _documentTypes: MutableList<DocumentType> = mutableListOf()

    /**
     * Get all the Document Types that are in the repository.
     */
    val documentTypes: List<DocumentType>
        get() = _documentTypes

    /**
     * Add a Document Type to the repository.
     *
     * @param documentType the Document Type to add
     */
    fun addDocumentType(documentType: DocumentType) =
        _documentTypes.add(documentType)

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
}