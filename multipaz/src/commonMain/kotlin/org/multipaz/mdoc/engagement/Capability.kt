package org.multipaz.mdoc.engagement

/**
 * Capabilities conveyed during the engagement phase.
 *
 * @param identifier the numerical identifier of the capability, as defined by ISO 18013-5.
 */
enum class Capability(
    val identifier: Int,
) {
    HANDOVER_SESSION_ESTABLISHMENT_SUPPORT(2),
    READER_AUTH_ALL_SUPPORT(3),
    EXTENDED_REQUEST_SUPPORT(4)
}
