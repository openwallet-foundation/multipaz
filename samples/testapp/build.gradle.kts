@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.ksp)
    alias(libs.plugins.skie)
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

// If changing it here, it must also be changed in XCode "Signing and Capabilities", under
// "Associated Domains"
val applinkHost = "apps.multipaz.org"

buildConfig {
    packageName("org.multipaz.testapp")
    buildConfigField("TEST_APP_UPDATE_URL", System.getenv("TEST_APP_UPDATE_URL") ?: "")
    buildConfigField("TEST_APP_UPDATE_WEBSITE_URL", System.getenv("TEST_APP_UPDATE_WEBSITE_URL") ?: "")
    buildConfigField("APPLINK_HOST", applinkHost)
    useKotlinOutput { internalVisibility = false }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors = true
        }
    }

    wasmJs {
        browser {
        }
        binaries.executable()
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Multipaz"
            isStatic = true
            export(project(":multipaz"))
            export(project(":multipaz-doctypes"))
            export(project(":multipaz-longfellow"))
            export(libs.ktor.client.darwin)
            export(libs.kotlinx.io.bytestring)
            export(libs.kotlinx.datetime)
            export(libs.kotlinx.coroutines.core)
            export(libs.kotlinx.serialization.json)
            export(libs.ktor.client.core)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.framework)

                api(project(":multipaz"))
                api(project(":multipaz-doctypes"))
                api(project(":multipaz-longfellow"))
                api(libs.ktor.client.darwin)
                api(libs.kotlinx.io.bytestring)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                api(libs.ktor.client.core)
            }
        }

        val iosX64Main by getting {
            dependencies {}
        }

        val iosArm64Main by getting {
            dependencies {}
        }

        val iosSimulatorArm64Main by getting {
            dependencies {}
        }

        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.bouncy.castle.bcprov)
                implementation(libs.androidx.biometrics)
                implementation(libs.ktor.client.android)
                implementation(libs.process.phoenix)
            }
        }

        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }

        val commonMain by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)
                implementation(libs.jetbrains.navigation.compose)
                implementation(libs.jetbrains.navigation.runtime)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.network)
                implementation(libs.semver)

                implementation(project(":multipaz"))
                implementation(project(":multipaz-compose"))
                implementation(project(":multipaz-dcapi"))
                implementation(project(":multipaz-doctypes"))
                implementation(project(":multipaz-longfellow"))
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.coil.compose)
                implementation(libs.coil.ktor3)
            }
        }
    }
}

android {
    namespace = "org.multipaz.testapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    defaultConfig {
        applicationId = "org.multipaz.testapp"
        manifestPlaceholders["applinkHost"] = applinkHost
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = projectVersionCode
        versionName = projectVersionName
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += listOf("/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    flavorDimensions.addAll(listOf("standard"))
    productFlavors {
        create("blue") {
            dimension = "standard"
            isDefault = true
        }
        create("red") {
            dimension = "standard"
            applicationId = "org.multipaz.testapp.red"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    dependencies {
        debugImplementation(compose.uiTooling)
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":multipaz-cbor-rpc"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

tasks["compileKotlinIosX64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinIosArm64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinIosSimulatorArm64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinWasmJs"].dependsOn("kspCommonMainKotlinMetadata")

subprojects {
	apply(plugin = "org.jetbrains.dokka")
}
