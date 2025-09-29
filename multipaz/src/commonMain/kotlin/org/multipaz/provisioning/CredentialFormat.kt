package org.multipaz.provisioning

/**
 * Describes a format of a credential.
 */
sealed class CredentialFormat {
    abstract val formatId: String
    data class Mdoc(val docType: String) : CredentialFormat() {
        override val formatId: String get() = "mso_mdoc"
    }

    data class SdJwt(val vct: String) : CredentialFormat() {
        override val formatId: String get() = "dc+sd-jwt"
    }
}