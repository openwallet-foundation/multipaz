package org.multipaz.provisioning.openid4vci

import io.ktor.client.HttpClient
import org.multipaz.provisioning.ProvisioningClient
import org.multipaz.provisioning.ProvisioningMetadata

/**
 * Utilities for provisioning through OpenID4VCI protocol.
 */
object OpenID4VCI {
    /**
     * Creates [ProvisioningClient] for Openid4Vci from a credential offer.
     */
    suspend fun createClientFromOffer(
        offerUri: String,
        clientPreferences: OpenID4VCIClientPreferences
    ): ProvisioningClient = OpenID4VCIProvisioningClient.createFromOffer(offerUri, clientPreferences)

    /**
     * Creates [ProvisioningClient] for Openid4Vci from the issuer configuration.
     *
     * @param issuerUrl issuer identifier (server URL)
     * @param credentialId credential configuration id
     * @param clientPreferences OpenID4VCI client parameters
     * @return new [ProvisioningClient]
     */
    suspend fun createClientCredentialId(
        issuerUrl: String,
        credentialId: String,
        clientPreferences: OpenID4VCIClientPreferences
    ): ProvisioningClient = OpenID4VCIProvisioningClient.createFromCredentialId(
        issuerUrl, credentialId, clientPreferences)

    /**
     * Fetches metadata from the server for the given issuer.
     *
     * @param issuerUrl issuer identifier (server URL)
     * @param httpClient HTTP client
     * @param clientPreferences OpenID4VCI client parameters
     * @returns metadata that includes all supported credential configurations
     */
    suspend fun getMetadata(
        issuerUrl: String,
        httpClient: HttpClient,
        clientPreferences: OpenID4VCIClientPreferences
    ): ProvisioningMetadata =
        OpenID4VCIProvisioningClient.getMetadata(issuerUrl, httpClient, clientPreferences)
}