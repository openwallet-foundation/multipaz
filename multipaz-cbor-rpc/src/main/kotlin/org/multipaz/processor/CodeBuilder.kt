package org.multipaz.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * A class to simplify Kotlin code generation.
 *
 * This is a simple, but somewhat more Kotlinized alternative to Kotlin Poet (which is basically
 * a Java Poet port). Kotlin code generation is still emerging it seems, and better libraries
 * may exist in the future. Consider them before adding too much functionality to this class.
 */
class CodeBuilder(
    private val classesToImport: MutableMap<String, String> = mutableMapOf(), // simple to fully qualified name
    private val functionsToImport: MutableMap<String, MutableSet<String>> = mutableMapOf(),  // function name to package set
    private var indentDepth: Int = 0,
    private val varCounts: MutableMap<String, Int> = mutableMapOf()
) {
    private val code: MutableList<Any> = mutableListOf()

    /**
     * Add and import for a function from a given package.
     */
    fun importFunctionName(function: String, packageName: String) {
        functionsToImport.computeIfAbsent(function) { mutableSetOf() }.add(packageName);
    }

    /**
     * Add given class to import list, simple class name can then be used in the code
     * to refer to it.
     *
     * Duplicate simple names will cause [IllegalArgumentException]
     */
    fun importQualifiedName(clazz: KSClassDeclaration) {
        importQualifiedName(clazz.qualifiedName!!.asString())
    }

    /**
     * Import class or function using its fully-qualified name.
     */
    fun importQualifiedName(qualifiedName: String) {
        val simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
        if (classesToImport.contains(simpleName)) {
            if (classesToImport[simpleName] == qualifiedName) {
                return
            }
            throw IllegalArgumentException("Duplicate simple names (not supported): $simpleName")
        }
        classesToImport[simpleName] = qualifiedName
    }

    /**
     * Reserve a new unique variable name using base name.
     *
     * If a variable given base name was not used before, it is used as is. Otherwise a unique
     * number is appended to it.
     */
    fun varName(base: String = "tmp"): String {
        val count = varCounts[base]
        return if (count == null) {
            varCounts[base] = 0
            base
        } else {
            varCounts[base] = count + 1
            "$base$count"
        }
    }

    /**
     * Creates an insertion point where some code can be added later (until [writeToFile] is
     * called).
     */
    fun insertionPoint(): CodeBuilder {
        val builder = CodeBuilder(
            classesToImport, functionsToImport, indentDepth, varCounts)
        code.add(builder)
        return builder
    }

    /**
     * Appends given code.
     */
    fun append(code: String) {
        this.code.add(code)
    }

    /**
     * Appends given integer.
     */
    fun append(value: Int) {
        code.add(value)
    }

    /**
     * Starts new line indenting it according to the current indent depth.
     */
    fun startLine() {
        repeat(indentDepth) {
            append("    ")
        }
    }

    /**
     * Ends line (appends newline character).
     */
    fun endLine() {
        append("\n")
    }

    /**
     * Executes given lambda with increased indent depth.
     */
    fun withIndent(lambda: CodeBuilder.() -> Unit) {
        indentDepth++
        lambda()
        indentDepth--
    }

    /**
     * Adds a construct that is followed by Kotlin block/lambda (code in curly braces).
     *
     * This generates code that looks like this
     * ```
     * <before> {
     *   <code-generated-by-lambda>
     * }
     * ```
     *
     * When generating `if` statements with `else` or `else if` clauses, set [hasBlockAfter]
     * and [hasBlockBefore] to indicate that extra newlines and indents are not necessary,
     * (this just makes code prettier).
     */
    fun block(
        before: String, hasBlockAfter: Boolean = false, hasBlockBefore: Boolean = false,
        lambdaParameters: String = "",
        lambda: CodeBuilder.() -> Unit
    ) {
        if (!hasBlockBefore) {
            startLine()
        }
        if (before.isNotEmpty()) {
            append(before)
            append(" ")
        }
        if (lambdaParameters.isEmpty()) {
            append("{")
        } else {
            append("{ $lambdaParameters ->")
        }
        endLine()
        withIndent(lambda)
        startLine()
        append("}")
        append(if (hasBlockAfter) " " else "\n")
    }

    /**
     * If [before] is not null, behaves like [block], simply executes [lambda] otherwise.
     */
    fun optionalBlock(before: String?, lambda: CodeBuilder.() -> Unit) {
        if (before != null) {
            block(before, lambda = lambda)
        } else {
            lambda()
        }
    }

    /**
     * Add an empty line to the code.
     */
    fun emptyLine() {
        append("\n")
    }

    /**
     * Add a line with the given code.
     */
    fun line(code: String) {
        startLine()
        append(code)
        endLine()
    }

    fun finishLine(code: String) {
        append(code)
        endLine()
    }

    /**
     * Generate a line of code in a [lambda]
     */
    fun line(lambda: CodeBuilder.() -> Unit) {
        startLine()
        lambda()
        endLine()
    }

    /**
     * Write generated kode to a new source file in context of KSP.
     */
    fun writeToFile(
        codeGenerator: CodeGenerator, dependencies: Dependencies, packageName: String,
        fileName: String
    ) {
        val file = OutputStreamWriter(
            codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = packageName,
                fileName = fileName
            )
        )
        file.write("// This file was generated by ${javaClass.name}, do not edit by hand\n")
        file.write("package $packageName\n\n")
        classesToImport.forEach { (_, qualifiedName) ->
            file.write("import $qualifiedName\n")
        }
        functionsToImport.forEach { (functionName, packageNames) ->
            packageNames.forEach { packageName ->
                file.write("import $packageName.$functionName\n")
            }
        }
        file.write("\n")
        writeCodeTo(file)
        file.close()
    }

    private fun writeCodeTo(out: Writer) {
        for (snippet in code) {
            if (snippet is CodeBuilder) {
                snippet.writeCodeTo(out)
            } else {
                out.write(snippet.toString())
            }
        }
    }
}