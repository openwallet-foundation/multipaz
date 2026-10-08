package org.multipaz.digitalcredentials

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DigitalCredentialsExtTest {
    @Test
    fun testGetAppOrigin() = runTest {
        // Test with bytes whose SHA-256 hash contains bytes that produce '-' and '_' in Base64Url
        // (which would produce '+' and '/' in standard Base64).
        // SHA-256("test_cert_0") = 55 fb 70 69 bd ee d2 48 c2 fc ef c5 e8 7a 40 93 47 36 ad e6 7c fe 29 43 0b a0 ef d3 5a 55 ac d6
        // Base64Url (unpadded): Vftwab3u0kjC_O_F6HpAk0c2reZ8_ilDC6Dv01pVrNY
        // Standard Base64:      Vftwab3u0kjC/O/F6HpAk0c2reZ8/ilDC6Dv01pVrNY
        val testData = "test_cert_0".encodeToByteArray()
        val origin = getAppOrigin(testData)
        assertEquals("android:apk-key-hash:Vftwab3u0kjC_O_F6HpAk0c2reZ8_ilDC6Dv01pVrNY", origin)
    }
}
