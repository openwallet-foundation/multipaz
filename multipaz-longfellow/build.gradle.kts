@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
    id("maven-publish")
    id("org.jetbrains.dokka") version "2.1.0"
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

abstract class GeneratePayloadsTask : DefaultTask() {
    @get:InputFiles
    abstract val inputFiles: ConfigurableFileCollection

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
        val outFile = File(packageDir, "Payloads.kt")
        outFile.bufferedWriter().use { writer ->
            writer.write("@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)\n")
            writer.write("package $pkg\n\n")
            writer.write("import kotlin.io.encoding.Base64\n\n")
            inputFiles.forEach { file ->
                val bytes = file.readBytes()
                val base64String = Base64.getEncoder().encodeToString(bytes)
                // Chunk safely below the JVM 65k string literal limit
                val chunks = base64String.chunked(30000)
                // Sanitize the filename to use as a Kotlin variable name
                val safeName = "chunks_" + file.name.replace("[^a-zA-Z0-9_]".toRegex(), "_")
                writer.write("private val $safeName = arrayOf(\n")
                chunks.forEach { chunk ->
                    writer.write("    \"$chunk\",\n")
                }
                writer.write(")\n\n")
            }
            writer.write("internal val payloads: Map<String, ByteArray> by lazy {\n")
            writer.write("    mapOf(\n")
            inputFiles.forEach { file ->
                val safeName = "chunks_" + file.name.replace("[^a-zA-Z0-9_]".toRegex(), "_")
                writer.write("        \"${file.name}\" to Base64.decode($safeName.joinToString(\"\")),\n")
            }
            writer.write("    )\n")
            writer.write("}\n")
        }
    }
}

val generatePayloads by tasks.registering(GeneratePayloadsTask::class) {
    val files = listOf(
        "6_1_4096_2945_137e5a75ce72735a37c8a72da1a8a0a5df8d13365c2ae3d2c2bd6a0e7197c7c6",
        "6_2_4025_2945_b4bb6f01b7043f4f51d8302a30b36e3d4d2d0efc3c24557ab9212ad524a9764e",
        "6_3_4121_2945_b2211223b954b34a1081e3fbf71b8ea2de28efc888b4be510f532d6ba76c2010",
        "6_4_4283_2945_c70b5f44a1365c53847eb8948ad5b4fdc224251a2bc02d958c84c862823c49d6",
        "7_1_4151_4096_8d079211715200ff06c5109639245502bfe94aa869908d31176aae4016182121",
        "7_2_4265_4096_6a5810683e62b6d7766ebd0d7ca72518a2b8325418142adcadb10d51dbbcd5ad",
        "7_3_4307_4096_8ee4849ae1293ae6fe5f9082ce3e5e15c4f198f2998c682fa1b727237d6d252f",
        "7_4_4415_4096_5aebdaaafe17296a3ef3ca6c80c6e7505e09291897c39700410a365fb278e460",
        )
    val baseDir = layout.projectDirectory.dir("src/commonMain/circuits")
    inputFiles.from(files.map { baseDir.file(it) })
    packageName.set("org.multipaz.mdoc.zkp.longfellow")
    outputDir.set(layout.buildDirectory.dir("generated/source/payloads/main"))
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        publishLibraryVariants("release")
    }

    if (!disableWebTargets) {
        js {
            outputModuleName = "multipaz-longfellow"
            browser {
                // Longfellow is currently not implemented for this target
                testTask { enabled = false }
            }
            binaries.executable()
        }

        wasmJs {
            outputModuleName = "multipaz-longfellow"
            browser {
                // Longfellow is currently not implemented for this target
                testTask { enabled = false }
            }
            binaries.executable()
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "longfellow"
        }
        val zkLibExt = when (it.name) {
            "iosX64" -> "x86_64-iphonesimulator"
            "iosArm64" -> "arm64-iphoneos"
            "iosSimulatorArm64" -> "arm64-iphonesimulator"
            else -> error("Unsupported target ${it.name}")
        }
        it.compilations.getByName("main") {
            val Longfellow by cinterops.creating {
                definitionFile.set(project.file("$rootDir/multipaz-longfellow/src/iosMain/MdocZk.def"))
                includeDirs.headerFilterOnly("$rootDir/multipaz-longfellow/src/iosMain/nativelibs/$zkLibExt/include")
                extraOpts("-libraryPath", "$rootDir/multipaz-longfellow/src/iosMain/nativelibs/$zkLibExt/lib")
                packageName = "Longfellow"
            }
        }
        it.binaries.all {
            linkerOpts(
                "-L$rootDir/multipaz-longfellow/src/iosMain/nativeLibs/$zkLibExt/lib",
                "-Wl,-rpath,/usr/lib/swift",
                "-lsqlite3",
                "-lmdoc_static"
            )
        }
    }

    // we want some extra dependsOn calls to create
    // javaSharedMain to share between JVM and Android,
    // but otherwise want to follow default hierarchy.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.io.bytestring)
                implementation(libs.kotlinx.datetime)
                implementation(project(":multipaz"))
            }
            kotlin.srcDir(generatePayloads)
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val javaSharedMain by creating {
            dependsOn(commonMain)
        }

        val jvmMain by getting {
            dependsOn(javaSharedMain)
        }

        val androidMain by getting {
            dependsOn(javaSharedMain)
        }
    }
}

android {
    namespace = "org.multipaz.mdoc.zkp.longfellow"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}")
            excludes += listOf("/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

group = "org.multipaz"
version = projectVersionName

publishing {
    repositories {
        maven {
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
    publications.withType(MavenPublication::class) {
        pom {
            name.set("multipaz-longfellow")
            description.set("Multipaz SDK Longfellow module")
            url.set("https://github.com/openwallet-foundation/multipaz")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://opensource.org/licenses/Apache-2.0")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("zeuthen")
                    name.set("David Zeuthen")
                    email.set("zeuthen@google.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/openwallet-foundation/multipaz.git")
                developerConnection.set("scm:git:ssh://github.com/openwallet-foundation/multipaz.git")
                url.set("https://github.com/openwallet-foundation/multipaz")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
    }
    if (name == "jvmTest") {
        // Set the path to your native libraries
        val nativeLibPath = project.file("src/jvmMain/resources/nativeLibs").absolutePath

        // Update the JVM's library path for jvmTest
        jvmArgs = listOf("-Djava.library.path=$nativeLibPath")

        // Ensure native libraries are included in the runtime classpath
        systemProperty("java.library.path", nativeLibPath)
    }
}

// Disable unit tests for Android (running on the host JVM)
project.tasks.configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        enabled = false
    }
}