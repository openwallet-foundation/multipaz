@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("maven-publish")
    id("org.jetbrains.dokka") version "2.1.0"
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra
val xcodeDeveloperPath: String by rootProject.extra

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

kotlin {
    jvmToolchain(21)

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
            outputModuleName = "multipaz-bthci"
            browser {
            }
            binaries.executable()
        }

        wasmJs {
            outputModuleName = "multipaz-bthci"
            browser {
            }
            binaries.executable()
        }
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
                "-L${xcodeDeveloperPath}/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/${platform}/",
                "-Wl,-rpath,/usr/lib/swift",
                "-lsqlite3",
            )
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":multipaz"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.bytestring)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.datetime)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":multipaz-doctypes"))
                implementation(project(":multipaz-utopia"))
            }
        }

        val jvmMain by getting {
            dependencies {
                compileOnly(libs.jna)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.jna)
                implementation(libs.bouncy.castle.bcprov)
                implementation(libs.bouncy.castle.bcpkix)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.bouncy.castle.bcprov)
                implementation(libs.bouncy.castle.bcpkix)
            }
        }
    }
}

tasks.withType<Test> {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}

group = "org.multipaz"
version = projectVersionName

android {
    namespace = "org.multipaz.bthci"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    repositories {
        maven {
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
    publications.withType(MavenPublication::class) {
        pom {
            name.set("multipaz-bthci")
            description.set("Multipaz SDK Bluetooth Host Controller Interface (HCI) module")
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
