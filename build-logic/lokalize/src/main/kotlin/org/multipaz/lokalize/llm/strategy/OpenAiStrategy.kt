package org.multipaz.lokalize.llm.strategy

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.llm.LLModel
import org.multipaz.lokalize.util.LLmModel

/**
 * OpenAI provider strategy for model resolution and executor creation.
 */
class OpenAiStrategy : ProviderStrategy {

    override val providerName: String = "OpenAI"

    override fun resolveKoogModel(model: LLmModel): LLModel = when (model) {
        LLmModel.GPT4O -> OpenAIModels.Chat.GPT4o
        LLmModel.GPT4O_MINI -> OpenAIModels.Chat.GPT4oMini
        LLmModel.GPT4_1 -> OpenAIModels.Chat.GPT4_1
        LLmModel.GPT4_1_NANO -> OpenAIModels.Chat.GPT4_1Nano
        LLmModel.GPT4_1_MINI -> OpenAIModels.Chat.GPT4_1Mini
        LLmModel.O1 -> OpenAIModels.Chat.O1
        LLmModel.O3 -> OpenAIModels.Chat.O3
        LLmModel.O3_MINI -> OpenAIModels.Chat.O3Mini
        LLmModel.O4_MINI -> OpenAIModels.Chat.O4Mini
        LLmModel.GPT5 -> OpenAIModels.Chat.GPT5
        LLmModel.GPT5_MINI -> OpenAIModels.Chat.GPT5Mini
        LLmModel.GPT5_NANO -> OpenAIModels.Chat.GPT5Nano
        LLmModel.GPT5_CODEX -> OpenAIModels.Chat.GPT5Codex
        LLmModel.GPT5_PRO -> OpenAIModels.Chat.GPT5Pro
        LLmModel.GPT5_1 -> OpenAIModels.Chat.GPT5_1
        LLmModel.GPT5_1_CODEX -> OpenAIModels.Chat.GPT5_1Codex
        LLmModel.GPT5_1_CODEX_MAX -> OpenAIModels.Chat.GPT5_1CodexMax
        LLmModel.GPT5_2 -> OpenAIModels.Chat.GPT5_2
        LLmModel.GPT5_2_PRO -> OpenAIModels.Chat.GPT5_2Pro
        LLmModel.GPT5_2_CODEX -> OpenAIModels.Chat.GPT5_2Codex
        LLmModel.GPT5_3_CODEX -> OpenAIModels.Chat.GPT5_3Codex
        LLmModel.GPT5_4 -> OpenAIModels.Chat.GPT5_4
        LLmModel.GPT5_4_MINI -> OpenAIModels.Chat.GPT5_4Mini
        LLmModel.GPT5_4_NANO -> OpenAIModels.Chat.GPT5_4Nano
        LLmModel.GPT5_4_PRO -> OpenAIModels.Chat.GPT5_4Pro
        else -> error("Unsupported OpenAI model: $model")
    }

    override fun createExecutor(apiKey: String) = simpleOpenAIExecutor(apiKey)
}
