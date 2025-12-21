plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("org.multipaz.multipazctl.MultipazCtl")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":multipaz"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.bouncy.castle.bcprov)
    implementation(libs.bouncy.castle.bcpkix)
}

tasks.register("runMultipazCtl", JavaExec::class) {
    group = "Execution"
    description = "Invoke multipazctl"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.multipaz.multipazctl.MultipazCtl"
    workingDir = project.rootDir
}

ktor {
}

subprojects {
	apply(plugin = "org.jetbrains.dokka")
}
