package org.multipaz.lokalize.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.multipaz.lokalize.util.LLMProvider
import org.multipaz.lokalize.util.LLmModel

/**
 * Convention plugin for Lokalize configuration.
 *
 * This plugin applies the base Lokalize plugin with common configuration,
 * allowing modules to only specify their resourcesDir if needed.
 *
 * Common settings (defaultLocale, targetLocales, LLM settings) are applied here.
 * Module-specific settings (resourcesDir) should be set in the module's build.gradle.kts.
 *
 * Usage:
 * ```kotlin
 * plugins {
 *     id("org.multipaz.lokalize.convention")
 * }
 *
 * lokalize {
 *     // Optional: only override if different from convention
 *     resourcesDir.set("src/androidMain/res")
 * }
 * ```
 */
class LokalizeConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {

        target.pluginManager.apply("org.multipaz.lokalize")

        // Configure the lokalize extension with common defaults
        target.extensions.configure(org.multipaz.lokalize.util.LokalizeExtension::class.java) { ext ->
            ext.defaultLocale = "en"
            ext.targetLocales = listOf(
                "da", "ar", "cs", "de", "el", "es", "fr", "he", "hi", "id",
                "it", "ja", "ko", "nl", "pl", "pt", "ru", "th", "tr", "uk",
                "vi", "zh-rCN"
            )

            ext.llmProvider.set(LLMProvider.GOOGLE)
            ext.llModel.set(LLmModel.GEMINI2_5_FLASH)
        }
    }
}
