package org.multipaz.securearea.software

import org.multipaz.prompt.Reason

internal expect suspend fun softwareSecureAreaPerformUserAuth(
    alias: String,
    userAuthenticationTypes: Set<SoftwareUserAuthType>,
    unlockReason: Reason
): Boolean
