package org.multipaz

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.multipaz.crypto.Crypto
import java.security.Security

actual fun testUtilSetupCryptoProvider() {
    println("In testUtilCommonSetup for Android")

    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.insertProviderAt(BouncyCastleProvider(), 1)

    println("Crypto.provider: ${Crypto.provider}")
    println("Crypto.supportedCurves: ${Crypto.supportedCurves}")
}
