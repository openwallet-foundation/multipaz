import org.jetbrains.kotlin.konan.target.HostManager

val xcodeDeveloperPath: String by rootProject.extra

if (HostManager.hostIsMac) {
    listOf("iphoneos", "iphonesimulator").forEach { sdk ->
        @Suppress("DEPRECATION") // The capitalize() is deprecated.
        tasks.create<Exec>("build${sdk.capitalize()}") {
            group = "build"

            val derivedDataDir = file("${projectDir}/build/DerivedData-${sdk}")
            doFirst {
                delete(derivedDataDir)
            }

            commandLine(
                "/usr/bin/env", "-i",
                "PATH=${System.getenv("PATH") ?: "/usr/bin:/bin:/usr/sbin:/sbin"}",
                "HOME=${System.getenv("HOME") ?: ""}",
                "USER=${System.getenv("USER") ?: ""}",
                "TMPDIR=${System.getenv("TMPDIR") ?: "/tmp"}",
                "DEVELOPER_DIR=${xcodeDeveloperPath}",
                "xcodebuild",
                "-project", "SwiftBridge.xcodeproj",
                "-scheme", "SwiftBridge",
                "-sdk", sdk,
                "-configuration", "Release",
                "-derivedDataPath", derivedDataDir.absolutePath,
                "SYMROOT=${projectDir}/build"
            )
            workingDir(projectDir)

            inputs.property("xcodeDeveloperPath", xcodeDeveloperPath)
            inputs.files(
                fileTree("$projectDir/SwiftBridge.xcodeproj") { exclude("**/xcuserdata") },
                fileTree("$projectDir/SwiftBridge")
            )
            outputs.files(
                fileTree("$projectDir/build/Release-${sdk}")
            )
        }
    }
}

tasks.create<Delete>("clean") {
    group = "build"

    delete("$projectDir/build")
}

