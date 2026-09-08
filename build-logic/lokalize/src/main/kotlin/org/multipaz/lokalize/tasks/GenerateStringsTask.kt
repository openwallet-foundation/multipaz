package org.multipaz.lokalize.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.multipaz.lokalize.engine.StringsGenerator
import org.multipaz.lokalize.util.OutputFormat
import java.io.File

/**
 * Generates Kotlin constants from JSON string resources.
 *
 * Scans [resourcesDir] for folders starting with "values" and writes Kotlin files with embedded
 * string maps into [outputDir], laid out by package. This bakes the strings into the binary for
 * platforms where file access is unreliable (e.g. iOS).
 *
 * [outputDir] is a checked-in source root, so this task is run on demand — after editing
 * `strings.json` or running `lokalizeFix` — rather than as part of every compile. The generated
 * sources are committed; `lokalizeCheckGenerated` fails the build if they drift out of sync.
 */
@CacheableTask
abstract class GenerateStringsTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDir: DirectoryProperty

    @get:Input
    abstract val defaultLocale: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val stringKeysPackageName: Property<String>

    @get:Input
    abstract val generatedClassName: Property<String>

    @get:Input
    abstract val outputFormat: Property<OutputFormat>

    init {
        description = "Generates Kotlin constants from JSON string resources"
        group = "lokalize"
    }

    @TaskAction
    fun generate() {
        // Only the JSON format bakes strings into Kotlin. XML-format modules (multipaz-compose)
        // read their strings.xml at runtime via Compose Resources instead.
        if (outputFormat.get() != OutputFormat.JSON) {
            logger.lifecycle(
                "Skipping generateMultipazStrings task - output format is '${outputFormat.get().name}', " +
                    "only JSON format is supported"
            )
            return
        }

        val resources = resourcesDir.get().asFile
        val output = outputDir.get().asFile

        val result = StringsGenerator(
            defaultLocale = defaultLocale.get(),
            translationsPackageName = packageName.get(),
            stringKeysPackageName = stringKeysPackageName.get(),
            generatedClassName = generatedClassName.get()
        ).generate(resources)

        if (result == null) {
            logger.lifecycle("Resources directory does not exist: ${resources.absolutePath}")
            return
        }

        // Wiping the root drops files for locales that have been removed. Safe because the root
        // holds nothing but this task's output - see the srcDir wiring in LokalizePlugin.
        output.deleteRecursively()
        result.filesByRelativePath.forEach { (relativePath, content) ->
            val file = File(output, relativePath)
            file.parentFile.mkdirs()
            file.writeText(content)
        }

        logger.lifecycle(
            "Generated ${result.filesByRelativePath.size} files for ${result.languages.size} languages " +
                "in ${output.absolutePath}"
        )
    }
}
