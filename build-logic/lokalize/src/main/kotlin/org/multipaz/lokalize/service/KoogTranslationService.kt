package org.multipaz.lokalize.service

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import kotlinx.coroutines.delay
import org.multipaz.lokalize.llm.strategy.ProviderStrategyFactory
import org.multipaz.lokalize.util.LLMProvider
import org.multipaz.lokalize.util.LLmModel

/**
 * Implementation of TranslationService using Koog AI framework.
 *
 * Architecture:
 * - BatchTranslator: Handles batching and rate limiting
 * - TranslationResult: Sealed class for success/failure outcomes
 * - PromptBuilder: Generates system prompts
 * - ResultParser: Parses API responses
 */
class KoogTranslationService(
    private val apiKey: String,
    private val provider: LLMProvider,
    private val model: LLmModel
) : TranslationService {

    override suspend fun translate(
        texts: List<String>,
        keys: List<String>,
        sourceLocale: String,
        targetLocale: String
    ): Map<String, String> {
        val translator = BatchTranslator.create(apiKey, provider, model)
        return translator.translate(texts, keys, sourceLocale, targetLocale)
    }
}

/**
 * Sealed class representing translation outcomes for a batch.
 */
private sealed class TranslationResult {
    data class Success(val translations: Map<String, String>) : TranslationResult()
    data class RateLimited(
        val partialResults: Map<String, String>,
        val cause: Throwable
    ) : TranslationResult()

    data class Failed(
        val fallback: Map<String, String>,
        val cause: Throwable
    ) : TranslationResult()
}

/**
 * Handles batch translation with rate limiting and error handling.
 */
private class BatchTranslator(
    private val executor: ai.koog.prompt.executor.llms.SingleLLMPromptExecutor,
    private val koogModel: ai.koog.prompt.llm.LLModel,
    private val batchSize: Int
) {
    companion object {
        fun create(
            apiKey: String,
            provider: LLMProvider,
            model: LLmModel
        ): BatchTranslator {
            val strategy = ProviderStrategyFactory.create(provider)
            val koogModel = strategy.resolveKoogModel(model)
            val executor = strategy.createExecutor(apiKey)

            val batchSize = 10

            return BatchTranslator(executor, koogModel, batchSize)
        }
    }

    suspend fun translate(
        texts: List<String>,
        keys: List<String>,
        sourceLocale: String,
        targetLocale: String
    ): Map<String, String> {
        val batches = createBatches(texts, keys)
        val allResults = mutableMapOf<String, String>()

        batches.forEachIndexed { index, batch ->
            val result = translateBatch(batch, sourceLocale, targetLocale, index, batches.size)
            when (result) {
                is TranslationResult.Success -> allResults.putAll(result.translations)
                is TranslationResult.RateLimited -> {
                    throw RuntimeException(
                        "API rate limit exceeded for locale '$targetLocale'. " +
                                "${result.partialResults.size}/${batch.keys.size} translations completed. " +
                                "Get a new API key or wait 24h for quota reset.",
                        result.cause
                    )
                }

                is TranslationResult.Failed -> {
                    throw RuntimeException(
                        "Translation failed for locale '$targetLocale': " +
                                "${batch.keys.joinToString()} - ${describe(result.cause)}",
                        result.cause
                    )
                }
            }
        }

        return allResults
    }

    private fun createBatches(texts: List<String>, keys: List<String>): List<TranslationBatch> {
        val effectiveBatchSize = if (batchSize == Int.MAX_VALUE) texts.size else batchSize
        return texts.chunked(effectiveBatchSize)
            .zip(keys.chunked(effectiveBatchSize))
            .map { (texts, keys) -> TranslationBatch(texts, keys) }
    }

    private suspend fun translateBatch(
        batch: TranslationBatch,
        sourceLocale: String,
        targetLocale: String,
        batchIndex: Int,
        totalBatches: Int
    ): TranslationResult {
        return try {
            val agent = AIAgent(
                promptExecutor = executor,
                llmModel = koogModel,
                systemPrompt = PromptBuilder.build(
                    sourceLocale,
                    targetLocale,
                    batch.texts,
                    batch.keys
                ),
                strategy = singleRunStrategy()
            )

            val response = agent.run("").trim()
            val translations = ResultParser.parse(response, batch.keys, batch.texts)

            addDelayIfNeeded(batchIndex, totalBatches)

            TranslationResult.Success(translations)
        } catch (e: Exception) {
            handleError(e, batch)
        }
    }

    private fun handleError(e: Exception, batch: TranslationBatch): TranslationResult {
        return when {
            isRateLimit(e) -> TranslationResult.RateLimited(batch.fallback(), e)
            else -> TranslationResult.Failed(batch.fallback(), e)
        }
    }

    /**
     * Detects quota/rate-limit errors anywhere in the exception chain.
     *
     * Koog wraps transport errors, so the HTTP status only shows up on a nested cause and
     * checking just [Throwable.message] misclassifies quota errors as generic failures.
     */
    private fun isRateLimit(e: Throwable): Boolean =
        e.causeChain().any { throwable ->
            val msg = throwable.message ?: return@any false
            msg.contains("429") ||
                    msg.contains("RESOURCE_EXHAUSTED") ||
                    msg.contains("quota") ||
                    msg.contains("limit: 0")
        }

    private suspend fun addDelayIfNeeded(currentIndex: Int, totalBatches: Int) {
        if (currentIndex >= totalBatches - 1) return

        val delayMs = 3000L
        delay(delayMs)
    }
}

/**
 * Represents a single batch of translations.
 */
private data class TranslationBatch(
    val texts: List<String>,
    val keys: List<String>
) {
    fun fallback(): Map<String, String> = keys.zip(texts).toMap()
}

/**
 * Builds system prompts for translation.
 */
private object PromptBuilder {
    fun build(
        sourceLocale: String,
        targetLocale: String,
        texts: List<String>,
        keys: List<String>
    ): String {
        val items = formatItems(texts, keys)

        return """
            You are a professional translator for mobile app localization.
            
            Translate the following strings from $sourceLocale to $targetLocale.
            Context keys are provided to help understand the UI context.
            
            STRINGS TO TRANSLATE:
            
            $items
            
            RULES:
            1. Translate accurately while maintaining meaning
            2. Keep placeholders (%s, %d, {name}, %1${'$'}s, etc.) unchanged
            3. Keep HTML tags (<b>, <i>, <br>, etc.) unchanged  
            4. Keep XML escapes (&amp;, &lt;, &gt;) unchanged
            5. Be concise - UI strings should be brief
            6. Return ONLY the translations, no key names or explanations
            
            OUTPUT FORMAT:
            Return translations as a numbered list matching the input order:
            1. [translation for item 1]
            2. [translation for item 2]
            3. [translation for item 3]
            
            Example:
            Input: "Hello", "Cancel", "Save %s"
            Output:
            1. Hola
            2. Cancelar  
            3. Guardar %s
        """.trimIndent()
    }

    private fun formatItems(texts: List<String>, keys: List<String>): String {
        return texts.zip(keys).mapIndexed { index, (text, key) ->
            "${index + 1}. Key: $key\n   Text: \"$text\""
        }.joinToString("\n\n")
    }
}

/**
 * Parses translation results from API response.
 */
private object ResultParser {
    fun parse(
        response: String,
        keys: List<String>,
        fallbackTexts: List<String>
    ): Map<String, String> {
        val lines = response.lines()

        return keys.mapIndexed { index, key ->
            val translation =
                extractTranslation(lines, index, fallbackTexts.getOrElse(index) { "" })
            key to translation
        }.toMap()
    }

    private fun extractTranslation(lines: List<String>, index: Int, fallback: String): String {
        val prefix = "${index + 1}."
        val line = lines.find { it.trim().startsWith(prefix) } ?: lines.getOrNull(index)

        return line?.let {
            it.substringAfter(prefix).trim()
                .trim('"')
                .takeIf { t -> t.isNotBlank() }
        } ?: fallback
    }
}

/** Upper bound on how far [causeChain] walks, so a cyclic chain cannot spin forever. */
private const val MAX_CAUSE_DEPTH = 10

/**
 * Returns this throwable followed by its causes, stopping at [MAX_CAUSE_DEPTH] or a repeat.
 */
private fun Throwable.causeChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && current !in chain && chain.size < MAX_CAUSE_DEPTH) {
        chain.add(current)
        current = current.cause
    }
    return chain
}

/**
 * Renders a throwable and its causes on one line.
 *
 * The worker process reports failures via the exception message, so without this the
 * underlying provider error (HTTP status, API explanation) never reaches Gradle's output.
 */
private fun describe(e: Throwable): String =
    e.causeChain().joinToString(" <- ") { throwable ->
        "${throwable::class.simpleName}: ${throwable.message ?: "(no message)"}"
    }
