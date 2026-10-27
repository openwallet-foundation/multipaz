rootProject.name = "MultipazProject"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Separate includeBuild so that non-plugin dependencies (e.g. dokkaPlugin) from
// build-logic can be resolved via composite build substitution.
includeBuild("build-logic") {
    dependencySubstitution {
        substitute(module("org.multipaz:dokka-known-subclasses-plugin"))
            .using(project(":dokka-known-subclasses-plugin"))
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://jitpack.io") {
            mavenContent {
                includeGroup("com.github.yuriy-budiyev")
            }
        }
        // Koog repository for Lokalize plugin worker dependencies
        maven("https://packages.jetbrains.team/maven/p/kt/koog")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.9.0")
}

include(":multipaz-cbor-rpc")
include(":multipaz")
include(":multipaz:SwiftBridge")
include(":multipaz-dcapi-credman")
include(":multipaz-dcapi-ios")
include(":multipaz-doctypes")
include(":multipaz-utopia")
include(":multipaz-android-legacy")
include(":multipaz-csa")
include(":multipaz-longfellow")
include(":multipaz-facenet")
include(":multipaz-facenet-test-data")
include(":multipazctl")
include(":multipaz-dcapi-credman:matcherTest")
include(":multipaz-server")
include(":multipaz-backend-server")
include(":multipaz-compose")
include(":multipaz-openid4vci")
include(":multipaz-openid4vci-server")
include(":multipaz-verifier")
include(":multipaz-verifier-server")
include(":multipaz-csa-server")
include(":multipaz-records")
include(":multipaz-records-server")
include(":samples:testapp")
include(":samples:legacytestapp")
include(":samples:SwiftTestApp")
include(":xcframework")
include(":multipaz-server-frontend")
include(":multipaz-server-deployment")
include(":multipaz-swiftui")
include(":multipaz-tools:web")
include(":multipaz-tools:server")

