plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("org.multipaz.tools.server.Main")
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":multipaz"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.logging)
    implementation(libs.logback.classic)
}

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

if (!disableWebTargets) {
    evaluationDependsOn(":multipaz-tools:web")

    val jsBrowserDistribution = project(":multipaz-tools:web").tasks.named("jsBrowserDistribution")

    tasks.named<ProcessResources>("processResources") {
        dependsOn(jsBrowserDistribution)
        from(project(":multipaz-tools:web").layout.buildDirectory.dir("dist/js/productionExecutable")) {
            into("static")
        }
    }
}

ktor {
}
