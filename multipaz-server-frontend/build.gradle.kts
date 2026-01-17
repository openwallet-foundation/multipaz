import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js(IR) {
        // This is for running in the browser, not server/node.js
        browser {
            commonWebpackConfig {
                cssSupport { enabled.set(true) }
                outputFileName = "multipaz-web.js"
            }
            runTask {
                devServerProperty.set(KotlinWebpackConfig.DevServer(
                    port = 3000,
                    open = false,
                    static = mutableListOf(
                        file("src/jsMain/resources").path
                    )
                ))
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                // Multipaz library (provides Crypto, toBase64Url, etc.)
                implementation(project(":multipaz"))

                // Kotlin React wrappers
                implementation(libs.kotlin.wrappers.react)
                implementation(libs.kotlin.wrappers.react.dom)
                implementation(libs.kotlin.wrappers.emotion.react.js)
            }
        }
    }
}
