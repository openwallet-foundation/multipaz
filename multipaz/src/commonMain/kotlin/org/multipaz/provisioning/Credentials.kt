package org.multipaz.provisioning

/**
 * Provisioned credentials and optional credential metadata.
 *
 * @property certifications credential certification data.
 * @property display updated credential name and card art if any.
 */
data class Credentials(
    val certifications: List<CredentialCertification>,
    val display: Display?
)