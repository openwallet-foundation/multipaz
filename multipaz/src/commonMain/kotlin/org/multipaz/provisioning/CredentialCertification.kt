package org.multipaz.provisioning

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.credential.Credential

/**
 * Data necessary to certify a credential during provisioning.
 *
 * See [Credential.certify].
 *
 * @property credentialId identifier of the credential that will be certified
 * @property issuerData issuer-provided data to certify the credential
 */
@CborSerializable
data class CredentialCertification(
    val credentialId: String,
    val issuerData: ByteString
) {
    companion object
}