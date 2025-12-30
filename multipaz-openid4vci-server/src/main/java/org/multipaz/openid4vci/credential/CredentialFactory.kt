package org.multipaz.openid4vci.credential

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.crypto.EcPublicKey
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.cbor.DataItem
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.Loyalty
import org.multipaz.openid4vci.request.wellKnownOpenidCredentialIssuer
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity

/**
 * Factory for credentials of a particular type.
 *
 * All credentials that this OpenId4VCI server can issue should be registered here (see
 * this class companion object's `init`). Corresponding entries will appear in the server
 * metadata (see [wellKnownOpenidCredentialIssuer]) and on the server's main page
 * automatically.
 */
internal interface CredentialFactory {
    val offerId: String
    val scope: String
    val format: CredentialFormat
    val requireKeyAttestation: Boolean get() = true
    val proofSigningAlgorithms: List<String>  // must be empty for keyless credentials
    val cryptographicBindingMethods: List<String>  // must be empty for keyless credentials
    val name: String  // human-readable name
    val logo: String?  // relative URL for the image

    suspend fun getSigningKey(): AsymmetricKey.X509Certified =
        getServerIdentity(ServerIdentity.CREDENTIAL_SIGNING)
     // the key that is used to sign the credential

    /**
     * Ensures that resources are loaded
     */
    suspend fun initialize() {}

    /**
     * Creates the credential.
     *
     * @param data personal data (typically from the System of Record) necessary to create the
     *    credential
     * @param authenticationKey public portion of the key to which the credential is bound in the
     *    wallet; must be non-null for key-bound credentials and null for keyless ones
     * @param credentialId combination of bucket id and credential index, used to communicate this
     *    credential's status to support revocation
     * @return credential and its creation and expiration times
     */
    suspend fun mint(
        data: DataItem,
        authenticationKey: EcPublicKey?,
        credentialId: CredentialId,
    ): MintedCredential

    suspend fun display(systemOfRecordData: DataItem): CredentialDisplay? = null

    class RegisteredFactories(
        val byOfferId: Map<String, CredentialFactory>,
        val supportedScopes: Set<String>
    )

    companion object {
        private val initializationLock = Mutex()
        private var registeredFactories: RegisteredFactories? = null

        suspend fun getRegisteredFactories(): RegisteredFactories  = initializationLock.withLock {
            if (registeredFactories == null) {
                val factories = mutableListOf(
                    CredentialFactoryMdl(),
                    CredentialFactoryMdocPid(),
                    CredentialFactorySdjwtPid(),
                    CredentialFactoryUtopiaNaturalization(),
                    CredentialFactoryUtopiaMovieTicket(),
                    CredentialFactoryAgeVerification(),
                    CredentialFactoryUtopiaLoyalty(),
                )
                factories.forEach { it.initialize() }
                registeredFactories = RegisteredFactories(
                    byOfferId = factories.associateBy { it.offerId },
                    supportedScopes = factories.map { it.scope }.toSet()
                )
            }
            registeredFactories!!
        }

        val DEFAULT_PROOF_SIGNING_ALGORITHMS = listOf("ES256")
    }
}

internal val credentialFormatMdl = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE)
internal val credentialFormatPid = CredentialFormat.Mdoc(EUPersonalID.EUPID_DOCTYPE)
internal val credentialFormatAv = CredentialFormat.Mdoc(AgeVerification.AV_DOCTYPE)
internal val credentialFormatLoyalty = CredentialFormat.Mdoc(Loyalty.LOYALTY_DOCTYPE)
