@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.ksp)
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

    jvm()

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        publishLibraryVariants("release")
    }

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

subprojects {
	apply(plugin = "org.jetbrains.dokka")
}
