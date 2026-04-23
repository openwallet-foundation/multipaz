package org.multipaz.mdoc.zkp.longfellow

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.util.fromHex
import org.multipaz.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests covering `IssuerSignedItem` byte preservation when used with
 * [LongfellowZkSystem.generateProof]. These tests assert the behaviour that Longfellow
 * proof generation needs in order to succeed on credentials issued with any key order.
 *
 * ## Background
 *
 * The ISO/IEC 18013-5 `IssuerSignedItem` CBOR map has four keys (`digestID`, `random`,
 * `elementIdentifier`, `elementValue`) and the spec does not mandate a particular key
 * ordering. Some issuers — for example the EUDI issuer — emit these maps
 * in an order such as `random, digestID, elementValue, elementIdentifier`.
 *
 * The MSO `ValueDigests` map contains `SHA-256(IssuerSignedItemBytes)` over the exact
 * bytes the issuer produced. For the Longfellow prover to succeed, the
 * `Tagged(24, Bstr(X))` bytes it receives via `longfellowDocBytes` need to hash to the
 * same value — which means the `X` bytes of each `IssuerSignedItem` should reach the
 * prover unchanged from what the issuer signed.
 *
 * At the moment, `MdocDocument.toDataItem()` → `IssuerNamespaces.toDataItem()` →
 * `IssuerSignedItem.toDataItem()` rebuilds the map from the parsed data class and emits
 * a fixed key order:
 *
 * ```kotlin
 * // multipaz/.../IssuerSignedItem.kt
 * fun toDataItem(): DataItem = buildCborMap {
 *     put("digestID", digestId)
 *     put("random", random.toByteArray())
 *     put("elementIdentifier", dataElementIdentifier)
 *     put("elementValue", dataElementValue)
 * }
 * ```
 *
 * For credentials whose issuer used a different key order, the resulting
 * `IssuerSignedItemBytes` therefore differ from the issuer-original bytes, and the
 * Longfellow native prover returns `MDOC_PROVER_GENERAL_FAILURE` (error code 6) on the
 * MSO digest check. The tests in this file surface that behaviour and will pass once
 * `IssuerSignedItem` bytes are preserved through the parse/emit round-trip.
 *
 * ## Sample credential
 *
 * [SAMPLE_NON_CANONICAL_MDOC_HEX] is an mDL issued by EUDI whose single
 * `age_over_18` `IssuerSignedItem` is encoded in the order
 * `random, digestID, elementValue, elementIdentifier`. It validates against its own MSO.
 *
 * Validity window: `2026-04-22T07:34:51Z .. 2026-07-21T07:34:51Z`.
 * [TEST_TIME] is set inside that window.
 *
 * ## Tests
 *  - [proofGenerationSucceedsOnNonCanonicalIssuer] invokes the full
 *    [LongfellowZkSystem.generateProof] and asserts it returns a valid proof. With the
 *    current re-encoding behaviour it raises `ProofGenerationException` (error code 6).
 *  - [reEncodedIssuerSignedItemDigestMatchesMso] runs entirely in CBOR/SHA-256 and does
 *    not need the native library. It asserts that the bytes produced by multipaz's
 *    data-class path hash to the same value as the issuer's original bytes.
 */
class NonCanonicalIssuerOrderingTest {

    /**
     * Invokes [LongfellowZkSystem.generateProof] with the sample credential
     * and a v7 circuit (1 attribute) and asserts that a non-empty proof is returned.
     *
     * Today this call raises `ProofGenerationException` with error code 6, because the
     * `Tagged(24, Bstr(X))` bytes produced by `document.toDataItem()` use a different
     * `IssuerSignedItem` key order than the issuer used, so `SHA-256(B)` does not match
     * the MSO digest and the native prover's consistency check fails before the v7
     * circuit's ordering-tolerance logic comes into play.
     *
     * A v7 circuit is used here because v7 is meant to tolerate any `IssuerSignedItem`
     * key order inside the circuit's witness — so if this test still fails on v7, the
     * issue is in the bytes presented to the prover rather than in the circuit.
     */
    @Test
    fun proofGenerationSucceedsOnNonCanonicalIssuer() = runTest {
        val system = LongfellowZkSystem()
        system.addDefaultCircuits()

        // v7, 1 attribute (matches age_over_18 disclosure on our sample).
        val spec = ZkSystemSpec(id = "non_canonical_test", system = system.name).apply {
            addParam(
                "circuit_hash",
                "8d079211715200ff06c5109639245502bfe94aa869908d31176aae4016182121",
            )
            addParam("num_attributes", 1)
            addParam("version", 7)
            addParam("block_enc_hash", 4151)
            addParam("block_enc_sig", 4096)
        }

        val sessionTranscript = Cbor.decode(SAMPLE_NON_CANONICAL_TRANSCRIPT_HEX.fromHex())
        val document = MdocDocument.fromDataItem(
            Cbor.decode(SAMPLE_NON_CANONICAL_MDOC_HEX.fromHex()),
        )

        // Expected to succeed. With the current re-encoding behaviour this raises
        // ProofGenerationException: Proof generation failed with error code: 6.
        val zkDocument = system.generateProof(
            zkSystemSpec = spec,
            document = document,
            sessionTranscript = sessionTranscript,
            timestamp = TEST_TIME,
        )

        assertNotNull(zkDocument, "generateProof should return a ZkDocument")
        assertTrue(
            zkDocument.proof.size > 0,
            "generateProof should return a non-empty proof",
        )
        println("Proof generated, size = ${zkDocument.proof.size} bytes")
    }

    /**
     * For each disclosable `IssuerSignedItem`, this test checks that re-encoding it via
     * the data-class path (`Cbor.encode(Tagged(24, Bstr(Cbor.encode(item.toDataItem()))))`)
     * yields the same SHA-256 as the issuer-original `IssuerSignedItemBytes` held in the
     * MSO `ValueDigests`.
     *
     * Today `IssuerSignedItem.toDataItem()` rebuilds the map in a fixed key order, so for
     * this non-canonically-ordered credential the SHA-256 diverges from the MSO digest and
     * the assertion at the bottom of the loop fails.
     *
     * Before that check, the test asserts that the credential is internally consistent: the
     * **original** bytes (extracted from the raw CBOR tree without going through
     * `IssuerSignedItem.fromDataItem`) hash to the MSO digest. That guards against a broken
     * sample accidentally being interpreted as a product issue.
     */
    @Test
    fun reEncodedIssuerSignedItemDigestMatchesMso() = runTest {
        val mdocBytes = SAMPLE_NON_CANONICAL_MDOC_HEX.fromHex()
        val mdocDataItem = Cbor.decode(mdocBytes)
        val document = MdocDocument.fromDataItem(mdocDataItem)

        val valueDigests: Map<String, Map<Long, ByteString>> = document.mso.valueDigests
        assertTrue(valueDigests.isNotEmpty(), "MSO.valueDigests should not be empty")

        // Walk the RAW `issuerSigned.nameSpaces` array of Tagged(24, Bstr(X)) items.
        val rawNameSpaces = mdocDataItem["issuerSigned"]["nameSpaces"]
        var checked = 0
        for ((nsKey, nsValue) in rawNameSpaces.asMap) {
            val namespace = nsKey.asTstr
            for (taggedItem in nsValue.asArray) {
                val innerX: ByteArray = taggedItem.asTagged.asBstr
                val digestId = Cbor.decode(innerX)["digestID"].asNumber

                val expected: ByteArray = valueDigests[namespace]!![digestId]?.toByteArray()
                    ?: fail("MSO has no digest for $namespace/digestID=$digestId")

                // Internal-consistency check: the original issuer bytes must hash to the
                // MSO digest. If this fails, the sample credential itself is inconsistent
                // and the rest of the test would be meaningless.
                val originalEncoded: ByteArray = Cbor.encode(taggedItem)
                val originalDigest = Crypto.digest(Algorithm.SHA256, originalEncoded)
                assertContentEquals(
                    expected,
                    originalDigest,
                    "SHA-256 of the original IssuerSignedItemBytes for " +
                            "$namespace digestID=$digestId does not match MSO.valueDigests. " +
                            "The sample credential in this test is not internally consistent.",
                )

                // Assertion under test: the bytes produced by re-encoding via
                // IssuerSignedItem.toDataItem should hash to the same value as the issuer
                // original. This fails for credentials whose issuer used a key order
                // other than the one that IssuerSignedItem.toDataItem emits.
                val parsedItem =
                    document.issuerNamespaces.data[namespace]!!.values
                        .first { it.digestId == digestId }
                val reEncodedX = Cbor.encode(parsedItem.dataItem)
                val reEncodedTagged = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(reEncodedX)))
                val reEncodedDigest = Crypto.digest(Algorithm.SHA256, reEncodedTagged)

                println(
                    "namespace=$namespace digestID=$digestId\n" +
                            "  MSO.expected     = ${expected.toHex()}\n" +
                            "  SHA256(original) = ${originalDigest.toHex()}\n" +
                            "  SHA256(reEncoded)= ${reEncodedDigest.toHex()}",
                )
                println(
                    "  originalBytes    = ${originalEncoded.toHex()}\n" +
                            "  reEncodedBytes   = ${reEncodedTagged.toHex()}",
                )

                assertContentEquals(
                    expected,
                    reEncodedDigest,
                    "SHA-256 of the re-encoded IssuerSignedItemBytes for " +
                            "$namespace/digestID=$digestId does not match MSO.valueDigests. " +
                            "IssuerSignedItem.toDataItem() emits a fixed key order " +
                            "(digestID, random, elementIdentifier, elementValue). The issuer " +
                            "here used a different order, and the MSO digest is computed over " +
                            "those original bytes. For Longfellow proof generation to succeed " +
                            "on credentials like this, the IssuerSignedItem bytes read from " +
                            "CBOR need to be preserved through the parse/emit round-trip.",
                )
                checked++
            }
        }
        assertTrue(checked > 0, "Sample credential had no IssuerSignedItem to check")
    }

    companion object {
        /**
         * EUDI-issued credential (docType `org.iso.18013.5.1.mDL`, namespace
         * `org.iso.18013.5.1`, single disclosable `age_over_18 = true`).
         *
         * The inner `IssuerSignedItem` map keys are encoded in the order
         * `random, digestID, elementValue, elementIdentifier`, which differs from the
         * order that `IssuerSignedItem.toDataItem()` currently emits.
         * Valid from 2026-04-22T07:34:51Z to 2026-07-21T07:34:51Z.
         */
        private const val SAMPLE_NON_CANONICAL_MDOC_HEX =
            "a367646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6c6973737565725369676e6564a26a6e616d65537061636573a1716f72672e69736f2e31383031332e352e3181d8185864a46672616e646f6d58203cbe2b0aa01212848ec8b76bb5be5ddc6934e2833a6bab79bc19d97a132f0586686469676573744944086c656c656d656e7456616c7565647472756571656c656d656e744964656e7469666965726b6167655f6f7665725f31386a697373756572417574688443a10126a118215902e3308202df30820285a00302010202147f7968853983300992fd85ffab886aa11c89079e300a06082a8648ce3d040302305c311e301c06035504030c1550494420497373756572204341202d205554203032312d302b060355040a0c24455544492057616c6c6574205265666572656e636520496d706c656d656e746174696f6e310b3009060355040613025554301e170d3235303431303134333735325a170d3236303730343134333735315a30523114301206035504030c0b504944204453202d203031312d302b060355040a0c24455544492057616c6c6574205265666572656e636520496d706c656d656e746174696f6e310b30090603550406130255543059301306072a8648ce3d020106082a8648ce3d03010703420004bb580016a8fcded14b37cfca5a8f254f581466ad16c28b95f6b3d1af9726d0cadc13ba67199de8fd0642df020965a17e6dbfe36059f0df82dff4eacfb9b55e25a382012d30820129301f0603551d2304183016801462c7944728bd0fa21620a79ac2499444f101d3c7301b0603551d110414301282106973737565722e65756469772e64657630160603551d250101ff040c300a06082b8102020000010230430603551d1f043c303a3038a036a034863268747470733a2f2f70726570726f642e706b692e65756469772e6465762f63726c2f7069645f43415f55545f30322e63726c301d0603551d0e04160414aa5fe8a71910958cb4965693a0f6c313f9b211c1300e0603551d0f0101ff040403020780305d0603551d1204563054865268747470733a2f2f6769746875622e636f6d2f65752d6469676974616c2d6964656e746974792d77616c6c65742f6172636869746563747572652d616e642d7265666572656e63652d6672616d65776f726b300a06082a8648ce3d0403020348003045022100d255483b2a4f722419c2965a049eb9b90339d8b9fd413d6f5185fd7e5f41115a022069e6dead1e1f17c0584fb2dcce1cca29bc10ff1b09acd110148264a7ea4bbc1a5903ecd8185903e7a766737461747573a26b7374617475735f6c697374a26369647819185163757269786868747470733a2f2f6973737565722e65756469772e6465762f746f6b656e5f7374617475735f6c6973742f46432f6f72672e69736f2e31383031332e352e312e6d444c2f61383031653265662d666431302d343262642d386232352d6238326166623433383366306f6964656e7469666965725f6c697374a2626964643632323563757269786668747470733a2f2f6973737565722e65756469772e6465762f6964656e7469666965725f6c6973742f46432f6f72672e69736f2e31383031332e352e312e6d444c2f61383031653265662d666431302d343262642d386232352d62383261666234333833663067646f6354797065756f72672e69736f2e31383031332e352e312e6d444c6776657273696f6e63312e306c76616c6964697479496e666fa3667369676e6564c074323032362d30342d32325430373a33343a35315a6976616c696446726f6dc074323032362d30342d32325430373a33343a35315a6a76616c6964556e74696cc074323032362d30372d32315430373a33343a35315a6c76616c756544696765737473a1716f72672e69736f2e31383031332e352e31ac00582046b0ab1cf4fef9e767e2c728e58d2b1b3f93dbda81ba2b4dfccd374f10adc996015820fb9a93e9027b9ec48a51e5049900590dcef06713e736bc267785f0bd69c7e9c9025820a7903fc05f9ce1c70c0b269075327322d2c8c373a70fb71dfff6f8409a7e3122035820bcd9bcb368a64dd0ce08594766e2edd4667d0ab7bfde13c00f7b88db47e3364904582063b7795874a855798b362927af030655e7721786155a52c940e524fc7ded8161055820253696193d43fbe53043e5ec3641534d6dc64c486da6a1ae9f10d28549d0e32a065820ba359c3647e32f4ca225948c0b95f4a6281ad2c170a27a4fd0d09b8285a3e0cb075820b131ba6033a6449e9bb6d8166839d076b8676f787f7e6dbabfccc9864ed1352f0858207bd809ea1ec20f25e3b4d3d9aa301aa5b4883430de2fa4790c4d55102e6ff7c109582083bd130701411402a2f082311ed9345cee17e1113a86b5df31d2441f15af7cbd0a5820a02cf53624cbb7b632ff583eb1b01fb9c9e616a748acef1ac693d7468fe16d580b5820dbce4ef87c7b3c07fc04faaeb3696ec3772324ef01ebb1f93a90bcbf55cccd436d6465766963654b6579496e666fa1696465766963654b6579a401022001215820221ba0d434ad5ca47614d4975d16e5e0187475bb4e39eda4f1103948bfe3aebd2258202e36f15556bc9abb7669bcda8f4c21244f17dde2b5df01df21b0e5714a60922f6f646967657374416c676f726974686d675348412d3235365840e9fca6ab7698721a6208ebf3619da15bc2e0b2700ad81ad00ba840a608cfac229518a800aa3749adc287c62fdda6c05c6e6a3e174421b03923d366d7514d2f5d6c6465766963655369676e6564a26a6e616d65537061636573d81841a06a64657669636541757468a16f6465766963655369676e61747572658443a10126a0f65840f5a35cc53287d0e1ca0d1f69c6e335cc2ee311fdd41c11daed48280c42a0938acbf6d9c714f81d2d7113092a852a5b3fe5d672aff688411338ec361d237e067f"

        /** Session transcript paired with [SAMPLE_NON_CANONICAL_MDOC_HEX]. */
        private const val SAMPLE_NON_CANONICAL_TRANSCRIPT_HEX =
            "83f6f6826564636170695820e2ab0276392e451d2b34f4db7f4250c0e4895eab3684288f2fcdc6991263fd02"

        /** Fixed test time, inside the sample credential's validity window. */
        private val TEST_TIME =
            LocalDateTime(2026, Month(6), 1, 0, 0, 0).toInstant(TimeZone.UTC)
    }
}
