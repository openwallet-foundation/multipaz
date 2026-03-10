import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("maven-publish")
    id("org.jetbrains.dokka") version "2.1.0"
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

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        publishLibraryVariants("release")
    }

    if (!disableWebTargets) {
        js {
            outputModuleName = "multipaz-dcapi"
            browser {
            }
            binaries.executable()
        }

        wasmJs {
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
                "-Wl,-rpath,/usr/lib/swift"
            )
        }
    }

    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":multipaz"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.accompanist.permissions)
                implementation(libs.androidx.material)
                implementation(libs.play.services.identity.credentials)
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services.auth)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(project(":multipaz-doctypes"))
            }
        }

        val androidInstrumentedTest by getting {
            dependsOn(commonTest)
            dependencies {
                implementation(project(":multipaz-dcapi:matcherTest"))
                implementation(libs.androidx.espresso.core)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }
}

android {
    namespace = "org.multipaz.dcapi"
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
        implementation(libs.kotlinx.datetime)
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
            name.set("multipaz-dcapi")
            description.set("Multipaz SDK DC API module")
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
