package org.multipaz.facenet

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FaceMatchingTest {

    @Test
    fun testFaceMatchingPairwiseSimilarity() = runTest {
        val matcher = FaceNetFaceMatcher(
            modelBytes = testModelMobileFaceNet,
            config = FaceNetModelConfig.MOBILE_FACENET
        )
        if (!matcher.isSupported) {
            println("Skipping FaceMatchingTest: not supported on this platform")
            return@runTest
        }

        val corpus = mapOf(
            "qualcomm_1" to (FaceTestData.decodeImageByteString(FaceTestData.QUALCOMM_DEMO_1_BASE64) to "qualcomm"),
            "qualcomm_2" to (FaceTestData.decodeImageByteString(FaceTestData.QUALCOMM_DEMO_2_BASE64) to "qualcomm"),
            "warren" to (FaceTestData.decodeImageByteString(FaceTestData.WARREN_PORTRAIT_BASE64) to "warren"),
            "warren_114th" to (FaceTestData.decodeImageByteString(FaceTestData.WARREN_PORTRAIT_114TH_BASE64) to "warren"),
            "erika_2010" to (FaceTestData.decodeImageByteString(FaceTestData.ERIKA_MUSTERMANN_BASE64) to "erika_2010"),
            "erika_2001" to (FaceTestData.decodeImageByteString(FaceTestData.ERIKA_MUSTERMANN_2001_BASE64) to "erika_2001"),
            "male" to (FaceTestData.decodeImageByteString(FaceTestData.MALE_PORTRAIT_BASE64) to "male"),
            "female" to (FaceTestData.decodeImageByteString(FaceTestData.FEMALE_PORTRAIT_BASE64) to "female"),
            "bob_with_glasses_1" to (FaceTestData.decodeImageByteString(FaceTestData.BOB_WITH_GLASSES_1_BASE64) to "bob"),
            "bob_with_glasses_2" to (FaceTestData.decodeImageByteString(FaceTestData.BOB_WITH_GLASSES_2_BASE64) to "bob"),
            "bob_without_glasses_1" to (FaceTestData.decodeImageByteString(FaceTestData.BOB_WITHOUT_GLASSES_1_BASE64) to "bob"),
            "bob_without_glasses_2" to (FaceTestData.decodeImageByteString(FaceTestData.BOB_WITHOUT_GLASSES_2_BASE64) to "bob"),
            "alice_with_glasses_1" to (FaceTestData.decodeImageByteString(FaceTestData.ALICE_WITH_GLASSES_1_BASE64) to "alice"),
            "alice_with_glasses_2" to (FaceTestData.decodeImageByteString(FaceTestData.ALICE_WITH_GLASSES_2_BASE64) to "alice"),
            "alice_without_glasses_1" to (FaceTestData.decodeImageByteString(FaceTestData.ALICE_WITHOUT_GLASSES_1_BASE64) to "alice"),
            "alice_without_glasses_2" to (FaceTestData.decodeImageByteString(FaceTestData.ALICE_WITHOUT_GLASSES_2_BASE64) to "alice"),
        )

        val embeddings = mutableMapOf<String, FaceEmbedding>()
        val identities = mutableMapOf<String, String>()

        for ((name, pair) in corpus) {
            val (bytes, identity) = pair
            println("CORPUS_CHECK: $name, bytes.size=${bytes.size}")
            identities[name] = identity

            val emb = matcher.getFaceEmbedding(bytes)
            assertNotNull(emb, "Failed to compute embedding for $name")
            assertEquals(128, emb.embedding.size)

            embeddings[name] = emb
        }

        val names = embeddings.keys.toList()

        // 1. Same Identity Test (Qualcomm official demo pair: two different photos of the same individual)
        val q1Emb = embeddings["qualcomm_1"]!!
        val q2Emb = embeddings["qualcomm_2"]!!
        val simQualcomm = q1Emb.calculateSimilarity(q2Emb)
        println("Qualcomm demo pair ALIGNED similarity: $simQualcomm")
        assertTrue(
            simQualcomm >= FaceNetModelConfig.MOBILE_FACENET.matchThreshold,
            "Qualcomm same-identity pair similarity ($simQualcomm) must be >= threshold (${FaceNetModelConfig.MOBILE_FACENET.matchThreshold})"
        )

        // 2. Same Identity Test (Elizabeth Warren 113th vs 114th Congress)
        val warrenEmb = embeddings["warren"]!!
        val warren114thEmb = embeddings["warren_114th"]!!
        val simWarren = warrenEmb.calculateSimilarity(warren114thEmb)
        println("Warren 113th vs 114th ALIGNED similarity: $simWarren")
        assertTrue(
            simWarren >= 0.50f,
            "Warren 113th vs 114th cross-session similarity ($simWarren) must be >= 0.50"
        )

        // 3. Test Erika Mustermann 2001 vs 2010:
        // Bundesdruckerei used different employees as sample models for the 2001 Reisepass
        // and 2010 Personalausweis, so they are distinct individuals.
        val erika2010Emb = embeddings["erika_2010"]!!
        val erika2001Emb = embeddings["erika_2001"]!!
        val simErika = erika2010Emb.calculateSimilarity(erika2001Emb)
        println("Erika 2001 vs 2010 ALIGNED similarity: $simErika")
        assertTrue(
            simErika < FaceNetModelConfig.MOBILE_FACENET.matchThreshold,
            "Erika 2001 and 2010 should not match as same person ($simErika < ${FaceNetModelConfig.MOBILE_FACENET.matchThreshold})"
        )

        // 4. Same Identity Test for Bob (Synthetic Portraits with and without glasses)
        val bobG1Emb = embeddings["bob_with_glasses_1"]!!
        val bobG2Emb = embeddings["bob_with_glasses_2"]!!
        val bobNoG1Emb = embeddings["bob_without_glasses_1"]!!
        val bobNoG2Emb = embeddings["bob_without_glasses_2"]!!

        val simBobGlasses = bobG1Emb.calculateSimilarity(bobG2Emb)
        println("Bob with glasses 1 vs 2 similarity: $simBobGlasses")
        assertTrue(
            simBobGlasses >= 0.60f,
            "Bob with glasses 1 vs 2 similarity ($simBobGlasses) must be >= 0.60"
        )

        val simBobNoGlasses = bobNoG1Emb.calculateSimilarity(bobNoG2Emb)
        println("Bob without glasses 1 vs 2 similarity: $simBobNoGlasses")
        assertTrue(
            simBobNoGlasses >= 0.60f,
            "Bob without glasses 1 vs 2 similarity ($simBobNoGlasses) must be >= 0.60"
        )

        val crossPairs = listOf(
            ("bob_with_glasses_1" to "bob_without_glasses_1") to bobG1Emb.calculateSimilarity(bobNoG1Emb),
            ("bob_with_glasses_1" to "bob_without_glasses_2") to bobG1Emb.calculateSimilarity(bobNoG2Emb),
            ("bob_with_glasses_2" to "bob_without_glasses_1") to bobG2Emb.calculateSimilarity(bobNoG1Emb),
            ("bob_with_glasses_2" to "bob_without_glasses_2") to bobG2Emb.calculateSimilarity(bobNoG2Emb),
        )
        for ((pair, sim) in crossPairs) {
            println("Bob cross-eyewear ${pair.first} vs ${pair.second} similarity: $sim")
            assertTrue(
                sim >= 0.50f,
                "Bob cross-eyewear ${pair.first} vs ${pair.second} similarity ($sim) must be >= 0.50"
            )
        }

        // 5. Same Identity Test for Alice (Synthetic Portraits with and without glasses)
        val aliceG1Emb = embeddings["alice_with_glasses_1"]!!
        val aliceG2Emb = embeddings["alice_with_glasses_2"]!!
        val aliceNoG1Emb = embeddings["alice_without_glasses_1"]!!
        val aliceNoG2Emb = embeddings["alice_without_glasses_2"]!!

        val simAliceGlasses = aliceG1Emb.calculateSimilarity(aliceG2Emb)
        println("Alice with glasses 1 vs 2 similarity: $simAliceGlasses")
        assertTrue(
            simAliceGlasses >= 0.50f,
            "Alice with glasses 1 vs 2 similarity ($simAliceGlasses) must be >= 0.50"
        )

        val simAliceNoGlasses = aliceNoG1Emb.calculateSimilarity(aliceNoG2Emb)
        println("Alice without glasses 1 vs 2 similarity: $simAliceNoGlasses")
        assertTrue(
            simAliceNoGlasses >= 0.50f,
            "Alice without glasses 1 vs 2 similarity ($simAliceNoGlasses) must be >= 0.50"
        )

        val aliceCrossPairs = listOf(
            ("alice_with_glasses_1" to "alice_without_glasses_1") to aliceG1Emb.calculateSimilarity(aliceNoG1Emb),
            ("alice_with_glasses_1" to "alice_without_glasses_2") to aliceG1Emb.calculateSimilarity(aliceNoG2Emb),
            ("alice_with_glasses_2" to "alice_without_glasses_1") to aliceG2Emb.calculateSimilarity(aliceNoG1Emb),
            ("alice_with_glasses_2" to "alice_without_glasses_2") to aliceG2Emb.calculateSimilarity(aliceNoG2Emb),
        )
        for ((pair, sim) in aliceCrossPairs) {
            println("Alice cross-eyewear ${pair.first} vs ${pair.second} similarity: $sim")
            assertTrue(
                sim >= 0.40f,
                "Alice cross-eyewear ${pair.first} vs ${pair.second} similarity ($sim) must be >= 0.40"
            )
        }

        // 6. Cross-Identity Matrix (All different identities must be below model match threshold)
        println("\n=== Pairwise Similarity Matrix ===")
        for (i in names.indices) {
            for (j in i + 1 until names.size) {
                val n1 = names[i]
                val n2 = names[j]
                val sim = embeddings[n1]!!.calculateSimilarity(embeddings[n2]!!)
                println("$n1 vs $n2 : $sim")
                if (identities[n1] != identities[n2]) {
                    assertTrue(
                        sim < FaceNetModelConfig.MOBILE_FACENET.matchThreshold,
                        "Cross-identity similarity between $n1 and $n2 should be < ${FaceNetModelConfig.MOBILE_FACENET.matchThreshold} (was $sim)"
                    )
                }
            }
        }
    }

    @Test
    fun testDirectPortraitMatchingConvenience() = runTest {
        val matcher = FaceNetFaceMatcher(
            modelBytes = testModelMobileFaceNet,
            config = FaceNetModelConfig.MOBILE_FACENET
        )
        if (!matcher.isSupported) {
            println("Skipping testDirectPortraitMatchingConvenience: not supported on this platform")
            return@runTest
        }

        val q1 = FaceTestData.decodeImageByteString(FaceTestData.QUALCOMM_DEMO_1_BASE64)
        val q2 = FaceTestData.decodeImageByteString(FaceTestData.QUALCOMM_DEMO_2_BASE64)
        val similarity = matcher.matchPortraits(q1, q2)
        assertTrue(
            similarity >= FaceNetModelConfig.MOBILE_FACENET.matchThreshold,
            "Direct matchPortraits similarity ($similarity) should be >= 0.70"
        )
    }
}
