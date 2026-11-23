package org.multipaz.securearea

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.HybridPublicKey
import org.multipaz.crypto.HybridSigning
import org.multipaz.crypto.MlDsaPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureEnclaveAreaKeyMetadataTest {

    @Test
    fun testHybridMetadataCborRoundTrip() = runTest {
        val mlDsaPub = MlDsaPublicKey(
            Algorithm.ML_DSA_65,
            ByteString(ByteArray(1952) { 0x2A })
        )
        val ecPub = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        val hybridPub = HybridSigning.createPublicKey(
            algorithm = Algorithm.ML_DSA_65_ES256,
            pqPublicKey = mlDsaPub,
            traditionalPublicKey = ecPub
        )

        val metadata = SecureEnclaveAreaKeyMetadata(
            algorithm = Algorithm.ML_DSA_65_ES256,
            userAuthenticationRequired = true,
            userAuthenticationTypes = 7L,
            publicKey = hybridPub,
            keyBlob = ByteString(byteArrayOf(1, 2, 3, 4)),
            biometryDomainState = "biometry-domain-state".encodeToByteString(),
            traditionalKeyBlob = ByteString(byteArrayOf(5, 6, 7, 8))
        )

        val encoded = metadata.toCbor()
        val map = Cbor.decode(encoded) as CborMap
        assertTrue(map.hasKey("biometryDomainState"))
        assertTrue(map.hasKey("traditionalKeyBlob"))

        val decoded = SecureEnclaveAreaKeyMetadata.fromCbor(encoded)
        assertEquals(metadata, decoded)
        assertEquals(Algorithm.ML_DSA_65_ES256, decoded.algorithm)
        assertEquals(hybridPub, decoded.publicKey as HybridPublicKey)
        assertEquals(metadata.keyBlob, decoded.keyBlob)
        assertEquals(metadata.traditionalKeyBlob, decoded.traditionalKeyBlob)
        assertEquals(metadata.biometryDomainState, decoded.biometryDomainState)
    }

    @Test
    fun testSingleKeyMetadataOmitsTraditionalKeyBlobAndLoadsLegacyCbor() = runTest {
        val ecPub = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        val metadata = SecureEnclaveAreaKeyMetadata(
            algorithm = Algorithm.ESP256,
            userAuthenticationRequired = false,
            userAuthenticationTypes = 0L,
            publicKey = ecPub,
            keyBlob = ByteString(byteArrayOf(9, 10, 11))
        )

        val encoded = metadata.toCbor()
        val map = Cbor.decode(encoded) as CborMap
        assertFalse(map.hasKey("traditionalKeyBlob"))
        assertFalse(map.hasKey("biometryDomainState"))

        val decoded = SecureEnclaveAreaKeyMetadata.fromCbor(encoded)
        assertEquals(metadata, decoded)
        assertNull(decoded.traditionalKeyBlob)
        assertNull(decoded.biometryDomainState)

        // Verify legacy CBOR written before traditionalKeyBlob existed decodes cleanly
        val legacyCbor = Cbor.encode(
            buildCborMap {
                put("algorithm", Tstr(Algorithm.ESP256.name))
                put("userAuthenticationRequired", false.toDataItem())
                put("userAuthenticationTypes", 0L.toDataItem())
                put("publicKey", ecPub.toDataItem())
                put("keyBlob", byteArrayOf(12, 13).toDataItem())
            }
        )
        val decodedLegacy = SecureEnclaveAreaKeyMetadata.fromCbor(legacyCbor)
        assertEquals(Algorithm.ESP256, decodedLegacy.algorithm)
        assertNull(decodedLegacy.traditionalKeyBlob)
        assertNull(decodedLegacy.biometryDomainState)
    }
}
