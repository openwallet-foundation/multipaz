package org.multipaz.securearea.software

import org.multipaz.prompt.Reason
import org.multipaz.securearea.KeyLockedException

internal actual suspend fun softwareSecureAreaPerformUserAuth(
    alias: String,
    userAuthenticationTypes: Set<SoftwareUserAuthType>,
    unlockReason: Reason
): Boolean {
    throw KeyLockedException("User authentication is not supported on WasmJS for SoftwareSecureArea")
}
