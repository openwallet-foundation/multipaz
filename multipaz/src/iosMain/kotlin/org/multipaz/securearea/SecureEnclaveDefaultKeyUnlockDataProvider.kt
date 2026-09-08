package org.multipaz.securearea

import org.multipaz.crypto.Algorithm
import org.multipaz.prompt.Reason
import platform.LocalAuthentication.LAContext

object SecureEnclaveDefaultKeyUnlockDataProvider: KeyUnlockDataProvider {
    override suspend fun getKeyUnlockData(
        secureArea: SecureArea,
        alias: String,
        algorithm: Algorithm,
        unlockReason: Reason
    ): KeyUnlockData {
        check(secureArea is SecureEnclaveSecureArea)
        return SecureEnclaveKeyUnlockData(
            secureArea = secureArea,
            alias = alias,
            authenticationContext = LAContext()
        )
    }
}