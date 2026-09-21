package org.multipaz.facenet

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.bytestring.ByteString
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosFaceDetectorTest {

    private fun loadModelData(): NSData? {
        val rootDir = NSProcessInfo.processInfo.environment["MULTIPAZ_ROOT_DIR"] as? String
        val hostHome = NSProcessInfo.processInfo.environment["SIMULATOR_HOST_HOME"] as? String
        val candidates = listOfNotNull(
            rootDir?.let { "$it/samples/testapp/src/commonMain/composeResources/files/mobile_facenet.tflite" },
            "samples/testapp/src/commonMain/composeResources/files/mobile_facenet.tflite",
            "../samples/testapp/src/commonMain/composeResources/files/mobile_facenet.tflite",
            "../../samples/testapp/src/commonMain/composeResources/files/mobile_facenet.tflite",
            "../../../samples/testapp/src/commonMain/composeResources/files/mobile_facenet.tflite",
            hostHome?.let { "$it/StudioProjects/multipaz/samples/testapp/src/commonMain/composeResources/files/mobile_facenet.tflite" }
        )
        for (candidate in candidates) {
            val data = NSData.dataWithContentsOfFile(candidate)
            if (data != null && data.length.toInt() > 0) {
                return data
            }
        }
        return null
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    @Test
    fun testFaceDetectionAndPairwiseSimilarity() {
        val modelData = loadModelData()
        assertNotNull(modelData, "mobile_facenet.tflite model file could not be located")

        val modelBytes = ByteArray(modelData.length.toInt())
        modelBytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), modelData.bytes, modelData.length)
        }
        val interpreter = IosFaceNetInterpreter(ByteString(modelBytes), FaceNetModelConfig.MOBILE_FACENET)
        val detector = IosFaceDetector()

        // 1. Direct Resize Regression against Golden Vectors
        val q1Bytes = FaceTestData.decodeImageBytes(FaceTestData.QUALCOMM_DEMO_1_BASE64)
        val q2Bytes = FaceTestData.decodeImageBytes(FaceTestData.QUALCOMM_DEMO_2_BASE64)

        val q1Direct = detector.resizeImageDirect(q1Bytes, 112)
        val q2Direct = detector.resizeImageDirect(q2Bytes, 112)
        assertNotNull(q1Direct)
        assertNotNull(q2Direct)

        val q1Emb = interpreter.getEmbedding(q1Direct)
        val q2Emb = interpreter.getEmbedding(q2Direct)
        assertNotNull(q1Emb)
        assertNotNull(q2Emb)

        val simDirect = q1Emb.calculateSimilarity(q2Emb)
        assertEquals(FaceTestData.QUALCOMM_DEMO_PAIR_DIRECT_COSINE, simDirect, 0.005f)

        // 2. Detection, Alignment, and Embeddings across Curated Test Corpus
        val corpus = mapOf(
            "qualcomm_1" to (FaceTestData.decodeImageBytes(FaceTestData.QUALCOMM_DEMO_1_BASE64) to "qualcomm"),
            "qualcomm_2" to (FaceTestData.decodeImageBytes(FaceTestData.QUALCOMM_DEMO_2_BASE64) to "qualcomm"),
            "warren" to (FaceTestData.decodeImageBytes(FaceTestData.WARREN_PORTRAIT_BASE64) to "warren"),
            "warren_114th" to (FaceTestData.decodeImageBytes(FaceTestData.WARREN_PORTRAIT_114TH_BASE64) to "warren"),
            "erika_2010" to (FaceTestData.decodeImageBytes(FaceTestData.ERIKA_MUSTERMANN_BASE64) to "erika_2010"),
            "erika_2001" to (FaceTestData.decodeImageBytes(FaceTestData.ERIKA_MUSTERMANN_2001_BASE64) to "erika_2001"),
            "male" to (FaceTestData.decodeImageBytes(FaceTestData.MALE_PORTRAIT_BASE64) to "male"),
            "female" to (FaceTestData.decodeImageBytes(FaceTestData.FEMALE_PORTRAIT_BASE64) to "female"),
            "bob_with_glasses_1" to (FaceTestData.decodeImageBytes(FaceTestData.BOB_WITH_GLASSES_1_BASE64) to "bob"),
            "bob_with_glasses_2" to (FaceTestData.decodeImageBytes(FaceTestData.BOB_WITH_GLASSES_2_BASE64) to "bob"),
            "bob_without_glasses_1" to (FaceTestData.decodeImageBytes(FaceTestData.BOB_WITHOUT_GLASSES_1_BASE64) to "bob"),
            "bob_without_glasses_2" to (FaceTestData.decodeImageBytes(FaceTestData.BOB_WITHOUT_GLASSES_2_BASE64) to "bob"),
        )

        val embeddings = mutableMapOf<String, FaceEmbedding>()
        val identities = mutableMapOf<String, String>()

        for ((name, pair) in corpus) {
            val (bytes, identity) = pair
            println("CORPUS_CHECK: $name, bytes.size=${bytes.size}")
            identities[name] = identity

            val faces = detector.detectFaces(bytes)
            assertTrue(faces.isNotEmpty(), "Expected at least 1 face detected in $name")

            val face = faces[0]
            println("$name: bbox=${face.boundingBox}, leftEye=${face.leftEye}, rightEye=${face.rightEye}")
            assertNotNull(face.leftEye, "Left eye landmark missing for $name")
            assertNotNull(face.rightEye, "Right eye landmark missing for $name")

            val crop = detector.extractFaceCrop(bytes, face, 112)
            assertNotNull(crop, "Failed to extract aligned face crop for $name")
            assertEquals(112 * 112 * 3, crop.size)

            val emb = interpreter.getEmbedding(crop)
            assertNotNull(emb, "Failed to compute embedding for $name")
            assertEquals(128, emb.embedding.size)

            embeddings[name] = emb
        }

        val names = embeddings.keys.toList()

        // 3. Same Identity Test (Qualcomm official demo pair: two different photos of the same individual)
        val q1EmbDet = embeddings["qualcomm_1"]!!
        val q2EmbDet = embeddings["qualcomm_2"]!!
        val simQualcomm = q1EmbDet.calculateSimilarity(q2EmbDet)
        println("Qualcomm demo pair ALIGNED similarity: $simQualcomm")
        assertTrue(
            simQualcomm >= FaceNetModelConfig.MOBILE_FACENET.matchThreshold,
            "Qualcomm same-identity pair similarity ($simQualcomm) must be >= threshold (${FaceNetModelConfig.MOBILE_FACENET.matchThreshold})"
        )

        // 4. Same Identity Test (Elizabeth Warren 113th vs 114th Congress)
        val warrenBytes = FaceTestData.decodeImageBytes(FaceTestData.WARREN_PORTRAIT_BASE64)
        val warren114thBytes = FaceTestData.decodeImageBytes(FaceTestData.WARREN_PORTRAIT_114TH_BASE64)
        val w1Direct = detector.resizeImageDirect(warrenBytes, 112)!!
        val w2Direct = detector.resizeImageDirect(warren114thBytes, 112)!!
        val w1DirectEmb = interpreter.getEmbedding(w1Direct)!!
        val w2DirectEmb = interpreter.getEmbedding(w2Direct)!!
        val simWarrenDirect = w1DirectEmb.calculateSimilarity(w2DirectEmb)
        println("Warren 113th vs 114th DIRECT RESIZE similarity: $simWarrenDirect")

        val warrenEmb = embeddings["warren"]!!
        val warren114thEmb = embeddings["warren_114th"]!!
        val simWarren = warrenEmb.calculateSimilarity(warren114thEmb)
        println("Warren 113th vs 114th ALIGNED similarity: $simWarren")
        assertTrue(
            simWarren >= 0.50f,
            "Warren 113th vs 114th cross-session similarity ($simWarren) must be >= 0.50"
        )

        // 5. Test Erika Mustermann 2001 vs 2010:
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

        // 6. Same Identity Test for Bob (Synthetic Portraits with and without glasses)
        val bobG1Emb = embeddings["bob_with_glasses_1"]!!
        val bobG2Emb = embeddings["bob_with_glasses_2"]!!
        val bobNoG1Emb = embeddings["bob_without_glasses_1"]!!
        val bobNoG2Emb = embeddings["bob_without_glasses_2"]!!

        val simBobGlasses = bobG1Emb.calculateSimilarity(bobG2Emb)
        println("Bob with glasses 1 vs 2 similarity: $simBobGlasses")

        val simBobNoGlasses = bobNoG1Emb.calculateSimilarity(bobNoG2Emb)
        println("Bob without glasses 1 vs 2 similarity: $simBobNoGlasses")

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

        // 7. Cross-Identity Matrix (All different identities must be below 0.65 threshold)
        println("\n=== Pairwise Similarity Matrix ===")
        for (i in names.indices) {
            for (j in i + 1 until names.size) {
                val n1 = names[i]
                val n2 = names[j]
                val sim = embeddings[n1]!!.calculateSimilarity(embeddings[n2]!!)
                println("$n1 vs $n2 : $sim")
                if (identities[n1] != identities[n2]) {
                    assertTrue(
                        sim < 0.65f,
                        "Cross-identity similarity between $n1 and $n2 should be < 0.65 (was $sim)"
                    )
                }
            }
        }

        assertTrue(
            simBobGlasses >= 0.60f,
            "Bob with glasses 1 vs 2 similarity ($simBobGlasses) must be >= 0.60"
        )
        assertTrue(
            simBobNoGlasses >= 0.60f,
            "Bob without glasses 1 vs 2 similarity ($simBobNoGlasses) must be >= 0.60"
        )
    }
}
