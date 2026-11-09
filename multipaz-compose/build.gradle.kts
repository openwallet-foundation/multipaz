@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree


plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    id("maven-publish")
    id("org.jetbrains.dokka") version "2.1.0"
    id("org.multipaz.lokalize.convention")
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

    jvm()

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        unitTestVariant.sourceSetTree.set(KotlinSourceSetTree.unitTest)

        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        publishLibraryVariants("release")
    }

    if (!disableWebTargets) {
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
                "-lsqlite3"
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
                implementation(libs.jetbrains.navigationevent.compose)
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material3)
                api(compose.ui)
                api(compose.components.resources)
                api(compose.materialIconsExtended)
                api(libs.jetbrains.navigation.compose)
                api(libs.jetbrains.navigation.runtime)
                api(libs.jetbrains.navigationevent.compose)

                implementation(project(":multipaz"))
                implementation(project(":multipaz-doctypes"))
                implementation(project(":multipaz-utopia"))
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.io.core)
                implementation(libs.coil.core)
                implementation(libs.coil.compose.core)
                implementation(libs.coil.ktor3)
                implementation(libs.compottie)
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
                implementation(libs.accompanist.drawablepainter)
                implementation(libs.accompanist.permissions)
                implementation(libs.androidx.material)
                implementation(libs.androidx.biometrics)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.view)
                implementation(libs.androidx.lifecycle.extensions)
                implementation(libs.zxing.core)
                implementation(libs.ktor.client.android)
                implementation(libs.androidx.browser)
                implementation(project(":multipaz-jpeg2000"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                implementation(libs.zxing.core)
                implementation(project(":multipaz-jpeg2000"))
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(project(":multipaz-doctypes"))
                implementation(project(":multipaz-utopia"))
                implementation(libs.bouncy.castle.bcprov)
                implementation(libs.bouncy.castle.bcpkix)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
                implementation(libs.compose.junit4)
                implementation(libs.compose.test.manifest)
                implementation(libs.androidx.test.junit)
                implementation(libs.androidx.espresso.core)
            }
        }
    }
}

val copyComposeResourcesForUnitTest = tasks.register<Copy>("copyComposeResourcesForUnitTest") {
    dependsOn("prepareComposeResourcesTaskForCommonMain", "convertXmlValueResourcesForCommonMain")
    from(layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.buildDirectory.dir("generated/unitTestComposeResources/composeResources/org.multipaz.multipaz_compose.generated.resources"))
}

tasks.matching { it.name == "processDebugUnitTestJavaRes" || it.name == "processReleaseUnitTestJavaRes" }.configureEach {
    dependsOn(copyComposeResourcesForUnitTest)
}

android {
    namespace = "org.multipaz.compose"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
        }
    }

    sourceSets["test"].resources.srcDir(layout.buildDirectory.dir("generated/unitTestComposeResources"))

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty(
                    "updateGoldens",
                    project.findProperty("updateGoldens")?.toString() ?: "false"
                )
            }
        }
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
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
    publications.withType(MavenPublication::class) {
        pom {
            name.set("multipaz-compose")
            description.set("Multipaz SDK Compose module")
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

// multipaz-compose keeps its translations as Compose Resources (strings.xml), so lokalize only
// validates and AI-fills them here - it generates no Kotlin (outputFormat defaults to XML).
lokalize {
    resourcesDir.set("src/commonMain/composeResources")
}