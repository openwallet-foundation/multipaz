plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("org.multipaz.upay.server.Main")
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        allWarningsAsErrors = true
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    ksp(project(":multipaz-cbor-rpc"))
    implementation(project(":multipaz"))
    implementation(project(":multipaz-doctypes"))
    implementation(project(":multipaz-utopia"))
    implementation(project(":multipaz-longfellow"))
    implementation(project(":multipaz-server"))
    implementation(project(":multipaz-verifier"))

    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.bytestring)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)

    testImplementation(libs.junit)
}

ktor {
}
