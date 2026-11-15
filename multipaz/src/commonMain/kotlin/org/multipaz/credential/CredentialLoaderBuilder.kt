package org.multipaz.credential

import org.multipaz.document.Document
import org.multipaz.mdoc.credential.KeyBoundMdocCredential
import org.multipaz.mdoc.credential.KeylessMdocCredential
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential

/**
 * Builder for [CredentialLoader].
 *
 * The [CredentialLoaderBuilder] is initially empty. In most cases, well-known [Credential]
 * implementations should be added using [addKeyBoundMdocCredential], [addKeylessMdocCredential],
 * [addKeyBoundSdJwtVcCredential], and [addKeylessSdJwtVcCredential] methods. Additionally,
 * applications may add their own [Credential] implementations.
 */
internal class CredentialLoaderBuilder {
    private val createCredentialFunctions:
            MutableMap<String, suspend (Document) -> Credential> = mutableMapOf()

    /**
     * Adds a new [Credential] implementation to the loader.
     *
     * @param credentialType the credential type
     * @param createCredentialFunction a function to create a [Credential] of the given type.
     */
    fun addCredentialImplementation(
        credentialType: String,
        createCredentialFunction: suspend (Document) -> Credential
    ) {
        if (createCredentialFunctions.contains(credentialType)) {
            throw IllegalArgumentException("'$credentialType' is already registered")
        }
        createCredentialFunctions[credentialType] = createCredentialFunction
    }

    /**
     * Adds [KeyBoundMdocCredential] implementation (and its legacy `"MdocCredential"` type alias) to the loader.
     */
    fun addKeyBoundMdocCredential() {
        addCredentialImplementation(
            credentialType = KeyBoundMdocCredential.CREDENTIAL_TYPE,
            createCredentialFunction = { document -> KeyBoundMdocCredential(document) }
        )
        addCredentialImplementation(
            credentialType = KeyBoundMdocCredential.LEGACY_CREDENTIAL_TYPE,
            createCredentialFunction = { document -> KeyBoundMdocCredential(document) }
        )
    }

    /**
     * Adds [KeylessMdocCredential] implementation to the loader.
     */
    fun addKeylessMdocCredential() {
        addCredentialImplementation(
            credentialType = KeylessMdocCredential.CREDENTIAL_TYPE,
            createCredentialFunction = { document -> KeylessMdocCredential(document) }
        )
    }

    /**
     * Adds [KeyBoundSdJwtVcCredential] implementation to the loader.
     */
    fun addKeyBoundSdJwtVcCredential() {
        addCredentialImplementation(
            credentialType = KeyBoundSdJwtVcCredential.CREDENTIAL_TYPE,
            createCredentialFunction = { document -> KeyBoundSdJwtVcCredential(document) }
        )
    }

    /**
     * Adds [KeylessSdJwtVcCredential] implementation to the loader.
     */
    fun addKeylessSdJwtVcCredential() {
        addCredentialImplementation(
            credentialType = KeylessSdJwtVcCredential.CREDENTIAL_TYPE,
            createCredentialFunction = { document -> KeylessSdJwtVcCredential(document) }
        )
    }

    fun build(): CredentialLoader {
        return CredentialLoader(createCredentialFunctions.toMap())
    }
}