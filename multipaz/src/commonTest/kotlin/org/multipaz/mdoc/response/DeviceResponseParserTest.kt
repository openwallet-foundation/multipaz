/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multipaz.mdoc.response

import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.mdoc.TestVectors
import org.multipaz.util.Constants
import org.multipaz.util.fromHex
import org.multipaz.util.fromHexByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceResponseParserTest {
    @Test
    fun testDeviceResponseParserWithVectors() = runTest {

        // NOTE: This tests tests the MAC verification path of DeviceResponseParser, the
        // ECDSA verification path is tested in DeviceResponseGeneratorTest by virtue of
        // SUtil.getIdentityCredentialStore() defaulting to the Jetpack.
        val encodedDeviceResponse = TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex()

        // Strip the #6.24 tag since our APIs expects just the bytes of SessionTranscript.
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val encodedSessionTranscript = Cbor.encode(
            Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor
        )
        val eReaderKey: EcPrivateKey = EcPrivateKeyDoubleCoordinate(
            EcCurve.P256,
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex()
        )
        val dr = DeviceResponseParser(
            encodedDeviceResponse,
            encodedSessionTranscript
        )
            .setEphemeralReaderKey(AsymmetricKey.anonymous(
                privateKey = eReaderKey,
                algorithm = eReaderKey.curve.defaultKeyAgreementAlgorithm
            ))
            .parse()
        assertEquals(Constants.DEVICE_RESPONSE_STATUS_OK, dr.status)
        assertEquals("1.0", dr.version)
        val documents = dr.documents
        assertEquals(1, documents.size.toLong())
        val d = documents[0]

        // Check ValidityInfo is correctly parsed, these values are all from
        // ISO/IEC 18013-5 Annex D.4.1.2 mdoc response.
        // 2020-10-01T13:30:02Z == 1601559002000
        assertEquals(1601559002000L, d.validityInfoSigned.toEpochMilliseconds())
        assertEquals(1601559002000L, d.validityInfoValidFrom.toEpochMilliseconds())
        // 2021-10-01T13:30:02Z == 1601559002000
        assertEquals(1633095002000L, d.validityInfoValidUntil.toEpochMilliseconds())
        assertNull(d.validityInfoExpectedUpdate)

        // Check DeviceKey is correctly parsed
        val deviceKeyFromVector = EcPublicKeyDoubleCoordinate(
            EcCurve.P256,
            TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex()
        )
        assertEquals(deviceKeyFromVector, d.deviceKey)

        // Test example is using a MAC.
        assertFalse(d.deviceSignedAuthenticatedViaSignature)

        // Check the returned issuer data, these values are all from
        // ISO/IEC 18013-5 Annex D.4.1.2 mdoc response.
        //
        assertTrue(d.issuerSignedAuthenticated)
        assertTrue(d.deviceSignedAuthenticated)
        assertEquals(MDL_DOCTYPE, d.docType)
        assertEquals(0, d.deviceNamespaces.size.toLong())
        assertEquals(1, d.issuerNamespaces.size.toLong())
        assertEquals(MDL_NAMESPACE, d.issuerNamespaces.iterator().next())
        assertEquals(6, d.getIssuerEntryNames(MDL_NAMESPACE).size.toLong())
        assertEquals(
            "Doe",
            d.getIssuerEntryString(MDL_NAMESPACE, "family_name")
        )
        assertEquals(
            "123456789",
            d.getIssuerEntryString(MDL_NAMESPACE, "document_number")
        )
        assertEquals(
            "1004(\"2019-10-20\")",
            Cbor.toDiagnostics(
                d.getIssuerEntryData(MDL_NAMESPACE, "issue_date"),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(
            "1004(\"2024-10-20\")",
            Cbor.toDiagnostics(
                d.getIssuerEntryData(MDL_NAMESPACE, "expiry_date"),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertEquals(
            """[
  {
    "vehicle_category_code": "A",
    "issue_date": 1004("2018-08-09"),
    "expiry_date": 1004("2024-10-20")
  },
  {
    "vehicle_category_code": "B",
    "issue_date": 1004("2017-02-23"),
    "expiry_date": 1004("2024-10-20")
  }
]""",
            Cbor.toDiagnostics(
                d.getIssuerEntryData(MDL_NAMESPACE, "driving_privileges"),
                setOf(DiagnosticOption.PRETTY_PRINT)
            )
        )
        assertContentEquals(
            TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE_PORTRAIT_DATA.fromHex(),
            d.getIssuerEntryByteString(MDL_NAMESPACE, "portrait")
        )

        // Check the issuer-signed items all validated (digest matches what's in MSO)
        assertTrue(d.getIssuerEntryDigestMatch(MDL_NAMESPACE, "family_name"))
        assertTrue(d.getIssuerEntryDigestMatch(MDL_NAMESPACE, "document_number"))
        assertTrue(d.getIssuerEntryDigestMatch(MDL_NAMESPACE, "issue_date"))
        assertTrue(d.getIssuerEntryDigestMatch(MDL_NAMESPACE, "expiry_date"))
        assertTrue(d.getIssuerEntryDigestMatch(MDL_NAMESPACE, "driving_privileges"))
        assertTrue(d.getIssuerEntryDigestMatch(MDL_NAMESPACE, "portrait"))
        assertEquals(0, d.numIssuerEntryDigestMatchFailures.toLong())

        // Check the returned issuer certificate matches.
        //
        val (certificates) = d.issuerCertificateChain
        assertEquals(1, certificates.size.toLong())
        assertEquals(
            TestVectors.ISO_18013_5_ANNEX_D_DS_CERT.fromHexByteString(),
            certificates[0].encoded
        )
    }

    @Test
    fun testDeviceResponseParserWithVectorsMalformedIssuerItem() = runTest {
        val encodedDeviceResponse =
            TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex()

        // We know that the value for family_name in IssuerSignedItem is at offset 200.
        // Change this from "Doe" to "Foe" to force validation of that item to fail.
        //
        assertEquals(0x44, encodedDeviceResponse[200].toLong())
        encodedDeviceResponse[200] = 0x46.toByte()

        // Strip the #6.24 tag since our APIs expects just the bytes of SessionTranscript.
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val encodedSessionTranscript = Cbor.encode(
            Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor
        )
        val eReaderKey: EcPrivateKey = EcPrivateKeyDoubleCoordinate(
            EcCurve.P256,
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex()
        )
        val dr = DeviceResponseParser(
            encodedDeviceResponse,
            encodedSessionTranscript
        )
            .setEphemeralReaderKey(AsymmetricKey.anonymous(
                privateKey = eReaderKey,
                algorithm = eReaderKey.curve.defaultKeyAgreementAlgorithm
            ))
            .parse()
        assertEquals(Constants.DEVICE_RESPONSE_STATUS_OK, dr.status)
        assertEquals("1.0", dr.version)
        val documents = dr.documents
        assertEquals(1, documents.size.toLong())

        // Check the returned issuer data, these values are all from
        // ISO/IEC 18013-5 Annex D.4.1.2 mdoc response.
        //
        val d = documents[0]
        assertTrue(d.issuerSignedAuthenticated)
        assertTrue(d.deviceSignedAuthenticated)

        // We changed "Doe" to "Foe". Check that it doesn't validate.
        //
        assertEquals(
            "Foe",
            d.getIssuerEntryString(MDL_NAMESPACE, "family_name")
        )
        assertFalse(d.getIssuerEntryDigestMatch(MDL_NAMESPACE, "family_name"))
        assertEquals(1, d.numIssuerEntryDigestMatchFailures.toLong())
    }

    @Test
    fun testDeviceResponseParserWithVectorsMalformedDeviceSigned() = runTest {
        val encodedDeviceResponse = TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex()

        // We know that the 32 bytes for the MAC in DeviceMac CBOR starts at offset 3522 and
        // starts with E99521A8. Poison that to cause DeviceSigned to not authenticate.
        assertEquals(0xe9.toByte().toLong(), encodedDeviceResponse[3522].toLong())
        encodedDeviceResponse[3522] = 0xe8.toByte()

        // Strip the #6.24 tag since our APIs expects just the bytes of SessionTranscript.
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val encodedSessionTranscript = Cbor.encode(
            Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor
        )
        val eReaderKey: EcPrivateKey = EcPrivateKeyDoubleCoordinate(
            EcCurve.P256,
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex()
        )
        val dr = DeviceResponseParser(
            encodedDeviceResponse,
            encodedSessionTranscript
        )
            .setEphemeralReaderKey(AsymmetricKey.anonymous(
                privateKey = eReaderKey,
                algorithm = eReaderKey.curve.defaultKeyAgreementAlgorithm
            ))
            .parse()
        assertEquals(Constants.DEVICE_RESPONSE_STATUS_OK, dr.status)
        assertEquals("1.0", dr.version)
        val documents = dr.documents
        assertEquals(1, documents.size.toLong())

        // Check the returned issuer data, these values are all from
        // ISO/IEC 18013-5 Annex D.4.1.2 mdoc response.
        //
        val d = documents[0]
        assertTrue(d.issuerSignedAuthenticated)

        // Validation of DeviceSigned should fail.
        assertFalse(d.deviceSignedAuthenticated)
    }

    @Test
    fun testDeviceResponseParserWithVectorsMalformedIssuerSigned() = runTest {
        val encodedDeviceResponse = TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex()

        // We know that issuer signature starts at offset 3398 and starts with 59E64205DF1E.
        // Poison that to cause IssuerSigned to not authenticate.
        assertEquals(0x59.toByte().toLong(), encodedDeviceResponse[3398].toLong())
        encodedDeviceResponse[3398] = 0x5a.toByte()

        // Strip the #6.24 tag since our APIs expects just the bytes of SessionTranscript.
        val encodedSessionTranscriptBytes =
            TestVectors.ISO_18013_5_ANNEX_D_SESSION_TRANSCRIPT_BYTES.fromHex()
        val encodedSessionTranscript = Cbor.encode(
            Cbor.decode(encodedSessionTranscriptBytes).asTaggedEncodedCbor
        )
        val eReaderKey: EcPrivateKey = EcPrivateKeyDoubleCoordinate(
            EcCurve.P256,
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
            TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex()
        )
        val dr = DeviceResponseParser(
            encodedDeviceResponse,
            encodedSessionTranscript
        )
            .setEphemeralReaderKey(AsymmetricKey.anonymous(
                privateKey = eReaderKey,
                algorithm = eReaderKey.curve.defaultKeyAgreementAlgorithm
            ))
            .parse()
        assertEquals(Constants.DEVICE_RESPONSE_STATUS_OK, dr.status)
        assertEquals("1.0", dr.version)
        val documents = dr.documents
        assertEquals(1, documents.size.toLong())

        // Check the returned issuer data, these values are all from
        // ISO/IEC 18013-5 Annex D.4.1.2 mdoc response.
        //
        val d = documents[0]

        // Validation of IssuerSigned should fail.
        assertFalse(d.issuerSignedAuthenticated)

        // Check we're still able to read the data, though...
        assertEquals(MDL_DOCTYPE, d.docType)
        assertEquals(0, d.deviceNamespaces.size.toLong())
        assertEquals(1, d.issuerNamespaces.size.toLong())
        assertEquals(MDL_NAMESPACE, d.issuerNamespaces.iterator().next())
        assertEquals(6, d.getIssuerEntryNames(MDL_NAMESPACE).size.toLong())
        assertEquals(
            "Doe",
            d.getIssuerEntryString(MDL_NAMESPACE, "family_name")
        )

        // DeviceSigned is fine.
        assertTrue(d.deviceSignedAuthenticated)
    }

    @Test
    fun testDeviceResponseWithoutIssuerSigned() = runTest {
        val deviceResponseBytes = "a366737461747573006776657273696f6e63312e3069646f63756d656e747381a367646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6c6465766963655369676e6564a26a64657669636541757468a16f6465766963655369676e61747572658443a10126a0f65840d55cef6810dd72f0f365f99aa8922197967c46d6c0eb6f463674a8262f449296e61c343064ce5826139ff67f85cb2838501d2171faa470f6dffd3e4b5c59a45a6a6e616d65537061636573d81841a06c6973737565725369676e6564a26a697373756572417574688443a10126a1182180591486d818591481a766737461747573f667646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6776657273696f6e63312e306c76616c6964697479496e666fa3667369676e6564c074323032352d30372d32365431393a35363a35325a6976616c696446726f6dc074323032352d30372d32365431393a35363a35325a6a76616c6964556e74696cc074323035322d30322d31305430303a30303a30305a6c76616c756544696765737473a3716f72672e69736f2e31383031332e352e31b87b00582074d07f68c973a8802315989c3c999dd6acc15194c3f2175a59882265faf667c501582096caa31e664701774282d4229b099ffb8dafef4899f2124c56110a8a2c17e32d025820cb19a6d917c1b99967e3b3c98d520782db3350f617f61f96204208ebb9e4b6f70358201a4c99c90fd31f40faae14b120dd1e8a7145ccc01eb7fa9ab08098b09090412f045820c1a064f5ad3dbdc0ba1a2d8c90d5739c6c8a0c04ae4e29267895c857084cca27055820d9c5c4006bb6fe696c8f4ab61477d7c7bddc2306acf40d0179b05a52186e66b706582076fa64b5100776f5097917cd30438949eb2e11d83a618a8b20348558943e427e0758209da3744f04c05ac13795b7f8359d903b0d58b376017735ff3901fa10e0140007085820b680ca66ab6c5b929d22653a5ee7545ea5369a47b928f0075d4b51e54ae8c025095820c0431297f47dd0688344a5a3a6407a91c067ba3dcc8f0ad3a6bd82f810d487890a5820b218b293f472d85a746192c7743552b94abae9f5471bf9ef9bb8fc75c2b3ee100b5820ba0b7c88418ab99851251010f2b343a6f7e040112a23be798d4cb9d2787a0fdf0c5820306961d8873ad31b5cddab1268929455e1c3630d24a044583338881fedd3a5cc0d5820db79bf0712ead3cc0e5c60aaf0e73ff445fc9c7e4695fe7f9614657b50da57430e5820621bc2967115255aab295487bd04d4c2632c9101f1276ed8d2ad3d8b8193d90e0f5820a06c3268ace2ce9020bbf3435196e4f6aef4490f57845d0df14f514d0995e08610582047316276e38033be5cb8b93a13609b6f7c42488d6c7575424406455fa614eb551158200f5e79a8e7140914c46e01e24b3b7a983e8b999a27d56991ae133e27396875aa125820622d77425a21f01fb6a13487abec9c89bc6f3b9c712bc4265735ea166a971ec1135820d4fdbad7d2edf4b2089a19d3d9b0f68d9eb504b173f9b9299e5712a2fb29475b14582057ed5f127a2eee24c672540c1f5ad4982a0c6a2243f26201cde423323749e052155820c0b96abd9d14a8b035a2090fdf31f4384c10e88f6b8dd7fddc0580fb5f05e7da1658203408f8388004fb417dbf6b3b605385f2ce6c7aa73de4345e52659631f6e5b3aa175820b421860b1a0910661a0cd3ba9b3f099ab458d725331d562d569b6b9cf1b80d0a181858201c04a6dcb87299ba3065ccb02f8a714460aa5661328e8b29f5ccb9383ce677be181958205e4a5e021adf63188e0f03e62041c5faa80bf85b155a6702b82ba02cfbf24f09181a582094d881fdf7b36de66b265666d0b990f038b8fb441134e22c9e4c5e5ff9282993181b582094413153112a401eab5c684cea374f47ca690b207930e9bf1eb663aaa569eb5b181c58201bf23626c8d6a75c16ddb1be492b380cf8bd10899a85fa05d2ec843a1e9c9658181d5820c8ffe5a5411f7bfd3349182506dd499a42d9db4c0f604528bb4039c970f24cbe181e5820fdf881121823083afe44c1a74c883543bfb94af854bec944467f786a14e2f44f181f5820ca6fb0404818273b66b1f1c9d4b1585e8dd7fbcbcd7d14e9b2ee1a92a3000b8d1820582092c695e513e9c7042df96d40b5e09b149542ec644c2622704024795ecffa657018215820770c5857b684553550b074d649e221809507d46f0370a800699c94cb835a09521822582045a8a0f81eb068c01becf83a13658cf30a6e1327128bddadd2d3be7118114cca182358208e36f579e40d4cdfc220ca8f0949c39c470f5f10c88020ed8b341a4e3207378118245820b3d6bf5f8da2cf1f5d5ba57bd05d63bd1e4ff259a57f38d0dc49cc1a195b122f182558203e63f7e8a74601e056e6953e78002b0d791f04db572802e3f4cae972092e076818265820c5b537bcfc76cb5199eedd065648b4f4062e5b16923fb9d7124bd0897ed2ad94182758207244ec6f7c51e107fa3355ef53145adabc6a8fd60199245140f0a819a8ed9e09182858202c8c13d6a3fa39f3924b1c6c33a590634b18015fef67cb4174b355cbbde05429182958200c0d29266915ab30c3f78e0e1c032087b99ddbe00a2fa0854a4fb2c1ac91856e182a5820b0821f2e9a3fe156f550bb453895b00241fc43d2972c007abaa5fe218849adbe182b58206ae98113e09f952a76a3230eb0884e173bab9c1c3b58c8cf4a8135faef89b7e4182c58209c610d0821254c40f5f8e79c4c8f072adeba14a279db8e5a509d0571f3594e8b182d582089bfdfb8c1865f2ddcb0eb21a81ffe538a42fdf51e5dc094ffbf949cd30a9163182e582053384cec6c6e680a0276eac849df8bbf4298a04accd6f3aa0f3e4af867bb6ac6182f5820fcd73b6b40b319fc60e09a7b3b3855f6d7b2ac6c531e8be19fc986ff3e73eea818305820f9986da29be3251dce3181eaa14d71d671c4ed5dbaf929a09b2651b6be23d2cb18315820e62e724c4f78343d0e5ab4b7ecd88708ead42f0631249fd88988122b11e0ef58183258203313e000b8dc4ef6969ac0efb069f1d770627209bb1a39ceb13d2ce8c179d79f18335820742ea88674e0cac7cf02cd497f6b4730d119f864f420b7dab8ee296618bf6001183458208a50c8af91dc4b940c96670da015e3e5f9a1b2c6668f8a8aa966e4b32c4bf3221835582017931bf8d40f50864dd708388bcd70c6d3b8610bba7f43e0bbbd4d11463819d918365820bedf3681cea6bb7d8fb0f46b223183cc1e506cb4e3054ff90706fbfebce1d263183758205e7ff60e4042904703e660844611f77f7727d992033e0a513433ab391da833b8183858209812694ec07ca70995cad96167bd213bf870746840398c101650ce05f75402cd1839582045b58503a8f08dc1ea20aeea4b52e0d2b209d43e1ec4e2a84efb6c0566431274183a5820d66c0caca0ae839ec2a3b3708afc0255315f460fa7e0ed21653f20b52eeb54cc183b582091c34d69ced9922eda5b7c429244d994bddd5989f28da289d2153c0db3ebc9ed183c58202db276d011324985b19145c498d9d1c31dffa23d5cb2e43ced8de19dca2a0454183d58208e0330de00305e21f94a45ca564bf1de80ffeac53da970c74f5f976a1d9e9c88183e582061f9e4308cbe25a0edaf3fc4cb1769769681f4f0ca52839d701728b184cfe5c9183f5820638c88a189f5381c020902d2dee968eb3eb2e1761bd0b26d1565b68c38b97957184058201a29e1ea04507c67abf9724d58aea23832ce2f886846f20fc6fe168d954287fe1841582049b594db602d10b01f87e9ee2407639290ad6cadf24c1d0236b2a4fb73988a4e18425820d3b74317abcde1cc95af82a3159f3ed91db9ff2a50436d28084495be03335fd418435820ee13ebbe1063b940e69a8af260f191b94bc1358171f52b70352f551ef7d22c561844582007ba45af03eb8502bbfb9c5cd29faf5795f65d201b58af95ebe85b0757753a3318455820134fe588104c69f6146a77e6b1349b8453a4fc2a2c901b92c5af24bb542a704c184658204e7c4902dd07987a702466b3c5d22f7941520eb2132135eaa345ac59d02fa2ab18475820637e2c85b20fb9a8e31371c18879aa3f7350e32b8d38de05c91db0d0db97923b18485820d785a19c6ac1328bfa7c088f8d7df526fa93a08c608c80c4bfc1bf406cd7fce218495820041afdcbf3af930ed58c9d9ed2f40f9cbf2814e2b755cc0e72d5a4516217028b184a5820c69f98f6c5056e9a14b39f3bc958fbcba0eade3740fc40f42347d19557f57561184b58200b240b2e8b9631ff6e98c3e3161f57455911f12a82bde152d9360cd47c16512f184c5820c013a53802c9d56484b0b0c468b9181274afb838d9da44fdc3acad975f6356ff184d58201142510871116c472eeb2b3cb84e04c54ff9867c78735fb44184c1abeb1d2cf4184e5820b319552273ca120f54134aec7e1acc664bb760275d9b6ea86800c901155ac470184f58201b391f8651e919a3fee54ce68414c3bc953a8216d0178b192681a17aa55ab2d6185058200dd34a75cf7034a91a63e3c5515f87fd31efceef42e0a4ed36cefd726178ae35185158209ba08106b633a19178b268ac7934878fd174f6e8a7dbd8dc929d5682b98b727e1852582036604632d9bb131eabb68fc24fe129cf1b7e6671f46c44fed6b81232b945f5d1185358203cf8c33eec02dccd77c9467457bd09444f76f406f89e058e7e53fe827941528e1854582029b37c3885e953b08215a95b4917b2e7e02653d0910547f272b7c14d1e2ee77018555820bb9b17a230fac8fd219e1db95b0f9009de4dbd54f142e866144f1dd6a3fcfc86185658200e85a1ddb90de427acc3b8e108fffbfdc9f2b5745946bdb730adbb8aa96fe21518575820e76778db67508a2d43e6e9e64d9ac02498999225d64f375d49daeac5bcc2c58018585820fa838c245a5b295188ae296230130c6ea2a9fc885672fe3a0d4ee1bc6e02656b1859582082e9d69f7e4d204bf927b73472c29f6928a25ec811a3a9e2e315303a7f82ebc8185a5820fb1fe70f9ad244b1248bc6e1e6b0a8598ffe63fe25c7024c7c4bd8a82e315bbe185b58200ab52441bc7e41cef7916581ab04eb15220f464ad424462040e965bf34174a5d185c5820b4fe35fa4a8760b799593ba80aea885f7a747f4890fb6f6b36eea870280e6278185d58204c5e8489a4d428edc8dbd7f5f3cfed6e723686cfbf187de0af7ac37e6bc8ffec185e5820a602fda362efafd509acc5bba91bc16c17ff1a288caf3ae3bb6d44a4c08f3e50185f5820df405bf5331f2d9c88bb9a32cf77bb77b21bc0c4711b3d416dae647a342c774618605820f5b0993a368e3b320dacc9ba183060e770bd9e2e36f3214da05fa9fb0c3cec1a1861582008ca8a361ffa525126b341f154d8fe3a78a0db711cc7ddd384f0b441db7fb6e81862582048ad966b75f78735bb51d34eb9f52c18a30ff61b8fb7d3311ff693bd73107303186358206ab9ce04de190ec0f281d692b20e229e326161eed28aad4970227fc7b609018a18645820cf7433ada64fd8e63226d89d21e50be8b8dbb3c0d3c9cdc348d7d685c19025c218655820355e761e415cef358606fe27bcd2bcc98636ec33419ab3793656e09ae1fc62d51866582046b213baef9d70e2e3a660174a095d2f7e8ff04905bc780236ca9398c3bd6dda1867582035975932a1a863022e7377ddb3a247063e1957c00f1eed102da3cdd31c5ba38b186858202049b6c42a4fa8f658d88a2f9116ccc6e3db712a0b77e6311ecb811c594ca63e18695820a68701db39c166a2a0973461479e88b9af8be373d78e6bb0ae34f834e4674c92186a5820f69b7eef416078714784234ef909c923fd4826b5ef778556d2fce07334402012186b582095a95bc5b09371a8637dc8a367e222e6d9db804c7463be82011a889a6f0b3180186c58200a6aa06ec399c371413740ee8f516a281f3f8483c214fd35a133f22b875c1e42186d5820e20ccbfc6a00ec3421f16bb46ca8c10fc464716c9e7a4d0a9431878806493071186e58204232e51f4c0cfa537a66eb92331fd47574ad73add6e9eb6abc0ab24bfc07a6c7186f5820abd7a0df196bd7cafb5dd4b266dd3fccc22d56058936f0036730a70710731e52187058203d628e2d8ecc94c837f17a4bc050aeac5f04ae139573e2969c916d4ed06f4bf81871582021015c35a31fe7554a18957bec048b3861c16d259332eccb4a55034d100662801872582027e50fdb9bc22cf4b9f72c393a9f458b1319c584e7976ca775faad2d29fa863c187358208edffeef263e4a00904871d934bc78b6fd5ee0ee861c144153de0622d11a271118745820d779f6d936c5c6d9b3025015f12fb5a46e35bfaff0f44557d181a58f26100cb11875582059f96f92aed96e63ec367e4d8ecca55d0e359bee56d0bcb1babf64281bbe1200187658206f09cf27a0c933444dd5b055a42d6fdec15972dca31d8ada3a7942f852d5ae1018775820bf90458782ea6612c2ca8d01926cca4b74b234ddb734eca1133f1ee0d12ef6c4187858204ccf16e09072c18d52862be0466f01b29ac38751edde642fb32e3a1751d264fb18795820bd8c82359c9900f58388018a4ec6095e2c013e4344e94df998ec4528847d6eb6187a582084808b9929ffba4adf78f683437cecf85a9adf95d2d609bf535c769aad3b76b7776f72672e69736f2e31383031332e352e312e61616d7661ac005820b35164aa13f00856b3f61f7e472224714ac55f672a584b4e3e62d1e48574e0960158204dbf6b58d5a06b9a0f0058d24a2703d15e0111c889a1be1699133950559202ef025820b8e1eeeebe6d2c98a3c0bfaf2f28ebef7324c38a36b9d673db4aff1ca41b272903582089756b514958219b7cb468825521d8e93d40c3e9b39f54e21fc314adc2950bb50458207dc2af36ab3e8e6d0a95738fa656ce87464fffab562407293da4158b2d470f990558202beb9688946ec3ca6c258bed97b745723a0258758332bfd942b45af64c7fa3580658202eea7c645cbdc9d0a293de2456f654747849c8e5a8d0b994b427ed32d28b2ee107582063e4bb1799833b3b2f0d1b6b38e73972f0773ab73f73dccc7a0fff5da2f7db4b0858206e3c1adf024d0fdf30a920e0ce540feec92afce4687a241bc698b7e86822fabb095820f72d8546558ec7de179eb76a1e00cf1f2119d3eebaed67b4002d710fd2559e8e0a5820e40bfccc4b22968291977435a42f376096484d59ba960b116f80cf0f38cf076b0b5820334c81ab61d511f2afc1d2bbb583a888e5b7ea5969a74a6d00d7c79790dab211782d636f6d2e6170706c652e6964656e746974792d70726573656e746d656e742e646576656c6f7065722d74657374a1005820badd1dc03e3afa089455a0e84bf47f3ac9508d4a7242e61e9a933abf12e26b426d6465766963654b6579496e666fa1696465766963654b6579a40102200121582098e01d501f9b0b071d441cc3ce859b4b2888981e5f60824393a486e1f587be1522582062def1829572b03038f2b1b02358c7a6e40ddf56ad9bd01c8807d49ad1c983776f646967657374416c676f726974686d675348412d3235365840000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000006a6e616d65537061636573a1716f72672e69736f2e31383031332e352e318bd818586ca46672616e646f6d5820ebbd0cdadc6ea97b1aabf1d2cd36855539b15aefdf6823726f3f0a5997626661686469676573744944166c656c656d656e7456616c7565d903ec6a313938372d30322d303171656c656d656e744964656e7469666965726a62697274685f64617465d818587ca46672616e646f6d5820caf5ef57e9526bf5c5d7da479709c04e6f54b852db419b7d8fa2a7bd5093237b686469676573744944096c656c656d656e7456616c756576546573742049737375696e6720417574686f7269747971656c656d656e744964656e7469666965727169737375696e675f617574686f72697479d818586ea46672616e646f6d5820bebf6e3ed22a77a5d661616f7dd77f3ad2b3ee2c533d5dd976cde110d6c6424d6864696765737449440c6c656c656d656e7456616c7565634e2f4171656c656d656e744964656e74696669657276756e5f64697374696e6775697368696e675f7369676ed818586da46672616e646f6d5820d9c35f5e0adb12647dfa288448abf84b3eea9a0ad018742f28f5f4676f107d516864696765737449440d6c656c656d656e7456616c7565d903ec6a323035322d30322d313071656c656d656e744964656e7469666965726b6578706972795f64617465d81858b3a46672616e646f6d58201e07afb3e2b1cffbe378c5ba7ce8a5ddb652df5deaceacbb61a3a50c0dbc5f59686469676573744944146c656c656d656e7456616c756581a36a69737375655f64617465d903ec6a323030332d30322d31306b6578706972795f64617465d903ec6a323035322d30322d31307576656869636c655f63617465676f72795f636f6465614471656c656d656e744964656e7469666965727264726976696e675f70726976696c65676573d8185866a46672616e646f6d5820c0b62b99c058a3670bde37ec4bfbfd432bfd0ebc692a608b93d73108488bdd2a6864696765737449440b6c656c656d656e7456616c756562585871656c656d656e744964656e7469666965726f69737375696e675f636f756e747279d8185866a46672616e646f6d5820ac4ab74cb421ed700bdc3f872e56a36e602ad11c08970b3bc644984649eb073a686469676573744944016c656c656d656e7456616c75656647617263696171656c656d656e744964656e7469666965726b66616d696c795f6e616d65d818586ca46672616e646f6d5820c06c0ffba531e4b950ff4ab8f514baba366325bd48b268fecd22b0bfffc0f16d6864696765737449440f6c656c656d656e7456616c7565d903ec6a323030332d30322d313071656c656d656e744964656e7469666965726a69737375655f64617465d8185913e2a46672616e646f6d5820e5e0cfee5efd082b4c27d4672ee85f01c79066c0a227483730b05fded47deef4686469676573744944026c656c656d656e7456616c7565591383ffd8ffe000104a46494600010100004800480000ffdb008400020202020202030202030403030304050404040405070505050505070807070707070708080808080808080a0a0a0a0a0a0b0b0b0b0b0d0d0d0d0d0d0d0d0d0d01020202030303060303060d0907090d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0dffc20011080084006403012200021101031101ffc4001d000000060301000000000000000000000001030406070802050900ffda00080101000000007706398448a77af5f0fb2c31f27a710022dc5b6b1c026618ebe9dc26d770eef676ce6611f0554afd7166f7a431531b1d050370f732a5fea6cce4c434c79d7d1e920d2c394f792fecf044275cf917742d21c426e3af48e429e9cb50f6545ed6ce67149b99fd0a436897c4e8e915927ca94fad8b2c0d868e329019b4d9fbbcd8a4f798162743a279c35af798284e3e2b6cd8db2dd1e4b71549c049645887844b02cb020b11808c04c25984d3246392a47e12d6c97a37542469a2b9133699d93b832d947d69a0ba5bb0eda235674386c64b91e3c88d279d16b2af27c93e3894623cc0a76fffc4001b01000202030100000000000000000000000607000102040508ffda000801021000000066508e998ed5d022fb4cc5af22302e10bdf7e90a3b8f43d07b9156aacc87d0b2737ce14dd62c9afe6fc1aac69c00a14ae99df6c26a5e375fffc4001c0100010403010000000000000000000000080105060700020304ffda00080103100000001b16d276aa1b552e5b8fd95e8d98a5dcff002302332ec624ab66f0c9b1081beb2181c2f37b383d43152fd39fb0d8e83e535d6736dcbfa3455d0bba555534cfffc4002d10000104010303030303050000000000000301020405000611120710131421311522612324341620415171ffda0008010100010801555df37dbb6ebfe3dd576cd45aceb74eb5184d3fab6baf6bd66a8b52519ca811eebf19bae7276725c472e2fcaf7214601b8c6d59aa4f6e665654c92c2631e2087948fb46e45f08b8685d512ad1c5a9b4ee98bbefda5ca8f063125cbd45ae0d6602d6c56940f87208789e37b9a20bab673d14a2414a0f368412df0b6963d2faa92e93d24bf8cfce22e2fcf6ea0dc7dcda404189f504f19b4f74e013608df222f4b6884fdd05a0ea212f31eaad231ccbfa36150e868bbe98b2fa35db0c6ec98bf2b9fe93354cdf35dd848374e69127cb2c99b56d1b58d6322c71f04559426f15c9ec4d9735c45464559803c91ac87a33495aadcd0c792fc6fc62e7e335b104fbf9ef174c4647d58c8b58ce2888e86c178d3796362b576b3f0011ce917d1e3cd82ff1598161da1599d2942fd1a63df8cf64ec547bc4f6b2721472de89a7a2cd05706b630e2d9407a7d2e82ded1cf449ba865ccf178c720158391e7bf50d5a8d2452f50e38c373e66f4e0600e9710c58d4dd3b7911a541e6a1ae70b509585fe9f3d8d4b9d020e938ef2c79057863427832c7c679dc0f6ba6634892258f034ac6ad7492aebdac497731e3b292332aa9e3c476e8a9ba357db17e71dfc96e6a0ab0d9d7a3b34917941131f0e18cb09ae34c3437d8bc66b163234c86f7c29d50733152c144c1482628073ee9933153703971bb39bbb59f1fd8db32d04f55202e1f60d63073b47d55dc8f54776818de66c8b8903047189906faded6517e9b1a90456426a93b353bef9cc0190324a895e92203e106036c58641c99817f1d9d4b511a18cd606b098d8c090f48e2f00061eec5f6f75eef130c350928f507a19be320d95c5223964c4ac0b51c1b8b48e1e2048168dbbba7f8ff003dc689b76ffbdace9493e5c9930dfa8f51d57ed8add6baace9c101597ba824f2b0ad80cac9eb19bdd9bededddcae4f665242e06735f334d4396de4f4d2b1869ed06901198afcb68af258bca11bd5eddd7dbb0b8f1f7cbbbdafd3f0d66585c7546da5f1654e93ea5d05b09b1ee63846aa2931849fa5bb9588ab856ec1fb6ce6d5d59ca7b3d6fd480d80df53a6f4e753e6c692d8f7f5d695f6f1fd5568daaa996bd569460f8ea6c2ca7599d64cfe59cb23cb97117f69075deb5adfe1b3acbd46633865aebdd6975f6d891ee73b9115cab8dcaeb39f547f555f59d55b31c541cc48ac5c7c76371628f6cf4ece5b62c7626205ab8e1a22ed9e24db1068abb6285a99e26e714df2185aa35cffc4003c10000201020207050407080300000000000102110003122104133141516171102022328105334291142352a1b1c1e1243043536272b2d17392f0ffda0008010100093f013dd3afbc7f86af11d4e7f2a61a23a361bb6eeb8184ee826310239569f68b938466449e44e4689a344d1346b8f6b044412cc720053dcb563105b867035c279ed0a055ed75d1f5681530959dac18cc9ebc6acaa06020cf804709cff5ac569c44939e676503f49b49acb4e76dcb63233cc7e1df7d5dab4b89db80ab5a8b374e1c64cbb2fe545b5c00d9c633ab6670c35cda278c523304683946677c73a906309c60461ff7486ddf48f29233e33be945bd280256365c55da7911bfbd788901afa2c751ce77d784af95bed0dd5b78b7fed956e6441dd56e791d956756bbf008ce9b10e1bfc3b3d2bdd34dbb939e047ca7967de7c376ddf3008c8e13840f9524db0aa5036c34206cedce29608f388a67de60796bdea8d55dfee4ff0063ba71fed07351e12450f39fc3b08a61eb57153a98a75b88c0895339d62956c9374d1107490aa06ec2bfaf70e162ac01e0632a3e22d07ad69034445b4badbdb6e78b72f0e66bdb5759bf9574824fa62c46983b1112b9674e6db1dfc2b4b17b16689a45fc00f41998f94d21d188f3db57c56aea1f523a114231db04ee934b85f58c6e8fea3b0f4c311dcde267a55a2b6df4a95fed669abdaabae9e13d3857b3afa8b4155db68b854f9ae336d339eda385a46d69cb84ef8e3467c07efdf5a3176b601565ba21b817048c6d9efa1ab6d29cb3595618437203207a541da236e548a8516085ce4f3e7d87b3ec9ac5afd1713db81b788ac884cfad5d8b7f662980526158ec396c14cacd80f84ef1460848d9bf750048668e9414aa9c0206cde6b75cad87ba0fd1de0e2dc26a757189db7470f5ac5afc58ed31262d37f4c47df35a636917532b64314c239675705bb961610cc9603e13c6ad937009b87e15037d34cb330e3c0cf7d435969b5727310fb3efabcfa3ea4b78edc62c3bb6d694f700df0acdea0b0fbb2abd79d8f1c16ff09349372478df33c867b287d6dff08e39feb5f02c1ebbfbf9a3e47a1a6f1e8ee6d5c077acc4d22b5b6330738e9488bd2884b49e23426c68c85837179807d3b99774e0bab70c11f9d59795d8cb9ecab2e79b0029ce027351430e2b3fe247ee0626dc2b32d9fa9ab609a4a02bc2d680c268436f1dbc7b1e01f776c79ee37051f9ec156c6808ad8893171de3719111c62993d9ba68112d958b878ab7c27937ce996e211e6b64303ea2852d0f956956746589fad70bf76da0cb61bdee947c2cd1fcb1b40e7b68fd2746c3875aaa35a846f31e61c77d69097edef287cbd46d1d9a28d198ff0016e91708e8367ce6afbdfba7e2733970e43b6f5db3ff001bb27f89af6d69c8381ba5c7c9e6bda8a79b68f68b7cf0d7b674bb8bf612e6a93feb6f08a976e2c64fccf6df7d1eefda4313d78fad6896748b8a7de02527a812268b51344d4d4d1344d13dd9f37e42bfffc4002510010002020202010403010000000000000111210031415161718191a1b1d1c1e1f0f1ffda0008010100013f104355b6af2cdbce2b45cf24cfe70e437d38544afcce1b1955a33e22f005f58bee5ba92b9501043492641892249e20059a872a8046eee70e75f54fbe32441d5b95c01e053152aadedcd61a97e72382f21d9c718d56ccc01b715578a668a8180cc48bcf58a849542912d12069b263326646f2622810593379310d3e4c4a9a032453eafc6722ac54215175b772f193209d7d72c6387592c56ff584457bcd18e523ce5bb989c358b34903c16aa901b58c00875fa58a440909096a26724ceb668250a239a98afae128816512b1e186298d44f101e10bc631286b0f1a48b48a63f358b92102d93e78f190f2eee75c5780d3b3ac12107bc8851739094efc4e597957ef89624b924ca5408585634145898630e6c3a11707c265b7b932733b970ec0fc3083adc284e13664a84806d5192e4b21515c95112593cc984882450d5424561bc50e4c8441883c882c7158b147f4ff00dc3552c6f34686f9c2583b700593684fbc38876e0165b881eb78b436c016e3c0fce46c10004518d62d77d64eab7932fb13f6cbde1005f49d548e293a5200381edef147f6cd44cc1c0eb73857f5de41fe9396b396f0e15fac0f8076e044915bfbce465d82cb4fed92925dc651131a7795658b905f591a9b4222ba94c668c8c84352493ef196c8905a5ee1f5805448a68cf826158ebbed31480c5e28ac6e6b291445b10be1bcbceb58596b6f70de209be251129f26d46a839c15908d81ba4a4f038115881a1b91b3d3ce1d9dc64d0f5cb92cbd0c479a57c2c3b39e0692d8a84c168913e3094130bd92478adb8a226344b1b00b0b62e307ab798d386cf9c921377de2863307f060d2421a6303e1f5930548961089686aa463048d613560cd8902fc4182a1b0494b525f00958a963214b24d840221355e48c91ec04e4d19248ce1b30a68cdae4b69369132294640143ae677ac170e6c48caaa9b13d6b8c25d11d233912d2fac42a0dde4d8660c9205293d914771049e78c9c0523d2cc2cc52ea5874af6e028df0a406e22b12f0250ae52bc6153901749f00fa387465a48603a78f8c548712931dccde8ca52547570b6fac45a4a908fb719195c4b3f6314243872294f35bc811196484ec778d04c02f42fab39c42e5eb1a01e55c74389f4402805950149481c7cf4390b45a9efaac0690e3c4d95e6ec6cc97ca15e194f5d1b708308e0c959169d506b1b57bc99a5f3888c137eb296fbc74ff00ab0b4acce119dec815a46a088fbce69fc436744434d286386148b09a7d8042b6387d28a3a9d598fc19203ec170b1944ba018dcad14dac82d2a0f6ab279d9ff00a3b9c21bd2bf4c986974e108da7bc92ef2f822f22ebd5e14b053efc9c7674e4f4b6e6c905f113c62ddc42049b3a7a9c5b6bb4c21cdb298bcac2280ba37e71fa596d3827a4c3de4088739f07e71a1ef18ef6d47832376c18bb85ebfe6785e30dd3ac6a7dbc5120e479c2f4d0703c1b47eb9278ba8279db86cb032813a7c64584b1c12a8fa2b92c4f59ea2af17de3f95df8c9b522cfe7bcd13c64baeacf9c12cb5c02f7e32dfc36bcec7eb83512e63f9c88f8b451ee321840371fe9c9dd541d8d8f84a4c35db99a798eceb1e93c6b2552e0dfbc896cf87831ee39c89cc4489b9dbf2a036e221c921952017c04bde1ce7cd7472664e628e161f05005b8854c564462e482bde68aef19465d409c721e6568370997c18db3402950c50a4b70b40178b31413b4950ea276175832478b04f0f0be10c6291bfe0f3892bb216f1817cd1d6036c85e8da1c7800311a7e3109a9e1c9a3abbfc698e1fe8761fb640107fd61f6c0a6159424ee84f99c91c8e67d6172bb478de51c3628434ce8583c04c442414e608441d9207a327e4ad2938f8c5005be63f5969f74fd60ca70f89fc61323f67eb09bf823f590b7c91fac6f290f8fd62753bf1fac0a49d4f1fac90997591025b71fd688e3f067ffc40036110001020403040705090000000000000001020300040511062131101241510713202261a1d1147191c1f116323342728192b1e1ffda0008010201013f008240173151c46428b72bfcbd047da8984803741e679fa4494da26590f37a1ec622ae2d2b530daac9191f1876baca4948ce062046fee9161187eae5b792a4abba4e7d8c4ce290dac83a93124ddf589c60df2118796549536ad4452dd5392885af5b6c22e2d18897d594b4ae6627b752849697bc7dd68927105443ab20780bc52661299939dc0e314f4144b3693ad8467b3a46a4294844db43c0fcbd3f684a82884a85a1c5042ae91184e98e4e4f240195c13fa41f9e9b2e365629c99f9372515f9879ea3ce0f592ae16d63306262656e7723a3ca4f512ca9c5fde5e5ee03fdfebb13736d4b34a7df55923530fd403f32e2ca6e092403c2e6f0a7edf86900c600ad32b96f6059b2c1247883cbc46d9baeb691bac6679f08abb06a29dd9851cb4fa69070bad26e8707c3eb030ebe4d94a1123406a5d61c59de234e11255a75aeeb9de1e702b52c45ee7e10348e10206d31fffc40032110002000404030702060300000000000001020003041105062131124151071022618191b132a11315244272d171c1f0ffda0008010301013f0030aa58d86f197b202b2ad46244ff000dbdcffa1ef07b38a17676fc46507602da7bef18b6173b0faa6a59db8e7c88e4445cc6b0632365194d212baa5789db55f21cb4ebcf9e9689181ce61c5070060bc40eb19cb2e255d23864bcc504a9e77e97f3e9de6328cb471285ac028f88a97e11a4489a2dac66240a55d7631992965d3e273a54afa41f9d6df78b0842437108caa167a19e9b10368a32c5d83a708e5ade2ac305065a063e66c2319a62f4c2c2c4da31c9a26d7ce9aba82c7e62f00da3b19cc481a661b3db5dd7fc731e9bfa9865b6c6152fa318ed071b9586e1331d8d9882abfc8836fefd219ef03bb2e630f8562526bd3f61d4751b11ea2f141569365ace97a8207b189b53c4069611db1e3e6a2b65e1a9f4cbf11f32dfd0f9ee17b7750505456cf5a6a55e276d80ff00b6ea628a8cd3514993c5e2550a4f5b0b4589fad898ed6b2f54ad6fe6d2d6f2d8006dfb48d35f23c8f5ee0ba4611916a66387adf0af4dc9f6d07cc6072246127f46805f7d353ebbfde17322116743ef0d98640175537f48adc6664f432c2d81df9c6339329aaaf369bc0ff63e9cbd20e4cc4c1b708f710617bd7bff00ffd971656c656d656e744964656e74696669657268706f727472616974d8185864a46672616e646f6d582067078941dc4f5227a2d8a90d5a2afeb5a20fc9c4efba56ff66d8e25f43c1cb0e686469676573744944006c656c656d656e7456616c7565654b656c6c7971656c656d656e744964656e7469666965726a676976656e5f6e616d65d818586da46672616e646f6d5820bb446abf3b2b3f27b2c70fe9ee59da756b5e31aadc8e6a930b402d746e995f906864696765737449440e6c656c656d656e7456616c75656944313233343536373871656c656d656e744964656e7469666965726f646f63756d656e745f6e756d626572".fromHex()
        val sessionTranscriptBytes = "83f6f6826564636170695820138a2435b18634c55210630096744133df5a44449c8dc678bf56d9ea5cf6bf63".fromHex()
        val response = DeviceResponseParser(
            encodedDeviceResponse = deviceResponseBytes,
            encodedSessionTranscript = sessionTranscriptBytes
        ).parse()
        assertEquals(1, response.documents.size)
        assertFalse(response.documents[0].issuerSignedAuthenticated)
        assertEquals(0, response.documents[0].issuerCertificateChain.certificates.size)
        assertTrue(response.documents[0].deviceSignedAuthenticated)
    }

    companion object {
        private const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"
        private const val MDL_NAMESPACE = "org.iso.18013.5.1"
    }
}