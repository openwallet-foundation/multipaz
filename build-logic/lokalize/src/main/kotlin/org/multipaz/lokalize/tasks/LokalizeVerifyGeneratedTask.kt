package org.multipaz.lokalize.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.multipaz.lokalize.engine.StringsGenerator
import org.multipaz.lokalize.util.OutputFormat
import java.io.File

/**
 * Fails the build when the checked-in generated sources no longer match the JSON string resources.
 *
 * Because compiling no longer regenerates the sources, nothing else would notice a `strings.json`
 * edit that was committed without re-running `generateMultipazStrings`. This task restores that
 * guarantee at `check` time: it renders the sources in memory and compares them against what is
 * checked in, without touching the source tree.
 */
@CacheableTask
abstract class LokalizeVerifyGeneratedTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedDir: DirectoryProperty

    @get:Input
    abstract val defaultLocale: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val stringKeysPackageName: Property<String>

    @get:Input
    abstract val generatedClassName: Property<String>

    @get:Input
    abstract val outputFormat: Property<OutputFormat>

    /** Gradle path of the owning project, used to spell out the fix command in failures. */
    @get:Input
    abstract val projectPath: Property<String>

    /** Marker so the task can be up-to-date checked; holds no meaningful content. */
    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    init {
        description = "Verifies the checked-in generated strings match the JSON resources"
        group = "verification"
    }

    @TaskAction
    fun verify() {
        if (outputFormat.get() != OutputFormat.JSON) {
            return
        }

        val expected = StringsGenerator(
            defaultLocale = defaultLocale.get(),
            translationsPackageName = packageName.get(),
            stringKeysPackageName = stringKeysPackageName.get(),
            generatedClassName = generatedClassName.get()
        ).generate(resourcesDir.get().asFile) ?: return

        val generatedRoot = generatedDir.get().asFile
        val actualPaths = generatedRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(generatedRoot).invariantSeparatorsPath }
            .toSortedSet()

        val missing = expected.filesByRelativePath.keys - actualPaths
        val stale = actualPaths - expected.filesByRelativePath.keys
        val changed = expected.filesByRelativePath
            .filterKeys { it in actualPaths }
            .filter { (path, content) -> File(generatedRoot, path).readText() != content }
            .keys

        if (missing.isEmpty() && stale.isEmpty() && changed.isEmpty()) {
            stampFile.get().asFile.apply {
                parentFile.mkdirs()
                writeText("ok")
            }
            logger.lifecycle("✓ Generated strings are up to date (${expected.languages.size} languages)")
            return
        }

        throw GradleException(
            buildString {
                appendLine()
                appendLine("=".repeat(60))
                appendLine("Generated strings are out of date in ${projectPath.get()}")
                appendLine("=".repeat(60))
                appendLine()
                appendLine("The checked-in sources under ${generatedRoot.absolutePath}")
                appendLine("no longer match ${resourcesDir.get().asFile.absolutePath}.")
                appendLine()
                missing.forEach { appendLine("  • missing:   $it") }
                changed.forEach { appendLine("  • outdated:  $it") }
                stale.forEach { appendLine("  • unexpected: $it") }
                appendLine()
                appendLine("-".repeat(60))
                appendLine("Run './gradlew ${projectPath.get()}:generateMultipazStrings' and commit the result")
                appendLine("-".repeat(60))
            }
        )
    }
}
