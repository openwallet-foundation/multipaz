plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass.set("org.multipaz.mpzpass.GenerateMpzPassExamples")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":multipaz"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io.bytestring)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("generateMpzPassExamples", JavaExec::class) {
    group = "Execution"
    description = "Regenerates example MpzPass files and certificates in mpzpass/"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.multipaz.mpzpass.GenerateMpzPassExamples"
    workingDir = project.projectDir
}
