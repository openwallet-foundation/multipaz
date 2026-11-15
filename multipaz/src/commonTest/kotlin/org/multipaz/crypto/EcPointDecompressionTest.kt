package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Simple
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseKey
import org.multipaz.cose.toCoseLabel
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.fromHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EcPointDecompressionTest {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    @Test
    fun brainpoolP320r1DefaultSigningAlgorithmFullySpecified() {
        assertEquals(
            Algorithm.ESB320,
            EcCurve.BRAINPOOLP320R1.defaultSigningAlgorithmFullySpecified
        )
    }

    @Test
    fun compressedEc2CoseKeyRoundTripAllWeierstrassAndBrainpoolCurves() = runTest {
        // Generator / base points (Gx, Gy) from FIPS 186-5 and RFC 5639 for deterministic coverage
        // across all 7 curves even on platforms whose runtime Crypto provider omits Brainpool.
        data class Vector(val curve: EcCurve, val gxHex: String, val gyHex: String)
        val vectors = listOf(
            Vector(
                EcCurve.P256,
                "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
            ),
            Vector(
                EcCurve.P384,
                "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7",
                "3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f"
            ),
            Vector(
                EcCurve.P521,
                "00c6858e06b70404e9cd9e3ecb662395b4429c648139053fb521f828af606b4d3dbaa14b5e77efe75928fe1dc127a2ffa8de3348b3c1856a429bf97e7e31c2e5bd66",
                "011839296a789a3bc0045c8a5fb42c7d1bd998f54449579b446817afbd17273e662c97ee72995ef42640c550b9013fad0761353c7086a272c24088be94769fd16650"
            ),
            Vector(
                EcCurve.BRAINPOOLP256R1,
                "8bd2aeb9cb7e57cb2c4b482ffc81b7afb9de27e1e3bd23c23a4453bd9ace3262",
                "547ef835c3dac4fd97f8461a14611dc9c27745132ded8e545c1d54c72f046997"
            ),
            Vector(
                EcCurve.BRAINPOOLP320R1,
                "43bd7e9afb53d8b85289bcc48ee5bfe6f20137d10a087eb6e7871e2a10a599c710af8d0d39e20611",
                "14fdd05545ec1cc8ab4093247f77275e0743ffed117182eaa9c77877aaac6ac7d35245d1692e8ee1"
            ),
            Vector(
                EcCurve.BRAINPOOLP384R1,
                "1d1c64f068cf45ffa2a63a81b7c13f6b8847a3e77ef14fe3db7fcafe0cbd10e8e826e03436d646aaef87b2e247d4af1e",
                "8abe1d7520f9c2a45cb1eb8e95cfd55262b70b29feec5864e19c054ff99129280e4646217791811142820341263c5315"
            ),
            Vector(
                EcCurve.BRAINPOOLP512R1,
                "81aee4bdd82ed9645a21322e9c4c6a9385ed9f70b5d916c1b43b62eef4d0098eff3b1f78e2d0d48d50d1687b93b97d5f7c6d5047406a5e688b352209bcb9f822",
                "7dde385d566332ecc0eabfa9cf7822fdf209f70024a57b1aa000c55b881f8111b2dcde494a5f485e5bca4bd88a2763aed1ca2b2fa8f0540678cd1e0f3ad80892"
            ),
        )

        for (v in vectors) {
            val gx = v.gxHex.fromHex()
            val gy = v.gyHex.fromHex()
            val gyOdd = (gy.last().toInt() and 1) == 1

            val compressedSameParity = CoseKey(
                mapOf(
                    Cose.COSE_KEY_KTY.toCoseLabel to Cose.COSE_KEY_TYPE_EC2.toDataItem(),
                    Cose.COSE_KEY_PARAM_CRV.toCoseLabel to v.curve.coseCurveIdentifier.toDataItem(),
                    Cose.COSE_KEY_PARAM_X.toCoseLabel to Bstr(gx),
                    Cose.COSE_KEY_PARAM_Y.toCoseLabel to if (gyOdd) Simple.TRUE else Simple.FALSE,
                )
            )
            val decompressed = EcPublicKey.fromCoseKey(compressedSameParity) as EcPublicKeyDoubleCoordinate
            assertEquals(v.curve, decompressed.curve)
            assertContentEquals(gx, decompressed.x)
            assertContentEquals(gy, decompressed.y)

            // Flipping the parity bit yields -G = (Gx, p - Gy), which is also on the curve
            val compressedNegated = CoseKey(
                mapOf(
                    Cose.COSE_KEY_KTY.toCoseLabel to Cose.COSE_KEY_TYPE_EC2.toDataItem(),
                    Cose.COSE_KEY_PARAM_CRV.toCoseLabel to v.curve.coseCurveIdentifier.toDataItem(),
                    Cose.COSE_KEY_PARAM_X.toCoseLabel to Bstr(gx),
                    Cose.COSE_KEY_PARAM_Y.toCoseLabel to if (gyOdd) Simple.FALSE else Simple.TRUE,
                )
            )
            val negDecompressed = EcPublicKey.fromCoseKey(compressedNegated) as EcPublicKeyDoubleCoordinate
            assertEquals(!gyOdd, (negDecompressed.y.last().toInt() and 1) == 1)
            // Uncompressed round-trip of -G succeeds on-curve validation
            assertEquals(negDecompressed, EcPublicKey.fromCoseKey(negDecompressed.toCoseKey()))
        }
    }

    @Test
    fun rejectsInvalidCompressedAndUncompressedPoints() {
        // x >= p on P-256 (all 0xff)
        val xGeP = ByteArray(32) { 0xff.toByte() }
        val coseXGeP = CoseKey(
            mapOf(
                Cose.COSE_KEY_KTY.toCoseLabel to Cose.COSE_KEY_TYPE_EC2.toDataItem(),
                Cose.COSE_KEY_PARAM_CRV.toCoseLabel to EcCurve.P256.coseCurveIdentifier.toDataItem(),
                Cose.COSE_KEY_PARAM_X.toCoseLabel to Bstr(xGeP),
                Cose.COSE_KEY_PARAM_Y.toCoseLabel to Simple.FALSE,
            )
        )
        assertFailsWith<IllegalArgumentException> {
            EcPublicKey.fromCoseKey(coseXGeP)
        }

        // x = 1 on P-256 has x^3 - 3x + b non-residue mod p (not on curve)
        val offCurveX = ByteArray(32).apply { this[31] = 1 }
        val coseOffCurveCompressed = CoseKey(
            mapOf(
                Cose.COSE_KEY_KTY.toCoseLabel to Cose.COSE_KEY_TYPE_EC2.toDataItem(),
                Cose.COSE_KEY_PARAM_CRV.toCoseLabel to EcCurve.P256.coseCurveIdentifier.toDataItem(),
                Cose.COSE_KEY_PARAM_X.toCoseLabel to Bstr(offCurveX),
                Cose.COSE_KEY_PARAM_Y.toCoseLabel to Simple.TRUE,
            )
        )
        assertFailsWith<IllegalArgumentException> {
            EcPublicKey.fromCoseKey(coseOffCurveCompressed)
        }

        // Uncompressed (1, 1) is not on P-256
        val one = ByteArray(32).apply { this[31] = 1 }
        val coseOffCurveUncompressed = CoseKey(
            mapOf(
                Cose.COSE_KEY_KTY.toCoseLabel to Cose.COSE_KEY_TYPE_EC2.toDataItem(),
                Cose.COSE_KEY_PARAM_CRV.toCoseLabel to EcCurve.P256.coseCurveIdentifier.toDataItem(),
                Cose.COSE_KEY_PARAM_X.toCoseLabel to Bstr(one),
                Cose.COSE_KEY_PARAM_Y.toCoseLabel to Bstr(one),
            )
        )
        assertFailsWith<IllegalArgumentException> {
            EcPublicKey.fromCoseKey(coseOffCurveUncompressed)
        }
    }
}
