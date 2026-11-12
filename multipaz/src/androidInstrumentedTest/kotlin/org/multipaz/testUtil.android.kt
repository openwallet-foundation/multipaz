package org.multipaz

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.multipaz.crypto.Crypto
import java.security.Security

actual fun testUtilSetupCryptoProvider() {
    // On Android (in tests), load BouncyCastleProvider so we can exercise
    // all the tests involving Brainpool curves.
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME) // remove first to ensure it's not already there
    Security.insertProviderAt(BouncyCastleProvider(), 1)

    println("In testUtilCommonSetup for androidInstrumentedTest")
    println("Crypto.provider: ${Crypto.provider}")
    println("Crypto.supportedCurves: ${Crypto.supportedCurves}")
}
