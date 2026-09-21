@file:OptIn(
    ExperimentalWasmDsl::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class
)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import kotlin.io.encoding.Base64

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

abstract class GenerateTestDataTask : DefaultTask() {
    @get:InputDirectory
    abstract val testFacesDir: DirectoryProperty

    @get:InputFile
    abstract val modelFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @TaskAction
    fun generate() {
        val pkg = packageName.get()
        val dir = outputDir.get().asFile
        val packageDir = File(dir, pkg.replace('.', '/'))
        packageDir.mkdirs()
        val outFile = File(packageDir, "FaceTestData.kt")

        data class PortraitMeta(
            val filename: String,
            val id: String,
            val displayName: String,
            val subtitle: String,
            val constName: String
        )

        val portraits = listOf(
            PortraitMeta("qualcomm_demo_1.jpg", "qualcomm_demo_1", "Qualcomm Demo 1", "Official MobileFaceNet demo portrait (BSD-3-Clause)", "QUALCOMM_DEMO_1_BASE64"),
            PortraitMeta("qualcomm_demo_2.jpg", "qualcomm_demo_2", "Qualcomm Demo 2", "Official MobileFaceNet demo portrait (BSD-3-Clause)", "QUALCOMM_DEMO_2_BASE64"),
            PortraitMeta("warren_portrait.jpg", "warren_portrait", "Elizabeth Warren (113th)", "U.S. Congress official portrait with glasses", "WARREN_PORTRAIT_BASE64"),
            PortraitMeta("warren_portrait_114th.jpg", "warren_portrait_114th", "Elizabeth Warren (114th)", "U.S. Congress official portrait with glasses", "WARREN_PORTRAIT_114TH_BASE64"),
            PortraitMeta("erika_mustermann.jpg", "erika_mustermann", "Erika Mustermann (2010)", "Public domain sample credential portrait", "ERIKA_MUSTERMANN_BASE64"),
            PortraitMeta("erika_mustermann_2001.jpg", "erika_mustermann_2001", "Erika Mustermann (2001)", "German Bundesdruckerei sample passport portrait", "ERIKA_MUSTERMANN_2001_BASE64"),
            PortraitMeta("male_portrait.jpg", "male_portrait", "Sample Male Portrait", "Multipaz sample credential portrait", "MALE_PORTRAIT_BASE64"),
            PortraitMeta("female_portrait.jpg", "female_portrait", "Sample Female Portrait", "Multipaz sample credential portrait", "FEMALE_PORTRAIT_BASE64"),
            PortraitMeta("bob_with_glasses_1.jpg", "bob_with_glasses_1", "Bob (with glasses 1)", "Synthetic portrait with glasses", "BOB_WITH_GLASSES_1_BASE64"),
            PortraitMeta("bob_with_glasses_2.jpg", "bob_with_glasses_2", "Bob (with glasses 2)", "Synthetic portrait with glasses", "BOB_WITH_GLASSES_2_BASE64"),
            PortraitMeta("bob_without_glasses_1.jpg", "bob_without_glasses_1", "Bob (without glasses 1)", "Synthetic portrait without glasses", "BOB_WITHOUT_GLASSES_1_BASE64"),
            PortraitMeta("bob_without_glasses_2.jpg", "bob_without_glasses_2", "Bob (without glasses 2)", "Synthetic portrait without glasses", "BOB_WITHOUT_GLASSES_2_BASE64"),
            PortraitMeta("alice_with_glasses_1.jpg", "alice_with_glasses_1", "Alice (with glasses 1)", "Synthetic portrait with glasses", "ALICE_WITH_GLASSES_1_BASE64"),
            PortraitMeta("alice_with_glasses_2.jpg", "alice_with_glasses_2", "Alice (with glasses 2)", "Synthetic portrait with glasses", "ALICE_WITH_GLASSES_2_BASE64"),
            PortraitMeta("alice_without_glasses_1.jpg", "alice_without_glasses_1", "Alice (without glasses 1)", "Synthetic portrait without glasses", "ALICE_WITHOUT_GLASSES_1_BASE64"),
            PortraitMeta("alice_without_glasses_2.jpg", "alice_without_glasses_2", "Alice (without glasses 2)", "Synthetic portrait without glasses", "ALICE_WITHOUT_GLASSES_2_BASE64")
        )

        outFile.bufferedWriter().use { writer ->
            writer.write("@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)\n")
            writer.write("package $pkg\n\n")
            writer.write("import kotlinx.io.bytestring.ByteString\n")
            writer.write("import kotlin.io.encoding.Base64\n\n")

            // Model chunks
            val modelBytes = modelFile.get().asFile.readBytes()
            val modelBase64 = Base64.encode(modelBytes)
            val modelChunks = modelBase64.chunked(30000)
            writer.write("private val chunks_mobile_facenet = arrayOf(\n")
            modelChunks.forEach { chunk ->
                writer.write("    \"$chunk\",\n")
            }
            writer.write(")\n\n")

            // Portrait chunks
            val facesDir = testFacesDir.get().asFile
            portraits.forEach { meta ->
                val file = File(facesDir, meta.filename)
                val bytes = file.readBytes()
                val b64 = Base64.encode(bytes)
                val chunks = b64.chunked(30000)
                writer.write("private val chunks_${meta.id} = arrayOf(\n")
                chunks.forEach { chunk ->
                    writer.write("    \"$chunk\",\n")
                }
                writer.write(")\n\n")
            }

            // FaceSamplePortrait data class
            writer.write("/**\n")
            writer.write(" * A sample portrait image with metadata for biometric face matching tests and picker UI.\n")
            writer.write(" */\n")
            writer.write("data class FaceSamplePortrait(\n")
            writer.write("    val id: String,\n")
            writer.write("    val displayName: String,\n")
            writer.write("    val subtitle: String,\n")
            writer.write("    val data: ByteString\n")
            writer.write(")\n\n")

            // FaceTestData object
            writer.write("/**\n")
            writer.write(" * Curated face test data, MobileFaceNet model, and golden regression vectors.\n")
            writer.write(" */\n")
            writer.write("object FaceTestData {\n\n")

            writer.write("    /** The MobileFaceNet TFLite model bytes. */\n")
            writer.write("    val testModel: ByteString by lazy {\n")
            writer.write("        ByteString(Base64.decode(chunks_mobile_facenet.joinToString(\"\")))\n")
            writer.write("    }\n\n")
            writer.write("    val testModelMobileFaceNet: ByteString get() = testModel\n\n")

            // Base64 strings for backward compatibility
            portraits.forEach { meta ->
                writer.write("    val ${meta.constName}: String by lazy {\n")
                writer.write("        chunks_${meta.id}.joinToString(\"\")\n")
                writer.write("    }\n\n")
            }

            // allPortraits
            writer.write("    val allPortraits: List<FaceSamplePortrait> by lazy {\n")
            writer.write("        listOf(\n")
            portraits.forEach { meta ->
                writer.write("            FaceSamplePortrait(\n")
                writer.write("                id = \"${meta.id}\",\n")
                writer.write("                displayName = \"${meta.displayName}\",\n")
                writer.write("                subtitle = \"${meta.subtitle}\",\n")
                writer.write("                data = ByteString(Base64.decode(chunks_${meta.id}.joinToString(\"\")))\n")
                writer.write("            ),\n")
            }
            writer.write("        )\n")
            writer.write("    }\n\n")

            writer.write("    fun decodeImageBytes(base64: String): ByteArray {\n")
            writer.write("        return Base64.decode(base64)\n")
            writer.write("    }\n\n")

            writer.write("    fun decodeImageByteString(base64: String): ByteString {\n")
            writer.write("        return ByteString(decodeImageBytes(base64))\n")
            writer.write("    }\n\n")

            // Golden regression vectors
            writer.write("    val QUALCOMM_DEMO_1_GOLDEN_EMBEDDING = floatArrayOf(\n")
            writer.write("        -0.05532378f, -0.06160173f, -0.032678265f, 0.026377603f, -0.019166624f, 0.10279686f, -0.09865529f, 0.082278155f,\n")
            writer.write("        0.00043511947f, -0.008925752f, -0.063125886f, 0.019382782f, -0.10568824f, -0.074806854f, 0.23690003f, 0.08148631f,\n")
            writer.write("        0.0941621f, 0.06361639f, -0.07212276f, -0.030216504f, -0.06298761f, 0.017642003f, -0.037462175f, -0.109954536f,\n")
            writer.write("        0.08063027f, -0.17740743f, 0.019606376f, 0.13307618f, -0.09542614f, -0.15970205f, 0.06931143f, -0.09765352f,\n")
            writer.write("        -0.07906154f, 0.045515575f, -0.08477068f, -0.14145441f, -0.05462566f, -0.07885392f, -0.05604504f, 0.05916162f,\n")
            writer.write("        -0.04717342f, -0.022479635f, 0.013329531f, -0.08684424f, -0.016117608f, -0.034731526f, 0.085430704f, 0.084005795f,\n")
            writer.write("        -0.09711468f, 0.094900556f, -0.061875958f, 0.09979279f, 0.042361874f, 0.12262272f, 0.07173301f, -0.021439927f,\n")
            writer.write("        0.04494016f, 0.045757663f, 0.055007763f, -0.07857099f, -0.09312934f, -0.06540117f, 0.008939923f, 0.09289818f,\n")
            writer.write("        0.022828406f, -0.021581596f, 0.06267055f, -0.08799447f, -0.045496233f, 0.16235761f, 0.03202874f, -0.046116125f,\n")
            writer.write("        0.04218767f, 0.021385198f, 0.042033307f, 0.020745946f, 0.11494985f, -0.061169926f, 0.14282067f, 0.09411576f,\n")
            writer.write("        0.06084983f, 0.21661723f, -0.16012818f, 0.07667272f, 0.055945445f, 0.2650901f, -0.020270627f, -0.02483247f,\n")
            writer.write("        0.050608937f, -0.13082543f, 0.15037178f, 0.052440435f, -0.065869816f, -0.028216628f, 0.036753442f, 0.045253843f,\n")
            writer.write("        0.0040590228f, -0.026535094f, -0.07354533f, -0.1067349f, -0.13405879f, -0.16423424f, 0.050660614f, -0.059698734f,\n")
            writer.write("        0.056394897f, -0.11414003f, -0.14918216f, -0.027309082f, 0.22870363f, 0.028270135f, -0.09761555f, -0.00641689f,\n")
            writer.write("        0.031720612f, -0.16825561f, 0.011030773f, 0.08284895f, -0.07043771f, -0.10417712f, 0.09470131f, -0.07348371f,\n")
            writer.write("        -0.052128218f, -0.041955903f, -0.12729585f, -0.0066677826f, -0.08048822f, -0.023977408f, 0.02063333f, 0.026193334f\n")
            writer.write("    )\n\n")

            writer.write("    val QUALCOMM_DEMO_2_GOLDEN_EMBEDDING = floatArrayOf(\n")
            writer.write("        0.073343724f, 0.007141319f, -0.025067277f, 0.029811835f, 0.03563456f, 0.12413345f, -0.06541919f, -0.0034371263f,\n")
            writer.write("        0.0028184387f, 0.06719343f, -0.042053826f, -0.16077098f, -0.21862958f, 0.06996234f, 0.22681758f, 0.07157356f,\n")
            writer.write("        0.11680958f, 0.091434695f, 0.008783353f, -0.01854404f, -0.06362094f, 0.05732272f, -0.023664113f, -0.08208485f,\n")
            writer.write("        0.113596365f, -0.123055026f, 0.0017174585f, 0.0044009537f, -0.1341048f, -0.16173826f, 0.0576801f, 0.006467964f,\n")
            writer.write("        -0.042607877f, 0.08214686f, -0.02913674f, -0.034575853f, -0.022708803f, 0.010353247f, -0.07521172f, 0.043058947f,\n")
            writer.write("        -0.027227918f, -0.04156298f, -0.05669538f, -0.050820284f, 0.055433217f, -0.046529524f, 0.07636427f, 0.106855445f,\n")
            writer.write("        -0.037471134f, 0.04001277f, -0.029710742f, 0.12425125f, -0.03842864f, 0.056956902f, 0.10657242f, -0.0306481f,\n")
            writer.write("        0.04877357f, -0.0012986342f, -0.032348853f, -0.03289629f, -0.098179325f, -0.06624075f, 0.04782621f, 0.11351535f,\n")
            writer.write("        0.0130393375f, -0.0927568f, -0.029743072f, -0.07285591f, -0.04909968f, 0.1691911f, 0.023600131f, -0.066732034f,\n")
            writer.write("        -0.043312527f, 0.0966463f, 0.05781824f, -0.016902123f, 0.09011056f, -0.080446f, 0.16960195f, 0.104368046f,\n")
            writer.write("        0.025564266f, 0.17639625f, -0.098067544f, 0.071109526f, -0.046042707f, 0.20554239f, 0.08593675f, -0.0539929f,\n")
            writer.write("        0.025720937f, -0.107732855f, 0.102963805f, 0.013453695f, -0.11281611f, -0.10641085f, -0.0032595543f, 0.03683595f,\n")
            writer.write("        0.017527714f, 0.020039698f, -0.087545894f, 0.026206661f, -0.04110829f, -0.22289161f, 0.036748078f, -0.098605715f,\n")
            writer.write("        -0.028809968f, -0.13700391f, -0.054127727f, -0.025931828f, 0.28237867f, 0.031177497f, -0.1705651f, -0.024994861f,\n")
            writer.write("        -0.03519294f, -0.15307282f, -0.03165523f, 0.23247293f, -0.08523299f, -0.11815091f, 0.07098998f, -0.07969358f,\n")
            writer.write("        0.015877927f, -0.07490814f, 0.016103495f, 0.03245321f, -0.04786636f, -0.030186135f, 0.030132107f, 0.007545255f\n")
            writer.write("    )\n\n")

            writer.write("    const val QUALCOMM_DEMO_PAIR_DIRECT_COSINE = 0.78950876f\n")
            writer.write("}\n")
        }
    }
}

val generateTestData by tasks.registering(GenerateTestDataTask::class) {
    testFacesDir.set(layout.projectDirectory.dir("testfaces"))
    modelFile.set(layout.projectDirectory.file("model/mobile_facenet.tflite"))
    packageName.set("org.multipaz.facenet.testdata")
    outputDir.set(layout.buildDirectory.dir("generated/source/testdata/main"))
}

kotlin {
    jvmToolchain(17)

    androidTarget()

    jvm()

    if (!disableWebTargets) {
        js {
            browser {
                testTask { enabled = false }
            }
        }

        wasmJs {
            browser {
                testTask { enabled = false }
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    )

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.io.bytestring)
            }
            kotlin.srcDir(generateTestData)
        }
    }
}

android {
    namespace = "org.multipaz.facenet.testdata"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
