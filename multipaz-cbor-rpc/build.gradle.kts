plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
    id("org.jetbrains.dokka") version "2.1.0"
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
    implementation(libs.kotlinx.io.core)

    testImplementation(libs.junit)
}

group = "org.multipaz"
version = projectVersionName

publishing {
    repositories {
        maven {
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
    publications {
        create<MavenPublication>("library") {
            afterEvaluate {
                from(components["java"])
            }
        }
    }
    publications.withType(MavenPublication::class) {
        pom {
            name.set("multipaz-cbor-rpc")
            description.set("Multipaz SDK cbor-rpc module")
            url.set("https://github.com/openwallet-foundation/multipaz")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://opensource.org/licenses/Apache-2.0")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("zeuthen")
                    name.set("David Zeuthen")
                    email.set("zeuthen@google.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/openwallet-foundation/multipaz.git")
                developerConnection.set("scm:git:ssh://github.com/openwallet-foundation/multipaz.git")
                url.set("https://github.com/openwallet-foundation/multipaz")
            }
        }
    }
}

