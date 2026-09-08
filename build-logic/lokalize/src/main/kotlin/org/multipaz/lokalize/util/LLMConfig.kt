package org.multipaz.lokalize.util

/**
 * Supported LLM providers for translation.
 */
enum class LLMProvider {
    OPENAI,
    GOOGLE,
    ANTHROPIC
}

/**
 * Available LLM models for translation.
 * Models are organized by provider and capability.
 */
enum class LLmModel {
    // ==================== OPENAI MODELS ====================

    // GPT-4o Series - Balanced performance and cost
    /** GPT-4o - Versatile, high-intelligence flagship model */
    GPT4O,

    /** GPT-4o Mini - Smaller, cost-effective version of GPT-4o */
    GPT4O_MINI,

    // GPT-4.1 Series - Complex tasks
    /** GPT-4.1 - Model for complex tasks across domains */
    GPT4_1,

    /** GPT-4.1 Nano - Fastest, most affordable in GPT-4.1 family */
    GPT4_1_NANO,

    /** GPT-4.1 Mini - Balance of intelligence, speed, and cost */
    GPT4_1_MINI,

    // O Series - Reasoning models
    /** O1 - Reasoning model for complex, multi-step tasks */
    O1,

    /** O3 - Advanced reasoning model */
    O3,

    /** O3 Mini - Smaller, affordable reasoning model */
    O3_MINI,

    /** O4 Mini - Fast, effective reasoning for coding/visual tasks */
    O4_MINI,

    // GPT-5 Series - Latest flagship models
    /** GPT-5 - Flagship for coding, reasoning, and agentic tasks */
    GPT5,

    /** GPT-5 Mini - Faster, cost-efficient version of GPT-5 */
    GPT5_MINI,

    /** GPT-5 Nano - Fastest, most cost-efficient GPT-5 */
    GPT5_NANO,

    /** GPT-5 Codex - GPT-5 tuned for coding/agentic tasks */
    GPT5_CODEX,

    /** GPT-5 Pro - Most advanced reasoning model */
    GPT5_PRO,

    // GPT-5.1 Series
    /** GPT-5.1 - Flagship with configurable reasoning */
    GPT5_1,

    /** GPT-5.1 Codex - GPT-5.1 tuned for coding/agentic tasks */
    GPT5_1_CODEX,

    /** GPT-5.1 Codex Max - Highest-capability GPT-5.1 Codex variant */
    GPT5_1_CODEX_MAX,

    // GPT-5.2 Series
    /** GPT-5.2 - Flagship for coding and agentic tasks */
    GPT5_2,

    /** GPT-5.2 Pro - Most advanced GPT-5.2 reasoning model */
    GPT5_2_PRO,

    /** GPT-5.2 Codex - GPT-5.2 tuned for coding/agentic tasks */
    GPT5_2_CODEX,

    // GPT-5.3 Series
    /** GPT-5.3 Codex - GPT-5.3 tuned for coding/agentic tasks */
    GPT5_3_CODEX,

    // GPT-5.4 Series
    /** GPT-5.4 - Flagship for coding, reasoning, and agentic tasks */
    GPT5_4,

    /** GPT-5.4 Mini - Faster, cost-efficient version of GPT-5.4 */
    GPT5_4_MINI,

    /** GPT-5.4 Nano - Fastest, most cost-efficient GPT-5.4 */
    GPT5_4_NANO,

    /** GPT-5.4 Pro - Most advanced GPT-5.4 reasoning model */
    GPT5_4_PRO,

    // ==================== GOOGLE (GEMINI) MODELS ====================

    // Gemini 2.5 Series
    /** Gemini 2.5 Pro - Advanced capabilities for complex tasks */
    GEMINI2_5_PRO,

    /** Gemini 2.5 Flash - Balance of speed and capability */
    GEMINI2_5_FLASH,

    /** Gemini 2.5 Flash Lite - Cost-efficient and high throughput */
    GEMINI2_5_FLASH_LITE,

    // Gemini 3 Series
    /** Gemini 3 Pro Preview - Advanced reasoning with thinking_level control */
    GEMINI3_PRO_PREVIEW,

    /** Gemini 3 Flash Preview - Pro-level intelligence at Flash speed/pricing */
    GEMINI3_FLASH_PREVIEW,

    // ==================== ANTHROPIC (CLAUDE) MODELS ====================

    // Claude 4 Series
    /** Claude Sonnet 4 - High-performance with exceptional reasoning */
    CLAUDE_SONNET_4,

    /** Claude Opus 4 - Previous flagship, very high intelligence */
    CLAUDE_OPUS_4,

    /** Claude Opus 4.1 - Exceptional for specialized complex tasks */
    CLAUDE_OPUS_4_1,

    // Claude 4.5 Series
    /** Claude Opus 4.5 - Premium model combining intelligence with performance */
    CLAUDE_OPUS_4_5,

    /** Claude Sonnet 4.5 - Best for complex agents and coding */
    CLAUDE_SONNET_4_5,

    /** Claude Haiku 4.5 - Near-frontier intelligence at blazing speeds */
    CLAUDE_HAIKU_4_5,

    // Claude 4.6 Series
    /** Claude Sonnet 4.6 - Best combination of speed and intelligence */
    CLAUDE_SONNET_4_6,

    /** Claude Opus 4.6 - Frontier model for engineering, agentic and knowledge work */
    CLAUDE_OPUS_4_6,
}

/**
 * Maps an LLmModel to its provider.
 */
fun LLmModel.toProvider(): LLMProvider = when (this) {
    // OpenAI models
    LLmModel.GPT4O,
    LLmModel.GPT4O_MINI,
    LLmModel.GPT4_1,
    LLmModel.GPT4_1_NANO,
    LLmModel.GPT4_1_MINI,
    LLmModel.O1,
    LLmModel.O3,
    LLmModel.O3_MINI,
    LLmModel.O4_MINI,
    LLmModel.GPT5,
    LLmModel.GPT5_MINI,
    LLmModel.GPT5_NANO,
    LLmModel.GPT5_CODEX,
    LLmModel.GPT5_PRO,
    LLmModel.GPT5_1,
    LLmModel.GPT5_1_CODEX,
    LLmModel.GPT5_1_CODEX_MAX,
    LLmModel.GPT5_2,
    LLmModel.GPT5_2_PRO,
    LLmModel.GPT5_2_CODEX,
    LLmModel.GPT5_3_CODEX,
    LLmModel.GPT5_4,
    LLmModel.GPT5_4_MINI,
    LLmModel.GPT5_4_NANO,
    LLmModel.GPT5_4_PRO -> LLMProvider.OPENAI

    // Google models
    LLmModel.GEMINI2_5_PRO,
    LLmModel.GEMINI2_5_FLASH,
    LLmModel.GEMINI2_5_FLASH_LITE,
    LLmModel.GEMINI3_PRO_PREVIEW,
    LLmModel.GEMINI3_FLASH_PREVIEW -> LLMProvider.GOOGLE

    // Anthropic models
    LLmModel.CLAUDE_SONNET_4,
    LLmModel.CLAUDE_OPUS_4,
    LLmModel.CLAUDE_OPUS_4_1,
    LLmModel.CLAUDE_OPUS_4_5,
    LLmModel.CLAUDE_SONNET_4_5,
    LLmModel.CLAUDE_HAIKU_4_5,
    LLmModel.CLAUDE_SONNET_4_6,
    LLmModel.CLAUDE_OPUS_4_6 -> LLMProvider.ANTHROPIC
}
