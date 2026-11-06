package org.multipaz.compose.presentment

import java.io.File

internal data class GoldenStepRecord(
    val stepId: String,
    val label: String,
    val relativePath: String,
)

internal data class GoldenScenarioRecord(
    val categoryDir: String,
    val categoryTitle: String,
    val categoryDescription: String,
    val scenarioId: String,
    val title: String,
    val requesterSummary: String,
    val protocolSummary: String,
    val riskBadge: String,
    val description: String,
    val steps: List<GoldenStepRecord>,
)

internal object ConsentGoldenCatalogGenerator {

    fun generate(rootDir: File, scenarios: List<GoldenScenarioRecord>) {
        rootDir.mkdirs()
        File(rootDir, "index.html").delete()
        val swiftUiGoldenDir = File(
            rootDir.parentFile?.parentFile ?: File("."),
            "multipaz-swiftui/golden-screenshots"
        )
        File(rootDir, "README.md").writeText(buildReadmeMarkdown(scenarios, swiftUiGoldenDir))
    }

    private fun buildReadmeMarkdown(
        scenarios: List<GoldenScenarioRecord>,
        swiftUiGoldenDir: File,
    ): String = buildString {
        val byCategory = scenarios.groupBy { it.categoryDir }
        appendLine("# Multipaz Consent Prompt — Golden Master Screenshot Catalog")
        appendLine()
        appendLine("This directory contains the golden-master reference screenshots for the")
        appendLine("[`Consent`](../src/commonMain/kotlin/org/multipaz/compose/presentment/Consent.kt) composable")
        appendLine("in `:multipaz-compose` (captured deterministically by")
        appendLine("[`ConsentGoldenScreenshotTest`](../src/androidUnitTest/kotlin/org/multipaz/compose/presentment/ConsentGoldenScreenshotTest.kt)")
        appendLine("using Robolectric Native Graphics) alongside the native iOS SwiftUI")
        appendLine("[`Consent`](../../multipaz-swiftui/src/iosMain/swift/Consent.swift) view in `:multipaz-swiftui`")
        appendLine("(stored in [`multipaz-swiftui/golden-screenshots/`](../../multipaz-swiftui/golden-screenshots/)).")
        appendLine()
        appendLine("- **Run Compose golden verification tests:** `./gradlew :multipaz-compose:testDebugUnitTest`")
        appendLine("- **Regenerate Compose goldens & catalog:** `./gradlew :multipaz-compose:testDebugUnitTest -PupdateGoldens=true`")
        appendLine("- **Regenerate Android 17 QPR2+ EmbeddedBiometricPrompt goldens (manual):** `./multipaz-compose/golden-screenshots/update-biometric-goldens.sh`")
        appendLine()
        appendLine("## Categories")
        appendLine()
        for ((catDir, items) in byCategory) {
            val first = items.first()
            appendLine("- **[${first.categoryTitle}](#$catDir)** (`$catDir` — ${items.size} scenarios)")
            for (scenario in items) {
                appendLine("  - [${scenario.title}](#${scenario.scenarioId})")
            }
        }
        appendLine()

        for ((catDir, items) in byCategory) {
            val first = items.first()
            appendLine("---")
            appendLine()
            appendLine("<a id=\"$catDir\"></a>")
            appendLine()
            appendLine("## ${first.categoryTitle}")
            appendLine()
            appendLine(first.categoryDescription)
            appendLine()

            for (scenario in items) {
                appendLine("<a id=\"${scenario.scenarioId}\"></a>")
                appendLine()
                appendLine("### `${scenario.scenarioId}` — ${scenario.title}")
                appendLine()
                appendLine("- **Relying Party:** ${scenario.requesterSummary}")
                appendLine("- **Protocol / Credentials:** ${scenario.protocolSummary}")
                appendLine("- **Risk Assessment:** `${scenario.riskBadge}`")
                appendLine()
                appendLine(scenario.description)
                appendLine()
                val hasSwiftUi = scenario.steps.any { File(swiftUiGoldenDir, it.relativePath).exists() }
                val stepHeaders = scenario.steps.joinToString(" | ") { "`${it.stepId}` — ${it.label}" }
                val stepAlignments = scenario.steps.joinToString(" | ") { ":---:" }
                val composeImages = scenario.steps.joinToString(" | ") {
                    "<img src=\"${it.relativePath}\" alt=\"Compose: ${escapeHtmlAttr(scenario.title)} - ${escapeHtmlAttr(it.label)}\" width=\"260\" />"
                }
                if (hasSwiftUi) {
                    val swiftUiImages = scenario.steps.joinToString(" | ") {
                        val swiftFile = File(swiftUiGoldenDir, it.relativePath)
                        if (swiftFile.exists()) {
                            "<img src=\"../../multipaz-swiftui/golden-screenshots/${it.relativePath}\" alt=\"SwiftUI: ${escapeHtmlAttr(scenario.title)} - ${escapeHtmlAttr(it.label)}\" width=\"260\" />"
                        } else {
                            "—"
                        }
                    }
                    appendLine("| | $stepHeaders |")
                    appendLine("| :---: | $stepAlignments |")
                    appendLine("| **Compose** | $composeImages |")
                    appendLine("| **SwiftUI** | $swiftUiImages |")
                } else {
                    appendLine("| $stepHeaders |")
                    appendLine("| $stepAlignments |")
                    appendLine("| $composeImages |")
                }
                appendLine()
            }
        }
    }

    private fun escapeHtmlAttr(text: String): String = text
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("|", "&#124;")
}
