package org.multipaz.lokalize.worker

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.lokalize.service.KoogTranslationService
import org.multipaz.lokalize.service.TranslationService
import org.multipaz.lokalize.util.LLMProvider
import org.multipaz.lokalize.util.LLmModel
import java.io.File
import kotlin.system.exitProcess

/**
 * Standalone entry point run in a plain forked `java` process (see
 * LokalizeTranslateTask.tryAITranslation), instead of Gradle's WorkerExecutor.
 *
 * Gradle's Worker API forks a process but still layers the worker classpath as a
 * child of Gradle's own classloader, which leaks `kotlin.*` packages from Gradle's
 * bundled (older) kotlin-stdlib. Koog calls kotlin.time.Clock.System.now() on every
 * request, which needs a kotlin-stdlib newer than the one Gradle bundles, so that
 * leak causes a NoSuchMethodError. A plain forked process has no such parent classloader.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: TranslationWorkerMain <inputFile> <resultsFile>" }
    val inputFile = File(args[0])
    val resultsFile = File(args[1])

    val input = Json.parseToJsonElement(inputFile.readText()).jsonObject
    val apiKey = input.getValue("apiKey").jsonPrimitive.content
    val provider = LLMProvider.valueOf(input.getValue("provider").jsonPrimitive.content)
    val model = LLmModel.valueOf(input.getValue("model").jsonPrimitive.content)
    val baseLocale = input.getValue("baseLocale").jsonPrimitive.content
    val targetLocale = input.getValue("targetLocale").jsonPrimitive.content
    val texts = input.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
    val keys = input.getValue("keys").jsonArray.map { it.jsonPrimitive.content }

    if (apiKey.isBlank()) {
        println("[Worker] No API key provided - skipping AI translation")
        writeResults(resultsFile, emptyMap())
        return
    }

    println("[Worker] Translating ${texts.size} strings from $baseLocale to $targetLocale using ${provider.name}")

    val translationService: TranslationService = KoogTranslationService(
        apiKey = apiKey,
        provider = provider,
        model = model
    )

    val translations = try {
        runBlocking {
            translationService.translate(
                texts = texts,
                keys = keys,
                sourceLocale = baseLocale,
                targetLocale = targetLocale
            )
        }
    } catch (e: Exception) {
        val isRateLimit = e.message?.contains("429") == true ||
            e.message?.contains("RESOURCE_EXHAUSTED") == true ||
            e.message?.contains("quota") == true ||
            e.message?.contains("limit: 0") == true

        if (isRateLimit) {
            println("[Worker] ⚠ API quota exhausted (429) - translation failed")
            println("[Worker]   Get a new API key or wait 24h for quota reset")
        } else {
            println("[Worker] ⚠ Translation failed: ${e.message}")
            e.printStackTrace()
        }
        System.err.println("Translation failed for locale '$targetLocale': ${e.message}")
        exitProcess(1)
    }

    val finalTranslations = mutableMapOf<String, String>()
    keys.forEachIndexed { index, key ->
        finalTranslations[key] = translations[key] ?: texts[index]
    }

    println(
        "[Worker] Completed ${finalTranslations.size} entries " +
            "(AI: ${translations.size}, fallback: ${texts.size - translations.size})"
    )
    writeResults(resultsFile, finalTranslations)
}

private fun writeResults(resultsFile: File, translations: Map<String, String>) {
    resultsFile.parentFile?.mkdirs()
    val json = buildJsonObject {
        translations.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }
    resultsFile.writeText(json.toString())
}
