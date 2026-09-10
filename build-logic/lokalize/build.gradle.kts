import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.21"
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

group = "org.multipaz.util.lokalize"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// CRITICAL: Create a separate configuration for worker classpath isolation
// This ensures the worker JVM gets its own classpath with Koog dependencies
// independent from Gradle's embedded Kotlin runtime
val lokalizeWorker = configurations.create("lokalizeWorker") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.runtimeClasspath.get())
}

gradlePlugin {
    plugins {
        create("lokalize") {
            id = "org.multipaz.lokalize"
            implementationClass = "org.multipaz.lokalize.LokalizePlugin"
        }
    }
}

dependencies {
    compileOnly(libs.gradlePlugin.kotlin)
    compileOnly(libs.android.tools.gradle.plugin)

    // JSON serialization
    implementation(libs.kotlinx.serialization.json)

    // Koog AI Agents and executors - for compilation
    implementation(libs.koog.agent)
    implementation(libs.koog.agents.ext)
    implementation(libs.koog.executor.openai.client)
    implementation(libs.koog.executor.google.client)
    implementation(libs.koog.executor.anthropic.client)
    implementation(libs.koog.executor.llms.all)
    implementation(libs.koog.prompt.model)

    // Gradle API
    compileOnly(gradleApi())

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Worker configuration - explicit dependencies for isolated worker classpath
    // # Koog AI Agent - these are the ONLY dependencies the worker JVM will see
    lokalizeWorker(libs.koog.agent)
    lokalizeWorker(libs.koog.agents.ext)
    lokalizeWorker(libs.koog.executor.openai.client)
    lokalizeWorker(libs.koog.executor.google.client)
    lokalizeWorker(libs.koog.executor.anthropic.client)
    lokalizeWorker(libs.koog.executor.llms.all)
    lokalizeWorker(libs.koog.prompt.model)

    // Explicit Kotlin stdlib for worker (2.2.x as required by Koog)
    lokalizeWorker(libs.kotlin.stdlib)
    lokalizeWorker(libs.kotlin.stdlib.jdk7)
    lokalizeWorker(libs.kotlin.stdlib.jdk8)

    // Coroutines for worker (must be compatible with Kotlin 2.2.x)
    lokalizeWorker(libs.kotlinx.coroutines.core)
    lokalizeWorker(libs.kotlinx.coroutines.core.jvm)
}
