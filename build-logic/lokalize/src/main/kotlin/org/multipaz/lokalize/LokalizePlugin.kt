package org.multipaz.lokalize

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.multipaz.lokalize.tasks.GenerateStringsTask
import org.multipaz.lokalize.tasks.LokalizeCheckTask
import org.multipaz.lokalize.tasks.LokalizeTranslateTask
import org.multipaz.lokalize.tasks.LokalizeVerifyGeneratedTask
import org.multipaz.lokalize.util.LLMProvider
import org.multipaz.lokalize.util.LLmModel
import org.multipaz.lokalize.util.LokalizeExtension
import org.multipaz.lokalize.util.OutputFormat
import java.io.File

/**
 * Lokalize Gradle Plugin for internationalization enforcement and AI-assisted translation.
 *
 * Provides tasks:
 * - lokalizeCheck: Validates that all target locales have complete translations
 * - lokalizeFix: Generates missing translations using AI
 *
 * Example configuration:
 * ```kotlin
 * lokalize {
 *     defaultLocale = "en"
 *     targetLocales = listOf("es", "fr", "de")
 *     failOnMissing = true
 *     llmApiKey.set("your-api-key") // or use LOKALIZE_API_KEY env var
 *     llmProvider.set(LLMProvider.GOOGLE)  // or OPENAI, ANTHROPIC
 *     llModel.set(LLmModel.GEMINI2_5_FLASH_LITE)  // see LLmModel enum for all options
 *     resourcesDir.set("src/commonMain/composeResources") // optional: custom path
 *     outputFormat.set(OutputFormat.XML) // or JSON for web/desktop projects
 * }
 * ```
 *
 * Output formats:
 * - XML: Android strings.xml format with values/values-locale folders
 * - JSON: JSON format with nested keys, stored as values/strings.json and values-locale/strings.json
 */
class LokalizePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val ext = target.extensions.create("lokalize", LokalizeExtension::class.java)

        // CRITICAL: Create worker configuration in the target project for classpath isolation
        // This ensures the worker JVM gets its own dependencies separate from Gradle's runtime
        // Note: Koog repository must be added in settings.gradle.kts dependencyResolutionManagement
        target.configurations.create("lokalizeWorker") { conf ->
            conf.isCanBeConsumed = false
            conf.isCanBeResolved = true
        }

        // Add dependencies to the worker configuration
        // Using correct artifact names from ai.koog (not the libs aliases), but the
        // version is read from the "libs" version catalog so it can't drift from the
        // koog version the plugin was actually compiled against (see build.gradle.kts).
        val libs = target.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val koogVersion = libs.findVersion("koog").get().requiredVersion
        val kotlinVersion = libs.findVersion("kotlin").get().requiredVersion
        val coroutinesVersion = libs.findVersion("kotlinx-coroutines").get().requiredVersion

        // The worker process is launched with a plain, fully-explicit classpath (see
        // LokalizeTranslateTask), so it needs the lokalize plugin's own classes too -
        // Gradle doesn't add those implicitly the way it would for a WorkAction.
        val pluginClasspathEntry = File(LokalizePlugin::class.java.protectionDomain.codeSource.location.toURI())

        target.dependencies.apply {
            add("lokalizeWorker", target.files(pluginClasspathEntry))
            add("lokalizeWorker", "ai.koog:koog-agents:$koogVersion")
            add("lokalizeWorker", "ai.koog:agents-ext:$koogVersion")
            add("lokalizeWorker", "ai.koog:prompt-executor-openai-client:$koogVersion")
            add("lokalizeWorker", "ai.koog:prompt-executor-google-client:$koogVersion")
            add("lokalizeWorker", "ai.koog:prompt-executor-anthropic-client:$koogVersion")
            add("lokalizeWorker", "ai.koog:prompt-executor-llms-all:$koogVersion")
            add("lokalizeWorker", "ai.koog:prompt-model:$koogVersion")
            add("lokalizeWorker", "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
            add("lokalizeWorker", "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        }

        ext.llmApiKey.convention(target.providers.environmentVariable("LOKALIZE_API_KEY")
            .orElse(target.providers.environmentVariable("KOOG_API_KEY"))
            .orElse(target.providers.environmentVariable("OPENAI_API_KEY"))
            .orElse(target.providers.environmentVariable("GOOGLE_API_KEY"))
            .orElse(target.providers.environmentVariable("ANTHROPIC_API_KEY")))

        ext.llmProvider.convention(LLMProvider.GOOGLE)
        ext.llModel.convention(LLmModel.GEMINI2_5_FLASH_LITE)
        ext.resourcesDir.convention("src/commonMain/composeResources")
        ext.outputFormat.convention(OutputFormat.XML)
        ext.generatedTranslationsPackageName.convention("org.multipaz.doctypes.generated")
        ext.stringKeysPackageName.convention("org.multipaz.doctypes.localization")
        ext.generatedSourceDir.convention("src/commonMain/generated")

        // Register check task - validates translations
        target.tasks.register("lokalizeCheck", LokalizeCheckTask::class.java) { task ->
            task.description = "Validates that all target locales have complete translations"
            task.group = "lokalize"

            // Wire extension values to task inputs lazily
            task.defaultLocale.convention(ext.defaultLocale)
            task.targetLocales.convention(ext.targetLocales)
            task.failOnMissing.convention(ext.failOnMissing)
            task.resourcesDir.convention(ext.resourcesDir)
            task.outputFormat.convention(ext.outputFormat)
            task.extension.convention(ext)

            // Compute base strings file path from resourcesDir and outputFormat
            task.baseStringsFile.convention(
                target.layout.projectDirectory.file(
                    ext.outputFormat.zip(ext.resourcesDir) { format, resDir ->
                        when (format) {
                            OutputFormat.XML -> "$resDir/values/strings.xml"
                            OutputFormat.JSON -> "$resDir/values/strings.json"
                        }
                    }
                )
            )
        }

        // Register fix task - generates missing translations with AI
        target.tasks.register("lokalizeFix", LokalizeTranslateTask::class.java) { task ->
            task.description = "Generates missing translations using AI"
            task.group = "lokalize"

            // Wire extension values to task inputs lazily
            task.llmApiKey.convention(ext.llmApiKey)
            task.llmProvider.convention(ext.llmProvider)
            task.llModel.convention(ext.llModel)
            task.resourcesDir.convention(ext.resourcesDir)
            task.defaultLocale.convention(ext.defaultLocale)
            task.targetLocales.convention(ext.targetLocales)
            task.outputFormat.convention(ext.outputFormat)
            task.extension.convention(ext)

            // Output directory for temporary translation results (in build/, not source)
            task.outputDirectory.convention(
                target.layout.buildDirectory.dir("lokalize/translations")
            )
        }

        // Root of the checked-in generated Kotlin source tree, a committed directory under src/.
        // Defaults to src/commonMain/generated (see the convention above) and is only populated for
        // JSON-format modules; XML modules never generate Kotlin, so it stays empty for them.
        val generatedSourceRoot = ext.generatedSourceDir
            .map { target.layout.projectDirectory.dir(it) }

        // Register the generate strings task - it will decide at execution time what to do
        val generateStringsTask = target.tasks.register("generateMultipazStrings", GenerateStringsTask::class.java) { task ->
            task.description = "Generates Kotlin constants from JSON string resources for embedded access (skips for XML format)"
            task.group = "lokalize"

            task.defaultLocale.convention(ext.defaultLocale)
            task.outputFormat.convention(ext.outputFormat)
            task.packageName.convention(ext.generatedTranslationsPackageName)
            task.stringKeysPackageName.convention(ext.stringKeysPackageName)
            task.generatedClassName.convention("GeneratedTranslations")

            task.resourcesDir.convention(
                target.layout.projectDirectory.dir(ext.resourcesDir)
            )

            // Only JSON modules write into the checked-in src/ tree. For XML modules the task skips,
            // but @OutputDirectory would still have Gradle create the src/ dir - so point it at a
            // throwaway build/ location there to keep the source tree pristine.
            task.outputDir.convention(
                ext.outputFormat.flatMap { format ->
                    if (format == OutputFormat.JSON) {
                        generatedSourceRoot
                    } else {
                        target.layout.buildDirectory.dir("lokalize/unused-generated")
                    }
                }
            )
        }

        // commonMain must see the checked-in generated tree. The sources are already on disk and
        // committed, so this carries no producer dependency: compiling reads them directly and never
        // regenerates. A JSON module keeps them fresh via lokalizeCheckGenerated; an XML module
        // leaves this directory empty, which is harmless.
        //
        // Configured EARLY (during configuration phase, not afterEvaluate): KMP source sets are
        // frozen after afterEvaluate, so we must do this now.
        target.extensions.findByType(KotlinMultiplatformExtension::class.java)?.let { kotlinExt ->
            kotlinExt.sourceSets.findByName("commonMain")?.let { sourceSet ->
                sourceSet.kotlin.srcDir(generatedSourceRoot)
            }
        }

        target.afterEvaluate {
            // The `lokalize {}` block has run by now, so the output format is finally known. Only
            // JSON modules bake strings into checked-in Kotlin; XML modules (multipaz-compose) read
            // strings.xml at runtime via Compose Resources and generate nothing.
            if (ext.outputFormat.get() == OutputFormat.JSON) {
                registerVerifyGeneratedTask(target, ext, generatedSourceRoot)
                // Translating writes new strings.json entries, which the committed sources must
                // reflect; regenerating straight afterwards keeps the two committed together.
                target.tasks.named("lokalizeFix").configure { it.finalizedBy(generateStringsTask) }
            }

            // Hook check task into build lifecycle if enabled
            if (ext.failOnMissing) {
                target.tasks.findByName("compileKotlin")?.dependsOn("lokalizeCheck")
                target.tasks.findByName("compileCommonMainKotlinMetadata")?.dependsOn("lokalizeCheck")
            }
        }
    }

    /**
     * Registers `lokalizeCheckGenerated` and wires it into `check`.
     *
     * Only meaningful for checked-in modules: with the generator off the compile path, this is the
     * only thing standing between a `strings.json` edit and stale committed sources.
     */
    private fun registerVerifyGeneratedTask(
        target: Project,
        ext: LokalizeExtension,
        generatedSourceRoot: Provider<Directory>
    ) {
        val verifyTask = target.tasks.register(
            "lokalizeCheckGenerated",
            LokalizeVerifyGeneratedTask::class.java
        ) { task ->
            task.defaultLocale.convention(ext.defaultLocale)
            task.outputFormat.convention(ext.outputFormat)
            task.packageName.convention(ext.generatedTranslationsPackageName)
            task.stringKeysPackageName.convention(ext.stringKeysPackageName)
            task.generatedClassName.convention("GeneratedTranslations")
            task.projectPath.convention(target.path)
            task.resourcesDir.convention(target.layout.projectDirectory.dir(ext.resourcesDir))
            task.generatedDir.convention(generatedSourceRoot)
            task.stampFile.convention(target.layout.buildDirectory.file("lokalize/generated-check.stamp"))
        }

        target.tasks.named("check").configure { it.dependsOn(verifyTask) }
    }
}
