package org.multipaz.mdoc

import kotlin.concurrent.Volatile

/**
 * Process-wide default compatibility options for mdoc parsing/certification.
 *
 * Applications can update this when a tenant/profile-specific compatibility bundle is resolved,
 * allowing credential parsing to start in the correct mode instead of probing strict mode first.
 */
object MdocCompatibilityDefaults {
    @Volatile
    private var defaults: MdocCompatibilityOptions = MdocCompatibilityOptions()

    fun current(): MdocCompatibilityOptions = defaults

    fun update(options: MdocCompatibilityOptions) {
        defaults = options
    }
}
