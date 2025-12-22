package org.multipaz.mdoc.mso

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.util.fromHex
import org.multipaz.util.fromHexByteString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

class MobileSecurityObjectTest {

    @Test
    fun testVector2021() {
        val deviceResponse = Cbor.decode(TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE .fromHex())
        val documentDataItem = deviceResponse["documents"][0]
        val issuerSigned = documentDataItem["issuerSigned"]
        val issuerAuthDataItem = issuerSigned["issuerAuth"]
        val mobileSecurityObjectBytes = Cbor.decode(issuerAuthDataItem.asCoseSign1.payload!!)
        val mso = MobileSecurityObject.fromDataItem(mobileSecurityObjectBytes.asTaggedEncodedCbor)
        assertEquals("1.0", mso.version)
        assertEquals(DrivingLicense.MDL_DOCTYPE, mso.docType)
        assertEquals("2020-10-01T13:30:02Z",mso.signedAt.toString())
        assertEquals("2020-10-01T13:30:02Z",mso.validFrom.toString())
        assertEquals("2021-10-01T13:30:02Z",mso.validUntil.toString())
        assertEquals(null,mso.expectedUpdate)
        assertEquals(Algorithm.SHA256,mso.digestAlgorithm)
        assertEquals(mapOf(
            "org.iso.18013.5.1" to mapOf(
                0L to "75167333b47b6c2bfb86eccc1f438cf57af055371ac55e1e359e20f254adcebf".fromHexByteString(),
                1L to "67e539d6139ebd131aef441b445645dd831b2b375b390ca5ef6279b205ed4571".fromHexByteString(),
                2L to "3394372ddb78053f36d5d869780e61eda313d44a392092ad8e0527a2fbfe55ae".fromHexByteString(),
                3L to "2e35ad3c4e514bb67b1a9db51ce74e4cb9b7146e41ac52dac9ce86b8613db555".fromHexByteString(),
                4L to "ea5c3304bb7c4a8dcb51c4c13b65264f845541341342093cca786e058fac2d59".fromHexByteString(),
                5L to "fae487f68b7a0e87a749774e56e9e1dc3a8ec7b77e490d21f0e1d3475661aa1d".fromHexByteString(),
                6L to "7d83e507ae77db815de4d803b88555d0511d894c897439f5774056416a1c7533".fromHexByteString(),
                7L to "f0549a145f1cf75cbeeffa881d4857dd438d627cf32174b1731c4c38e12ca936".fromHexByteString(),
                8L to "b68c8afcb2aaf7c581411d2877def155be2eb121a42bc9ba5b7312377e068f66".fromHexByteString(),
                9L to "0b3587d1dd0c2a07a35bfb120d99a0abfb5df56865bb7fa15cc8b56a66df6e0c".fromHexByteString(),
                10L to "c98a170cf36e11abb724e98a75a5343dfa2b6ed3df2ecfbb8ef2ee55dd41c881".fromHexByteString(),
                11L to "b57dd036782f7b14c6a30faaaae6ccd5054ce88bdfa51a016ba75eda1edea948".fromHexByteString(),
                12L to "651f8736b18480fe252a03224ea087b5d10ca5485146c67c74ac4ec3112d4c3a".fromHexByteString(),
            ),
            "org.iso.18013.5.1.US" to mapOf(
                0L to "d80b83d25173c484c5640610ff1a31c949c1d934bf4cf7f18d5223b15dd4f21c".fromHexByteString(),
                1L to "4d80e1e2e4fb246d97895427ce7000bb59bb24c8cd003ecf94bf35bbd2917e34".fromHexByteString(),
                2L to "8b331f3b685bca372e85351a25c9484ab7afcdf0d2233105511f778d98c2f544".fromHexByteString(),
                3L to "c343af1bd1690715439161aba73702c474abf992b20c9fb55c36a336ebe01a87".fromHexByteString(),
            )
        ), mso.valueDigests)
        assertEquals(EcPublicKeyDoubleCoordinate(
            curve = EcCurve.P256,
            x = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
            y = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex()
        ),mso.deviceKey)
        assertEquals(listOf(),mso.deviceKeyAuthorizedNamespaces)
        assertEquals(mapOf(),mso.deviceKeyAuthorizedDataElements)
        assertEquals(mapOf(),mso.deviceKeyInfo)
    }

    @Test
    fun testComplex() = runTest {
        val randomProvider = Random(42)
        val issuerNamespaces = buildIssuerNamespaces(randomProvider = randomProvider) {
            addNamespace(PhotoID.ISO_23220_2_NAMESPACE) {
                addDataElement("family_name", "Doe".toDataItem())
                addDataElement("given_name", "John".toDataItem())
            }
            addNamespace(PhotoID.PHOTO_ID_DOCTYPE) {
                addDataElement("person_id", "1234567890".toDataItem())
            }
        }
        val d = LocalDate.parse("2025-12-01").atStartOfDayIn(TimeZone.UTC)
        val deviceKey = EcPublicKeyDoubleCoordinate(
            curve = EcCurve.P256,
            x = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
            y = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex()
        )
        val mso = MobileSecurityObject(
            version = "1.0",
            docType = PhotoID.PHOTO_ID_DOCTYPE,
            signedAt = d,
            validFrom = d,
            validUntil = d + 30.days,
            expectedUpdate = d + 20.days,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = deviceKey,
            deviceKeyAuthorizedNamespaces = listOf(PhotoID.PHOTO_ID_DOCTYPE),
            deviceKeyAuthorizedDataElements = mapOf(
                PhotoID.ISO_23220_2_NAMESPACE to listOf(
                    "age_over_18",
                    "age_over_19"
                ),
                PhotoID.DTC_NAMESPACE to listOf(
                    "dg1",
                    "dg2",
                    "dg3"
                )
            ),
            deviceKeyInfo = mapOf(
                -42L to "TestString".toDataItem(),
                -43L to 42601L.toDataItem()
            ),
        )
        assertEquals(
            """
                {
                  "version": "1.0",
                  "digestAlgorithm": "SHA-256",
                  "docType": "org.iso.23220.photoid.1",
                  "valueDigests": {
                    "org.iso.23220.1": {
                      1: h'47ae45b8e9156a0c59cfc1587eeb086127c728cb72c71d3e072b08576f7da174',
                      0: h'af9f679ba1a31c45aa1bd59a5b878c215825698e55dbb64cc6bfb4f02ff207f1'
                    },
                    "org.iso.23220.photoid.1": {
                      2: h'33cfdc1fcd8320d44848decb955d90a98afb0ee0c72c179b311bd83672d77f34'
                    }
                  },
                  "deviceKeyInfo": {
                    "deviceKey": {
                      1: 2,
                      -1: 1,
                      -2: h'96313d6c63e24e3372742bfdb1a33ba2c897dcd68ab8c753e4fbd48dca6b7f9a',
                      -3: h'1fb3269edd418857de1b39a4e4a44b92fa484caa722c228288f01d0c03a2c3d6'
                    },
                    "keyAuthorizations": {
                      "nameSpaces": ["org.iso.23220.photoid.1"],
                      "dataElements": {
                        "org.iso.23220.1": ["age_over_18", "age_over_19"],
                        "org.iso.23220.dtc.1": ["dg1", "dg2", "dg3"]
                      }
                    },
                    "keyInfo": {
                      -42: "TestString",
                      -43: 42601
                    }
                  },
                  "validityInfo": {
                    "signed": 0("2025-12-01T00:00:00Z"),
                    "validFrom": 0("2025-12-01T00:00:00Z"),
                    "validUntil": 0("2025-12-31T00:00:00Z"),
                    "expectedUpdate": 0("2025-12-21T00:00:00Z")
                  }
                }
            """.trimIndent(),
            Cbor.toDiagnostics(mso.toDataItem(), setOf(DiagnosticOption.PRETTY_PRINT))
        )
        // Check that roundtrip works
        assertEquals(
            MobileSecurityObject.fromDataItem(Cbor.decode(Cbor.encode(mso.toDataItem()))),
            mso
        )
    }
}