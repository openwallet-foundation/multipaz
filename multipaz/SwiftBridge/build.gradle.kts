import org.jetbrains.kotlin.konan.target.HostManager

if (HostManager.hostIsMac) {
    listOf("iphoneos", "iphonesimulator").forEach { sdk ->
        @Suppress("DEPRECATION") // The capitalize() is deprecated.
        tasks.create<Exec>("build${sdk.capitalize()}") {
            group = "build"

            commandLine(
                "xcodebuild",
                "-project", "SwiftBridge.xcodeproj",
                "-scheme", "SwiftBridge",
                "-sdk", sdk,
                "-configuration", "Release",
                "SYMROOT=${projectDir}/build"
            )
            workingDir(projectDir)

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

