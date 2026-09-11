package org.multipaz.crypto

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.toDataItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SignatureTests {
    @Test
    fun ecSignatureCborRoundtrip() {
        val r = byteArrayOf(1, 2, 3, 4)
        val s = byteArrayOf(5, 6, 7, 8)
        val sig = EcSignature(r, s)
        val dataItem = sig.toDataItem()
        val cborBytes = Cbor.encode(dataItem)

        val decodedSig = Signature.fromDataItem(Cbor.decode(cborBytes))
        assertIs<EcSignature>(decodedSig)
        assertEquals(sig, decodedSig)

        val directDecoded = EcSignature.fromDataItem(Cbor.decode(cborBytes))
        assertEquals(sig, directDecoded)
    }

    @Test
    fun rsaSignatureCborRoundtrip() {
        val sigData = byteArrayOf(10, 20, 30, 40, 50)
        val sig = RsaSignature(sigData)
        val dataItem = sig.toDataItem()
        val cborBytes = Cbor.encode(dataItem)

        val decodedSig = Signature.fromDataItem(Cbor.decode(cborBytes))
        assertIs<RsaSignature>(decodedSig)
        assertEquals(sig, decodedSig)

        val directDecoded = RsaSignature.fromDataItem(Cbor.decode(cborBytes))
        assertEquals(sig, directDecoded)
    }

    @Test
    fun mlDsaSignatureCborRoundtrip() {
        val sigData = byteArrayOf(11, 22, 33, 44, 55, 66)
        val sig = MlDsaSignature(sigData)
        val dataItem = sig.toDataItem()
        val cborBytes = Cbor.encode(dataItem)

        val decodedSig = Signature.fromDataItem(Cbor.decode(cborBytes))
        assertIs<MlDsaSignature>(decodedSig)
        assertEquals(sig, decodedSig)

        val directDecoded = MlDsaSignature.fromDataItem(Cbor.decode(cborBytes))
        assertEquals(sig, directDecoded)
    }

    @Test
    fun invalidSignatureFromDataItem() {
        assertFailsWith<IllegalArgumentException> {
            Signature.fromDataItem(42L.toDataItem())
        }
        assertFailsWith<IllegalArgumentException> {
            val invalidMap = org.multipaz.cbor.buildCborMap {
                put("unknown", 1L)
            }
            Signature.fromDataItem(invalidMap)
        }
    }
}
