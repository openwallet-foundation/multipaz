package org.multipaz.lokalize.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.multipaz.lokalize.engine.ResourceScanner
import org.multipaz.lokalize.engine.ResourceWriterStrategy
import org.multipaz.lokalize.engine.ResourceWriterStrategyFactory
import org.multipaz.lokalize.engine.TranslationComparator
import org.multipaz.lokalize.model.LocaleBundle
import org.multipaz.lokalize.model.ResourceEntry
import org.multipaz.lokalize.util.LLMProvider
import org.multipaz.lokalize.util.LLmModel
import org.multipaz.lokalize.util.LokalizeExtension
import org.multipaz.lokalize.util.OutputFormat
import org.multipaz.lokalize.util.toProvider
import java.io.File
import javax.inject.Inject

/**
 * Task that detects missing translations and translates them using AI.
 * NOTE: Not cacheable - modifies source files and makes external API calls.
 */
abstract class LokalizeTranslateTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @get:Internal
    abstract val extension: Property<LokalizeExtension>

    @get:Input
    abstract val llmApiKey: Property<String>

    @get:Input
    abstract val llmProvider: Property<LLMProvider>

    @get:Input
    abstract val llModel: Property<LLmModel>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val resourcesDir: Property<String>

    @get:Input
    abstract val defaultLocale: Property<String>

    @get:Input
    abstract val targetLocales: ListProperty<String>

    @get:Internal
    abstract val baseStringsFile: Property<File>

    @get:Input
    abstract val outputFormat: Property<OutputFormat>

    init {
        // Never cache this task - it modifies source files and makes external API calls
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val apiKey = llmApiKey.orNull ?: run {
                logger.warn("No API key configured - will use fallback mode (copying base text)")
                ""
            }

        val provider = llmProvider.getOrElse(LLMProvider.GOOGLE)
        val model = llModel.getOrElse(LLmModel.GEMINI2_5_FLASH_LITE)

        val modelProvider = model.toProvider()
        val effectiveProvider = when {
            modelProvider != provider -> {
                logger.warn(
                    "Model ${model.name} (${modelProvider.name}) doesn't match configured provider ${provider.name}. " +
                        "Using model's provider: ${modelProvider.name}"
                )
                modelProvider
            }
            else -> provider
        }

        logger.lifecycle("Using LLM: ${model.name} (${effectiveProvider.name})")

        // CRITICAL: Run translation in a plain forked `java` process (not Gradle's
        // WorkerExecutor). Gradle's Worker API layers the worker classpath as a child
        // of Gradle's own classloader, which leaks `kotlin.*` packages from Gradle's
        // bundled (older) kotlin-stdlib ahead of ours - and koog needs a newer one
        // (kotlin.time.Clock). A plain forked process has no such parent classloader.
        val workerClasspath = project.configurations.getByName("lokalizeWorker")

        val baseLocale = defaultLocale.get()
        val resDir = resourcesDir.get()
        val format = outputFormat.getOrElse(OutputFormat.XML)

        val (baseDir, baseFileName, targetFilePrefix, targetFileExtension) = when (format) {
            OutputFormat.XML -> {
                listOf(
                    File(project.projectDir, "$resDir/values"),
                    "strings.xml",
                    "values-",
                    "/strings.xml"
                )
            }
            OutputFormat.JSON -> {

                listOf(
                    File(project.projectDir, "$resDir/values"),
                    "strings.json",
                    "values-",
                    "/strings.json"
                )
            }
        }
        val baseFile = File(baseDir as File, baseFileName as String)

        if (!baseFile.exists()) {
            error("Base file not found at: ${baseFile.absolutePath}")
        }

        val scanner = ResourceScanner()
        val comparator = TranslationComparator()
        val writer = ResourceWriterStrategyFactory.create(format)

        val baseBundle = scanner.scan(baseFile)
        logger.info("Found ${baseBundle.totalEntries()} entries in base locale '$baseLocale'")

        targetLocales.get().forEach { targetLocale ->
            processLocale(
                targetLocale = targetLocale,
                baseBundle = baseBundle,
                baseLocale = baseLocale,
                resDir = resDir,
                targetFilePrefix = targetFilePrefix as String,
                targetFileExtension = targetFileExtension as String,
                scanner = scanner,
                comparator = comparator,
                writer = writer,
                apiKey = apiKey,
                provider = effectiveProvider,
                model = model,
                workerClasspath = workerClasspath
            )
        }
    }

    private fun processLocale(
        targetLocale: String,
        baseBundle: LocaleBundle,
        baseLocale: String,
        resDir: String,
        targetFilePrefix: String,
        targetFileExtension: String,
        scanner: ResourceScanner,
        comparator: TranslationComparator,
        writer: ResourceWriterStrategy,
        apiKey: String,
        provider: LLMProvider,
        model: LLmModel,
        workerClasspath: FileCollection
    ) {
        val targetFile = File(project.projectDir, "$resDir/$targetFilePrefix$targetLocale$targetFileExtension")
        val targetBundle = if (targetFile.exists()) {
            scanner.scan(targetFile)
        } else {
            LocaleBundle.empty(targetLocale)
        }

        val comparison = comparator.compareWithPluralRules(baseBundle, targetBundle)

        if (!comparison.hasMissing) {
            logger.lifecycle("✓ Locale '$targetLocale' is complete")
            return
        }

        logger.lifecycle("Found ${comparison.totalMissing} missing entries for '$targetLocale'")

        val missingStrings = mutableMapOf<String, ResourceEntry.StringEntry>()
        val missingPlurals = mutableMapOf<String, ResourceEntry.PluralEntry>()
        val missingArrays = mutableMapOf<String, ResourceEntry.StringArrayEntry>()

        val stringWorkItems = comparison.missingStrings.map { key ->
            key to baseBundle.strings[key]!!.value
        }

        val pluralWorkItems = mutableListOf<Pair<String, String>>()
        val pluralQuantityMap = mutableMapOf<String, Pair<String, String>>()

        comparison.missingPlurals.forEach { pluralKey ->
            val basePlural = baseBundle.plurals[pluralKey]!!
            val requiredQuantities = comparator.requiredQuantities(targetLocale)

            requiredQuantities.forEach { quantity ->
                // Use the base value if available, otherwise fall back to 'other'
                val value = basePlural.items[quantity]
                    ?: basePlural.items["other"]
                    ?: basePlural.items.values.firstOrNull()
                    ?: ""
                val fullId = "$pluralKey#$quantity"
                pluralWorkItems.add(fullId to value)
                pluralQuantityMap[fullId] = pluralKey to quantity
            }
        }

        comparison.incompletePlurals.forEach { (pluralKey, missingQuantities) ->
            val basePlural = baseBundle.plurals[pluralKey]!!
            missingQuantities.forEach { quantity ->
                // Use the base value if available, otherwise fall back to 'other' or any available
                val value = basePlural.items[quantity]
                    ?: basePlural.items["other"]
                    ?: basePlural.items.values.firstOrNull()
                    ?: ""
                val fullId = "$pluralKey#$quantity"
                pluralWorkItems.add(fullId to value)
                pluralQuantityMap[fullId] = pluralKey to quantity
            }
        }

        // Try AI translation for both strings and plurals
        val stringTranslations = if (apiKey.isNotBlank() && stringWorkItems.isNotEmpty()) {
            tryAITranslation(
                stringWorkItems = stringWorkItems,
                baseLocale = baseLocale,
                targetLocale = targetLocale,
                apiKey = apiKey,
                provider = provider,
                model = model,
                workerClasspath = workerClasspath
            )
        } else {
            emptyMap()
        }

        val pluralTranslations = if (apiKey.isNotBlank() && pluralWorkItems.isNotEmpty()) {
            tryAITranslation(
                stringWorkItems = pluralWorkItems,
                baseLocale = baseLocale,
                targetLocale = targetLocale,
                apiKey = apiKey,
                provider = provider,
                model = model,
                workerClasspath = workerClasspath
            )
        } else {
            emptyMap()
        }

        // Build missing strings bundle
        comparison.missingStrings.forEach { key ->
            val baseText = baseBundle.strings[key]!!.value
            val translation = stringTranslations[key]?.takeIf { it.isNotBlank() } ?: baseText
            missingStrings[key] = ResourceEntry.StringEntry(key, translation)
        }

        // Build missing plurals bundle with AI translations
        val pluralTranslationsByKey = mutableMapOf<String, MutableMap<String, String>>()

        pluralTranslations.forEach { (fullId, translatedText) ->
            val (pluralKey, quantity) = pluralQuantityMap[fullId]!!
            pluralTranslationsByKey.getOrPut(pluralKey) { mutableMapOf() }[quantity] = translatedText
        }

        comparison.missingPlurals.forEach { pluralKey ->
            val basePlural = baseBundle.plurals[pluralKey]!!
            val translatedItems = mutableMapOf<String, String>()

            basePlural.items.forEach { (quantity, baseValue) ->
                translatedItems[quantity] = pluralTranslationsByKey[pluralKey]?.get(quantity)
                    ?.takeIf { it.isNotBlank() }
                    ?: baseValue
            }

            missingPlurals[pluralKey] = ResourceEntry.PluralEntry(pluralKey, translatedItems)
        }

        // Handle incomplete plurals
        comparison.incompletePlurals.forEach { (pluralKey, missingQuantities) ->
            val existingPlural = targetBundle.plurals[pluralKey]!!
            val newItems = existingPlural.items.toMutableMap()

            missingQuantities.forEach { quantity ->
                val translatedText = pluralTranslationsByKey[pluralKey]?.get(quantity)
                val baseValue = baseBundle.plurals[pluralKey]?.items?.get(quantity) ?: ""
                newItems[quantity] = translatedText?.takeIf { it.isNotBlank() } ?: baseValue
            }

            missingPlurals[pluralKey] = ResourceEntry.PluralEntry(pluralKey, newItems)
        }

        // Handle arrays
        comparison.missingArrays.forEach { key ->
            val baseArray = baseBundle.arrays[key]!!
            missingArrays[key] = ResourceEntry.StringArrayEntry(key, baseArray.items.map { "" })
        }

        // Create bundle and merge
        val missingBundle = LocaleBundle(
            locale = targetLocale,
            strings = missingStrings,
            plurals = missingPlurals,
            arrays = missingArrays
        )

        writer.mergeBundle(targetFile, missingBundle, preserveExisting = true)
        logger.lifecycle("✓ Processed ${comparison.totalMissing} entries for '$targetLocale'")

        // Report if we fell back to base text for strings
        val stringFallbackCount = comparison.missingStrings.count { key ->
            stringTranslations[key]?.isBlank() ?: true
        }
        val pluralFallbackCount = pluralWorkItems.count { (fullId, _) ->
            pluralTranslations[fullId]?.isBlank() ?: true
        }
        val totalFallback = stringFallbackCount + pluralFallbackCount
        if (totalFallback > 0) {
            logger.warn("⚠ $totalFallback entries used base text as fallback (API limits or errors)")
            logger.warn("  Run again later with API key to translate, or edit strings.xml manually")
        }
    }

    private fun tryAITranslation(
        stringWorkItems: List<Pair<String, String>>,
        baseLocale: String,
        targetLocale: String,
        apiKey: String,
        provider: LLMProvider,
        model: LLmModel,
        workerClasspath: FileCollection
    ): Map<String, String> {
        val resultsFile = File(outputDirectory.get().asFile, "translations_${targetLocale}.json")
        val inputFile = File(outputDirectory.get().asFile, "translation_input_${targetLocale}.json")

        // Clean up any stale results file to prevent using cached results from previous runs
        if (resultsFile.exists()) {
            resultsFile.delete()
        }

        val inputJson = buildJsonObject {
            put("apiKey", JsonPrimitive(apiKey))
            put("provider", JsonPrimitive(provider.name))
            put("model", JsonPrimitive(model.name))
            put("baseLocale", JsonPrimitive(baseLocale))
            put("targetLocale", JsonPrimitive(targetLocale))
            put("texts", buildJsonArray { stringWorkItems.forEach { add(JsonPrimitive(it.second)) } })
            put("keys", buildJsonArray { stringWorkItems.forEach { add(JsonPrimitive(it.first)) } })
        }
        inputFile.parentFile?.mkdirs()
        inputFile.writeText(inputJson.toString())

        // Run translation in a plain forked `java` process - see comment in run().
        execOperations.javaexec { spec ->
            spec.classpath = workerClasspath
            spec.mainClass.set("org.multipaz.lokalize.worker.TranslationWorkerMainKt")
            spec.args = listOf(inputFile.absolutePath, resultsFile.absolutePath)
        }

        // Collect all translations from results files
        val allTranslations = mutableMapOf<String, String>()
        if (resultsFile.exists()) {
            try {
                val content = resultsFile.readText()
                Json.parseToJsonElement(content)
                    .jsonObject.forEach { entry ->
                        allTranslations[entry.key] = entry.value.jsonPrimitive.content
                    }
            } catch (e: Exception) {
                logger.warn("Failed to read translation results: ${e.message}")
            }
        } else {
            logger.warn("Translation results file not found - API may have failed")
        }

        return allTranslations
    }
}
