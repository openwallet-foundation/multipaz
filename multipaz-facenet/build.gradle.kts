@file:OptIn(
    ExperimentalWasmDsl::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class
)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager
import kotlin.io.encoding.Base64

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("maven-publish")
    id("org.jetbrains.dokka") version "2.1.0"
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

val unpackTensorFlowLiteC by tasks.registering(Sync::class) {
    val archiveFile = layout.projectDirectory.file("prebuilts/TensorFlowLiteC-ios-2.17.0.tar.gz")
    from(tarTree(archiveFile))
    into(layout.buildDirectory.dir("TensorFlowLiteC"))
}

val unpackTensorFlowLiteCDesktop by tasks.registering(Sync::class) {
    val archiveFile = layout.projectDirectory.file("prebuilts/TensorFlowLiteC-desktop-2.17.1.tar.gz")
    from(tarTree(archiveFile))
    into(layout.buildDirectory.dir("generated/resources/tfliteDesktop"))
}

abstract class GenerateTestModelTask : DefaultTask() {
    @get:InputFile
    abstract val inputFile: RegularFileProperty

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
        val outFile = File(packageDir, "TestModelPayload.kt")
        outFile.bufferedWriter().use { writer ->
            writer.write("@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)\n")
            writer.write("package $pkg\n\n")
            writer.write("import kotlin.io.encoding.Base64\n")
            writer.write("import kotlinx.io.bytestring.ByteString\n\n")
            val bytes = inputFile.get().asFile.readBytes()
            val base64String = Base64.encode(bytes)
            val chunks: List<String> = base64String.chunked(30000)
            writer.write("private val chunks_mobile_facenet = arrayOf(\n")
            for (chunk in chunks) {
                writer.write("    \"$chunk\",\n")
            }
            writer.write(")\n\n")
            writer.write("internal val testModelMobileFaceNet: ByteString by lazy {\n")
            writer.write("    ByteString(Base64.decode(chunks_mobile_facenet.joinToString(\"\")))\n")
            writer.write("}\n")
        }
    }
}

val generateTestModel by tasks.registering(GenerateTestModelTask::class) {
    val modelFile = rootProject.layout.projectDirectory.file(
        "samples/testapp/src/commonMain/composeResources/files/mobile_facenet.tflite"
    )
    inputFile.set(modelFile)
    packageName.set("org.multipaz.facenet")
    outputDir.set(layout.buildDirectory.dir("generated/source/testModel/commonTest"))
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        publishLibraryVariants("release")
    }

    jvm()

    if (!disableWebTargets) {
        js {
            outputModuleName = "multipaz-facenet"
            browser {
                testTask { enabled = false }
            }
            binaries.executable()
        }

        wasmJs {
            outputModuleName = "multipaz-facenet"
            browser {
                testTask { enabled = false }
            }
            binaries.executable()
        }
    }

    val tfliteBaseDir = file("${project.layout.buildDirectory.get()}/TensorFlowLiteC")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        if (HostManager.hostIsMac) {
            target.compilations.getByName("main") {
                val TensorFlowLiteC by cinterops.creating {
                    definitionFile.set(project.file("src/iosMain/cinterop/TensorFlowLiteC.def"))
                    includeDirs.headerFilterOnly("$tfliteBaseDir/include")
                    extraOpts("-libraryPath", "$tfliteBaseDir/libs/${target.name}")
                    val interopTask = tasks[interopProcessingTaskName]
                    interopTask.dependsOn(unpackTensorFlowLiteC)
                }
            }
            target.binaries.all {
                linkerOpts(
                    "-L$tfliteBaseDir/libs/${target.name}",
                    "-Wl,-rpath,/usr/lib/swift",
                    "-lc++",
                    "-lsqlite3"
                )
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        standalone.set(false)
        device.set("booted")
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":multipaz"))
                implementation(libs.kotlinx.io.bytestring)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
            kotlin.srcDir(generateTestModel)
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.litert)
                implementation(libs.litert.gpu)
                implementation(libs.litert.support)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.camera.camera2)
            }
        }

        val jvmMain by getting {
            resources.srcDir(unpackTensorFlowLiteCDesktop)
            dependencies {
                implementation(libs.jna)
            }
        }
    }
}

android {
    namespace = "org.multipaz.facenet"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    testOptions {
        unitTests.isReturnDefaultValues = true
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
            name.set("multipaz-facenet")
            description.set("Multipaz SDK FaceNet module")
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
