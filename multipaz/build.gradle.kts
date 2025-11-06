import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.implementation
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.get
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

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
        if (HostManager.hostIsMac) {
            it.compilations.getByName("main") {
                val SwiftBridge by cinterops.creating {
                    definitionFile.set(project.file("nativeInterop/cinterop/SwiftBridge-$platform.def"))
                    includeDirs.headerFilterOnly("$rootDir/multipaz/SwiftBridge/build/Release-$platform/include")

                    val interopTask = tasks[interopProcessingTaskName]
                    val capitalizedPlatform = platform.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase()
                        else it.toString()
                    }
                    interopTask.dependsOn(":multipaz:SwiftBridge:build${capitalizedPlatform}")
                }

                it.binaries.all {
                    // Linker options required to link to the library.
                    linkerOpts(
                        "-L/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/${platform}/",
                        "-L$rootDir/multipaz/SwiftBridge/build/Release-${platform}/",
                        "-lSwiftBridge"
                    )
                }
            }
        }
    }

    // we want some extra dependsOn calls to create
    // javaSharedMain to share between JVM and Android,
    // but otherwise want to follow default hierarchy.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            dependencies {
                implementation(libs.kotlinx.io.bytestring)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                api(libs.kotlinx.io.bytestring)
                api(libs.kotlinx.io.core)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                api(libs.ktor.client.core)
            }
        }

        val commonTest by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonTest/kotlin")
            dependencies {
                implementation(libs.bouncy.castle.bcprov)
                implementation(libs.bouncy.castle.bcpkix)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(project(":multipaz-doctypes"))
            }
        }

        val javaSharedMain by creating {
            dependsOn(commonMain)
            dependencies {
            }
        }

        val jvmMain by getting {
            dependsOn(javaSharedMain)
            dependencies {
                implementation(libs.ktor.client.java)
            }
        }

        val androidMain by getting {
            dependsOn(javaSharedMain)
            dependencies {
                implementation(libs.androidx.biometrics)
                implementation(libs.androidx.lifecycle.viewmodel)
            }
        }

        val iosMain by getting {
            dependencies {
                // This dependency is needed for SqliteStorage implementation.
                // KMP-compatible version is still alpha and it is not compatible with
                // other androidx packages, particularly androidx.work that we use in wallet.
                // TODO: once compatibility issues are resolved, SqliteStorage and this
                // dependency can be moved into commonMain.
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.framework)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.hsqldb)
                implementation(libs.mysql)
                implementation(libs.postgresql)
                implementation(libs.nimbus.oauth2.oidc.sdk)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.tink)
            }
        }

        val androidInstrumentedTest by getting {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.bouncy.castle.bcprov)
                implementation(libs.bouncy.castle.bcpkix)
                implementation(project(":multipaz-doctypes"))
                implementation(project(":multipaz-dcapi"))
                implementation(project(":multipaz-dcapi:matcherTest"))
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.framework)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.androidx.test.junit)
                implementation(libs.androidx.espresso.core)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.mock)
                implementation(project(":multipaz-csa"))
            }
        }

        val iosTest by getting {
            dependencies {
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.framework)
                implementation(libs.androidx.sqlite.bundled)
            }
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":multipaz-cbor-rpc"))
    add("kspJvmTest", project(":multipaz-cbor-rpc"))
}

tasks.all {
    if (name == "compileDebugKotlinAndroid" || name == "compileReleaseKotlinAndroid" ||
        name == "androidReleaseSourcesJar" || name == "iosArm64SourcesJar" ||
        name == "iosSimulatorArm64SourcesJar" || name == "iosX64SourcesJar" ||
        name == "jvmSourcesJar" || name == "sourcesJar") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

tasks["compileKotlinIosX64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinIosArm64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinIosSimulatorArm64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinJvm"].dependsOn("kspCommonMainKotlinMetadata")

tasks.withType<Test> {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}
android {
    namespace = "org.multipaz"
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

subprojects {
	apply(plugin = "org.jetbrains.dokka")
}
