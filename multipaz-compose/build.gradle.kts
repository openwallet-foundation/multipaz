@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.kotlin.dsl.implementation
import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    id("maven-publish")
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)

        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        publishLibraryVariants("release")
    }

    js {
        outputModuleName = "multipaz-compose"
        browser {
            // Currently disabled, see https://youtrack.jetbrains.com/issue/CMP-4906
            testTask { enabled = false }
        }
        binaries.executable()
    }

    wasmJs {
        outputModuleName = "multipaz-compose"
        browser {
            // Currently disabled, see https://youtrack.jetbrains.com/issue/CMP-4906
            testTask { enabled = false }
        }
        binaries.executable()
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        val platform = when (it.name) {
            "iosX64" -> "iphonesimulator"
            "iosArm64" -> "iphoneos"
            "iosSimulatorArm64" -> "iphonesimulator"
            else -> error("Unsupported target ${it.name}")
        }
        it.binaries.all {
            linkerOpts(
                "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/${platform}/",
            )
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.materialIconsExtended)
                implementation(libs.jetbrains.navigation.compose)
                implementation(libs.jetbrains.navigation.runtime)
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(compose.ui)
                api(compose.components.resources)
                api(compose.materialIconsExtended)
                api(libs.jetbrains.navigation.compose)
                api(libs.jetbrains.navigation.runtime)

                implementation(project(":multipaz"))
                implementation(project(":multipaz-dcapi"))
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.io.core)
                implementation(libs.coil.core)
                implementation(libs.coil.compose.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                @OptIn(ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.accompanist.permissions)
                implementation(libs.androidx.material)
                implementation(libs.androidx.biometrics)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.view)
                implementation(libs.androidx.lifecycle.extensions)
                implementation(libs.zxing.core)
                implementation(libs.play.services.identity.credentials)
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.registry.provider)
            }
        }
    }
}

android {
    namespace = "org.multipaz.compose"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    dependencies {
        debugImplementation(compose.uiTooling)
        debugImplementation(libs.androidx.ui.tooling.preview)
        androidTestImplementation(libs.compose.junit4)
        debugImplementation(libs.compose.test.manifest)
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

    lint {
        baseline = file("lint-baseline.xml")
    }
}

group = "org.multipaz"
version = projectVersionName

publishing {
    repositories {
        maven {
            url = uri("${rootProject.rootDir}/repo")
        }
    }
    publications.withType(MavenPublication::class) {
        pom {
            licenses {
                license {
                    name = "Apache 2.0"
                    url = "https://opensource.org/licenses/Apache-2.0"
                }
            }
        }
    }
}

tasks.named("generateResourceAccessorsForAndroidMain").configure { dependsOn("sourceReleaseJar") }

subprojects {
	apply(plugin = "org.jetbrains.dokka")
}
