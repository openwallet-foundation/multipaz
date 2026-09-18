@file:OptIn(ExperimentalWasmDsl::class)

import java.net.URI
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("maven-publish")
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

val prepareTensorFlowLiteC by tasks.registering {
    val xcframeworkDir = file("${project.layout.buildDirectory.get()}/TensorFlowLiteC/TensorFlowLiteC.xcframework")
    outputs.dir(xcframeworkDir)
    doLast {
        val infoPlist = file("$xcframeworkDir/Info.plist")
        if (!infoPlist.exists()) {
            val userHome = System.getProperty("user.home")
            val localCachePaths = listOf(
                file("$userHome/Library/Caches/CocoaPods/Pods/Release/TensorFlowLiteC/2.17.0-eac6d/Frameworks/TensorFlowLiteC.xcframework"),
                file("$rootDir/../multipaz-extras/multipaz-vision/build/cocoapods/synthetic/ios/Pods/TensorFlowLiteC/Frameworks/TensorFlowLiteC.xcframework"),
                file("$userHome/.gradle/caches/multipaz/TensorFlowLiteC.xcframework")
            )
            val foundCache = localCachePaths.firstOrNull { file("$it/Info.plist").exists() }
            if (foundCache != null) {
                logger.lifecycle("Using cached TensorFlowLiteC.xcframework from $foundCache")
                project.copy {
                    from(foundCache)
                    into(xcframeworkDir)
                }
            } else {
                val globalCacheDir = file("$userHome/.gradle/caches/multipaz")
                val globalXcframework = file("$globalCacheDir/TensorFlowLiteC.xcframework")
                if (!file("$globalXcframework/Info.plist").exists()) {
                    globalCacheDir.mkdirs()
                    val tarGzFile = file("$globalCacheDir/TensorFlowLiteC-2.17.0.tar.gz")
                    val url = "https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/32/20240729-115310/TensorFlowLiteC/2.17.0/0c10b3543e01f547/TensorFlowLiteC-2.17.0.tar.gz"
                    logger.lifecycle("Downloading TensorFlowLiteC from $url...")
                    URI.create(url).toURL().openStream().use { input: java.io.InputStream ->
                        tarGzFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    logger.lifecycle("Extracting TensorFlowLiteC...")
                    project.exec {
                        commandLine("tar", "-xzf", tarGzFile.absolutePath, "-C", globalCacheDir.absolutePath)
                    }
                    val extracted = file("$globalCacheDir/Frameworks/TensorFlowLiteC.xcframework")
                    if (extracted.exists()) {
                        extracted.renameTo(globalXcframework)
                    }
                }
                project.copy {
                    from(globalXcframework)
                    into(xcframeworkDir)
                }
            }

            val tfliteLibsDir = file("${project.layout.buildDirectory.get()}/TensorFlowLiteC/libs")
            val iosArm64Lib = file("$tfliteLibsDir/iosArm64/libTensorFlowLiteC.a")
            val iosSimArm64Lib = file("$tfliteLibsDir/iosSimulatorArm64/libTensorFlowLiteC.a")
            val iosX64Lib = file("$tfliteLibsDir/iosX64/libTensorFlowLiteC.a")

            if (!iosArm64Lib.exists()) {
                file("$tfliteLibsDir/iosArm64").mkdirs()
                project.exec {
                    commandLine("ar", "rcs", iosArm64Lib.absolutePath, "$xcframeworkDir/ios-arm64/TensorFlowLiteC.framework/TensorFlowLiteC")
                }
            }

            val simBinary = "$xcframeworkDir/ios-arm64_x86_64-simulator/TensorFlowLiteC.framework/TensorFlowLiteC"
            if (!iosSimArm64Lib.exists()) {
                val simArm64Dir = file("$tfliteLibsDir/iosSimulatorArm64")
                simArm64Dir.mkdirs()
                val tempObj = file("$simArm64Dir/tflite.o")
                project.exec {
                    commandLine("lipo", "-thin", "arm64", simBinary, "-output", tempObj.absolutePath)
                }
                project.exec {
                    commandLine("ar", "rcs", iosSimArm64Lib.absolutePath, tempObj.absolutePath)
                }
                tempObj.delete()
            }

            if (!iosX64Lib.exists()) {
                val x64Dir = file("$tfliteLibsDir/iosX64")
                x64Dir.mkdirs()
                val tempObj = file("$x64Dir/tflite.o")
                project.exec {
                    commandLine("lipo", "-thin", "x86_64", simBinary, "-output", tempObj.absolutePath)
                }
                project.exec {
                    commandLine("ar", "rcs", iosX64Lib.absolutePath, tempObj.absolutePath)
                }
                tempObj.delete()
            }
        }
    }
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

    val tfliteBaseDir = file("${project.layout.buildDirectory.get()}/TensorFlowLiteC/TensorFlowLiteC.xcframework")
    val tfliteLibsDir = file("${project.layout.buildDirectory.get()}/TensorFlowLiteC/libs")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        val platform = when (target.name) {
            "iosX64" -> "iphonesimulator"
            "iosArm64" -> "iphoneos"
            "iosSimulatorArm64" -> "iphonesimulator"
            else -> error("Unsupported target ${target.name}")
        }
        val tfliteSlice = when (target.name) {
            "iosArm64" -> "ios-arm64"
            "iosSimulatorArm64", "iosX64" -> "ios-arm64_x86_64-simulator"
            else -> error("Unsupported target ${target.name}")
        }
        if (HostManager.hostIsMac) {
            target.compilations.getByName("main") {
                val TensorFlowLiteC by cinterops.creating {
                    definitionFile.set(project.file("src/iosMain/cinterop/TensorFlowLiteC.def"))
                    includeDirs.headerFilterOnly("$tfliteBaseDir/$tfliteSlice/TensorFlowLiteC.framework/Headers")
                    extraOpts("-libraryPath", "$tfliteLibsDir/${target.name}")
                    val interopTask = tasks[interopProcessingTaskName]
                    interopTask.dependsOn(prepareTensorFlowLiteC)
                }
            }
        }
        target.binaries.all {
            linkerOpts(
                "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/${platform}/",
                "-Wl,-rpath,/usr/lib/swift",
                "-L$tfliteLibsDir/${target.name}",
                "-lc++",
                "-lsqlite3"
            )
        }
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
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.face.detection)
                implementation(libs.litert)
                implementation(libs.litert.gpu)
                implementation(libs.litert.support)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.camera.camera2)
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
}
