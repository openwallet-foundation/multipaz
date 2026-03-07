import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

kotlin {
    if (!disableWebTargets) {
        js(IR) {
            // This is for running in the browser, not server/node.js
            browser {
                commonWebpackConfig {
                    cssSupport { enabled.set(true) }
                    outputFileName = "multipaz-web.js"
                }
                runTask {
                    // This defines how to run the front-end in development environment
                    devServerProperty.set(
                        KotlinWebpackConfig.DevServer(
                            port = 3000,
                            open = false,
                            static = mutableListOf(
                                file("src/jsMain/resources").path
                            ),
                            proxy = mutableListOf(
                                // Hook locally-running verifier multipaz-verifier-server
                                KotlinWebpackConfig.DevServer.Proxy(
                                    context = mutableListOf("/verifier/"),
                                    target = "http://localhost:8006/",
                                    pathRewrite = mutableMapOf(
                                        "^/verifier/" to ""
                                    )
                                ),
                                KotlinWebpackConfig.DevServer.Proxy(
                                    context = mutableListOf("/records/"),
                                    target = "http://localhost:8004/",
                                    pathRewrite = mutableMapOf(
                                        "^/records/" to ""
                                    )
                                ),

                                )
                        )
                    )
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
}
