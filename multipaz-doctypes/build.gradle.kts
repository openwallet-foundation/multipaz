@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.multipaz.lokalize.util.OutputFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("maven-publish")
    id("org.jetbrains.dokka") version "2.1.0"
    id("org.multipaz.lokalize.convention")
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget()

    jvm()

    if (!disableWebTargets) {
        js {
            outputModuleName = "multipaz-doctypes"
            browser {
            }
            binaries.executable()
        }

        wasmJs {
            outputModuleName = "multipaz-doctypes"
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
                "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/${platform}/",
                "-Wl,-rpath,/usr/lib/swift",
                "-lsqlite3"
            )
        }
    }

    // Apply default hierarchy template to automatically create webMain source set
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":multipaz"))
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.bytestring)
                implementation(libs.kotlinx.serialization.json)
                api(project(":multipaz"))
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.tink)
            }
        }

        val jvmTest by getting {
            dependencies {
            }
        }

        if (!disableWebTargets) {
            val webMain by getting {
                dependencies {
                    implementation(libs.kotlinx.browser)
                }
            }

            val jsTest by getting {
                dependencies {
                    implementation(libs.kotlin.wrappers.web)
                }
            }
        }
    }
}

group = "org.multipaz"

android {
    namespace = "org.multipaz.doctypes"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

version = projectVersionName

publishing {
    repositories {
        maven {
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
    publications.withType(MavenPublication::class) {
        pom {
            name.set("multipaz-doctypes")
            description.set("Multipaz SDK doctypes module")
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

// Source strings live under src/commonMain/lokalize/ rather than src/commonMain/resources/
// because KMP would auto-bundle the latter into the JVM JAR as Java resources at the
// root (values-*/strings.json), which collides with any sibling module that does the
// same — most notably multipaz-utopia — during Android's mergeJavaResource step in
// downstream consumers (issue #1714). Nothing reads these JSONs at runtime: they're
// build-time inputs to the lokalize plugin only; runtime translations are baked into
// GeneratedTranslations as Kotlin constants.
lokalize {
    outputFormat.set(OutputFormat.JSON)
    resourcesDir.set("src/commonMain/lokalize")
}

