package org.multipaz.openid4vci.credential

import org.multipaz.crypto.EcPublicKey
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.openid4vci.request.wellKnownOpenidCredentialIssuer
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import org.multipaz.rpc.backend.BackendEnvironment

/**
 * Factory for credentials of a particular type.
 *
 * All credentials that this OpenId4VCI server can issue should be registered through
 * [CredentialFactoryRegistry] object injected into the server's [BackendEnvironment].
 *
 * OpenID4VCI metadata (see [wellKnownOpenidCredentialIssuer]) and on the server's main page
 * are generated automatically from [CredentialFactoryRegistry].
 *
 * @param configurationId id that identifies this credential type in OpenID4VCI metadata
 * @param scope scope that identifies this credential type in OpenID4VCI metadata; also
 *  when connected to the System of Record this scope is used to authenticate and obtain
 *  credential data
 * @param format format of the credential issued by this factory
 * @param requireKeyAttestation if this credential can only be bound to an attested key (i.e.
 *  mere proof-of-possession is not acceptable)
 * @param acceptAndroidKeyAttestation if true "android_keystore_attestation" proof type is accepted
 * @param keyMintSecurityLevel minimal accepted security level for Android Keystore Attestation
 * @param acceptAndroidKeyAttestation if true "android_keystore_attestation" proof type is accepted
 * @param proofSigningAlgorithms algorithms that correspond to `proof_signing_alg_values_supported`
 *  property in OpenID4VCI; must be empty for keyless credentials
 * @param cryptographicBindingMethods key binding methods that correspond to
 *  `cryptographic_binding_methods_supported` property in OpenID4VCI; must be empty for keyless
 *  credentials
 * @param name human-readable name for this credential
 * @param logo relative URL for the logo image (normally corresponds to the resource name in
 *  `resources/www`)
 */
interface CredentialFactory {
    val configurationId: String
    val scope: String
    val format: CredentialFormat
    val requireKeyAttestation: Boolean get() = true
    val acceptAndroidKeyAttestation: Boolean get() = false
    val keyMintSecurityLevel: AndroidKeystoreSecurityLevel get() =
        AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT
    val proofSigningAlgorithms: List<String>
    val cryptographicBindingMethods: List<String>
    val name: String
    val logo: String?

    /**
     * [AsymmetricKey] used to sign the new credentials.
     *
     * Only X509-certified keys are supported
     */
    suspend fun getSigningKey(): AsymmetricKey.X509Certified =
        getServerIdentity(ServerIdentity.CREDENTIAL_SIGNING)
     // the key that is used to sign the credential

    /**
     * Initializes the factory and ensures that all the necessary resources are loaded
     */
    suspend fun initialize() {}

    /**
     * Creates the credential.
     *
     * @param systemOfRecordData personal data (typically from the System of Record) necessary to
     *  create the credential
     * @param authenticationKey public portion of the key to which the credential is bound in the
     *  wallet; must be non-null for key-bound credentials and null for keyless ones
     * @param credentialId combination of bucket id and credential index, used to communicate this
     *  credential's status to support revocation
     * @return credential and its creation and expiration times
     */
    suspend fun mint(
        systemOfRecordData: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId,
    ): MintedCredential

    /**
     * Creates display data for credentials issued using given data.
     */
    suspend fun display(systemOfRecordData: DataItem): CredentialDisplay? = null

    companion object {
        /**
         * Default value to use for [proofSigningAlgorithms].
         */
        val DEFAULT_PROOF_SIGNING_ALGORITHMS = listOf("ES256")
    }
}
