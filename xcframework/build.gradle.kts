import co.touchlab.skie.configuration.FlowInterop
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.skie)
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

kotlin {
    val xcFrameworkName = "Multipaz"
    val xcf = XCFramework(xcFrameworkName)
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            export(project(":multipaz"))
            export(project(":multipaz-dcapi"))
            export(project(":multipaz-doctypes"))
            export(project(":multipaz-longfellow"))
            export(libs.kotlinx.io.bytestring)
            export(libs.kotlinx.io.core)
            export(libs.kotlinx.datetime)
            export(libs.kotlinx.coroutines.core)
            export(libs.kotlinx.serialization.json)
            export(libs.ktor.client.core)
            baseName = xcFrameworkName
            binaryOption("bundleId", "org.multipaz.${xcFrameworkName}")
            binaryOption("bundleVersion", projectVersionCode.toString())
            binaryOption("bundleShortVersionString", projectVersionName)
            freeCompilerArgs += listOf(
                "-Xoverride-konan-properties=minVersion.ios=16.0;minVersionSinceXcode15.ios=16.0",
                // Uncomment the following to get Garbage Collection logging when using the framework:
                //
                // "-Xruntime-logs=gc=info"
            )
            linkerOpts("-lsqlite3")
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":multipaz"))
                api(project(":multipaz-dcapi"))
                api(project(":multipaz-doctypes"))
                api(project(":multipaz-longfellow"))
                api(libs.kotlinx.io.bytestring)
                api(libs.kotlinx.io.core)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                api(libs.ktor.client.core)
            }
        }
    }
}
subprojects {
	apply(plugin = "org.jetbrains.dokka")
}