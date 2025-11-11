import org.apache.commons.io.output.ByteArrayOutputStream
import org.jetbrains.dokka.gradle.DokkaTaskPartial

// For `versionCode` we just use the number of commits.
val projectVersionCode: Int by extra {
    val stdout = ByteArrayOutputStream()
    rootProject.exec {
        commandLine("git", "rev-list", "HEAD", "--count")
        standardOutput = stdout
    }
    @Suppress("DEPRECATION") // toString() is deprecated.
    stdout.toString().trim().toInt()
}

// The version number of the project.
//
// For a tagged release, projectVersionNext should be blank and the next commit
// following the release should bump it to the next version number.
//
val projectVersionLast = "0.95.0"
val projectVersionNext = ""

private fun runCommand(args: List<String>): String {
    val stdout = ByteArrayOutputStream()
    rootProject.exec {
        commandLine(args)
        standardOutput = stdout
    }
    return stdout.toString().trim()
}

// Generate a project version meeting the requirements of Semantic Versioning 2.0.0
// according to https://semver.org/
//
// Essentially, for tagged releases use the version number e.g. "0.91.0". Otherwise use
// the next version number with a pre-release string set to "pre.N.H" where N is the
// number of commits since the last version and H is the short commit hash of the
// where we cut the pre-release from. Example: 0.91.0-pre.48.574b479c
//
val projectVersionName: String by extra {
    if (projectVersionNext.isEmpty()) {
        projectVersionLast
    } else {
        val numCommitsSinceTag = runCommand(listOf("git", "rev-list", "${projectVersionLast}..", "--count"))
        val commitHash = runCommand(listOf("git", "rev-parse", "--short", "HEAD"))
        projectVersionNext + "-pre.${numCommitsSinceTag}.${commitHash}"
    }
}

tasks.register("printVersionName") {
    doLast {
        println(projectVersionName)
    }
}

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.navigation.safe.args) apply false
    alias(libs.plugins.parcelable) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.skie) apply false
    id("org.jetbrains.dokka") version "2.0.0"
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
}

tasks.named("dokkaHtmlMultiModule") {
    dependsOn(
        ":samples:dokkaHtmlMultiModule",
        ":multipaz:dokkaHtmlMultiModule",
        ":multipaz-dcapi:dokkaHtmlMultiModule",
    )
}