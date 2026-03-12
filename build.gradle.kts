import org.apache.commons.io.output.ByteArrayOutputStream
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

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
    alias(libs.plugins.detekt) apply false

    id("org.jetbrains.dokka") version "2.1.0"
}

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
val projectVersionLast = "0.97.0"
val projectVersionNext = "0.98.0"

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

// Define a shared staging directory in the root build/ folder
val stagingRepoDir = layout.buildDirectory.dir("staging-repo")

// Aggregate task to zip everything up
tasks.register<Zip>("createPortalBundle") {
    group = "publishing"
    description = "Creates a signed ZIP bundle for Maven Central portal upload."

    // 1. Depend on all subprojects' publishing tasks targeting our staging repo
    val publishTasks = subprojects.flatMap { subproject ->
        subproject.tasks.withType<PublishToMavenRepository>().matching {
            it.repository.name == "PortalStaging"
        }
    }
    dependsOn(publishTasks)

    // 2. Zip the shared root staging directory
    from(stagingRepoDir) {
        exclude("**/maven-metadata*.xml")
    }

    archiveFileName.set("multipaz-publish-bundle-${projectVersionName}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

subprojects {
    plugins.withType<MavenPublishPlugin> {
        apply(plugin = "signing")

        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "PortalStaging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
                }
            }

            // Create a unique empty Javadoc JAR for EVERY publication
            publications.withType<MavenPublication>().configureEach {
                val publicationName = name
                val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar${publicationName.replaceFirstChar { it.uppercase() }}") {
                    archiveClassifier.set("javadoc")
                    // Isolate the output directory so the .asc signature files don't collide
                    destinationDirectory.set(layout.buildDirectory.dir("emptyJavadocs/$publicationName"))
                }
                artifact(emptyJavadocJar)
            }
        }

        configure<SigningExtension> {
            val signingKey = providers.gradleProperty("signingKey")
            val signingPassword = providers.gradleProperty("signingPassword")

            val hasKeys = signingKey.isPresent && signingPassword.isPresent
            val isPortalBuild = gradle.startParameter.taskNames.any { it.contains("createPortalBundle") }

            // Only sign if the developer has configured keys OR is explicitly building a portal bundle
            if (hasKeys) {
                useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
                sign(extensions.getByType<PublishingExtension>().publications)
            } else if (isPortalBuild) {
                useGpgCmd()
                sign(extensions.getByType<PublishingExtension>().publications)
            } else {
                // Silently skip signing to allow local development for contributors without keys
                println("Skipping artifact signing for ${project.name}: No signing keys configured.")
            }
        }
    }
}

val detektModules = listOf(
    ":multipaz",
    ":multipaz-compose",
    ":multipaz-dcapi",
    ":multipaz-doctypes",
    ":multipaz-longfellow",
)

subprojects {
    if (path in detektModules) {
        apply(plugin = "io.gitlab.arturbosch.detekt")

        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
            baseline = file("$projectDir/config/detekt/baseline.xml")
        }
    }
}

dependencies {
    dokka(project(":multipaz"))
    dokka(project(":multipaz-compose"))
    dokka(project(":multipaz-dcapi"))
    dokka(project(":multipaz-doctypes"))
    dokka(project(":multipaz-longfellow"))
    dokka(project(":multipaz-cbor-rpc"))
    dokka(project(":multipaz-android-legacy"))
}