import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

kotlin {
    if (!disableWebTargets) {
        js(IR) {
            browser {
                commonWebpackConfig {
                    cssSupport { enabled.set(true) }
                    outputFileName = "multipaz-tools.js"
                }
                runTask {
                    devServerProperty.set(
                        KotlinWebpackConfig.DevServer(
                            port = 3000,
                            open = false,
                            static = mutableListOf(
                                file("src/jsMain/resources").path
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
                    implementation(project(":multipaz"))
                    implementation(project(":multipaz-doctypes"))
                    implementation(libs.kotlinx.coroutines.core)
                    implementation(libs.kotlinx.datetime)
                    implementation(libs.kotlinx.serialization.json)
                    implementation(libs.kotlin.wrappers.react)
                    implementation(libs.kotlin.wrappers.react.dom)
                    implementation(libs.kotlin.wrappers.emotion.react.js)
                }
            }
        }
    }
}
