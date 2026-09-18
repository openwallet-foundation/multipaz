@file:OptIn(ExperimentalWasmDsl::class)

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

val unpackTensorFlowLiteC by tasks.registering(Sync::class) {
    val archiveFile = layout.projectDirectory.file("prebuilts/TensorFlowLiteC-ios-2.17.0.tar.gz")
    from(tarTree(archiveFile))
    into(layout.buildDirectory.dir("TensorFlowLiteC"))
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
