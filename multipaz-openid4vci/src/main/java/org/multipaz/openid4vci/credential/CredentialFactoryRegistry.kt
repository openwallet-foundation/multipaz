package org.multipaz.openid4vci.credential

/**
 * Registry of all [CredentialFactory] implementations supported by this server.
 *
 * @param factories list of [CredentialFactory] implementations
 */
class CredentialFactoryRegistry(
    factories: List<CredentialFactory>
) {
    /** Maps configuration id to the corresponding [CredentialFactory] */
    val byId = factories.associateBy { it.configurationId }

    /** Set of all [CredentialFactory.scope] values */
    val supportedScopes = factories.map { it.scope }.toSet()

    /** Call [CredentialFactory.initialize] on all factories. */
    suspend fun initialize() {
        for (factory in byId.values) {
            factory.initialize()
        }
    }
}