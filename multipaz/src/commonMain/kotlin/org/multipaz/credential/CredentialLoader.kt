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
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import kotlin.reflect.KClass

/**
 * A class that aids in creation of credentials from serialized data.
 *
 * Use [CredentialLoaderBuilder] class to build one
 */
internal class CredentialLoader(
    private val createCredentialFunctions: Map<String, suspend (Document) -> Credential>
) {
    /**
     * Loads a [Credential] from storage
     *
     * @param document The document associated with the credential
     * @param identifier Credential identifier
     * @return a credential instance
     * @throws IllegalStateException if there is no registered type for the serialized data.
     */
    suspend fun loadCredential(document: Document, identifier: String): Credential? {
        val blob = Credential.load(document, identifier) ?: return null
        val dataItem = Cbor.decode(blob.toByteArray())
        val credentialType = dataItem["credentialType"].asTstr
        val createCredentialFunction = createCredentialFunctions[credentialType]
            ?: throw IllegalStateException(
                "Credential type '$credentialType' not registered, have: ${createCredentialFunctions.keys.joinToString(", ") { "'$it'" }}")
        val credential = createCredentialFunction.invoke(document)
        credential._identifier = identifier
        credential.deserialize(dataItem)
        if (credential.credentialType != credentialType) {
            throw IllegalStateException(
                "Inconsistent credential type: '$credentialType' vs '${credential.credentialType}")
        }
        return credential
    }
}