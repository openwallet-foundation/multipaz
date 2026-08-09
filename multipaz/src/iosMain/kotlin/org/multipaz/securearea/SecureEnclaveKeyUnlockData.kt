package org.multipaz.securearea

import platform.LocalAuthentication.LAContext

/**
 * Class used to provide policy when unlocking a key.
 *
 * @param authenticationContext platform native [LAContext](https://developer.apple.com/documentation/LocalAuthentication/LAContext) object.
 */
class SecureEnclaveKeyUnlockData(
    override val secureArea: SecureEnclaveSecureArea,
    override val alias: String,
    val authenticationContext: LAContext = LAContext()
): KeyUnlockData
