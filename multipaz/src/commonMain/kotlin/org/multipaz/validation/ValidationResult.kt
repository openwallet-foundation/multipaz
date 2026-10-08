package org.multipaz.validation

/**
 * Severity level for validation findings.
 */
enum class ValidationSeverity {
    /**
     * A warning indicates a potential issue or non-compliance that may not immediately
     * prevent processing, but could cause interop problems or indicates unexpected data.
     */
    WARNING,

    /**
     * An error indicates a defect or protocol violation that will prevent successful
     * presentation, verification, or cryptographic validation.
     */
    ERROR
}

/**
 * A finding produced during validation.
 *
 * @property severity the severity of the finding.
 * @property message a description of the issue.
 */
data class ValidationFinding(
    val severity: ValidationSeverity,
    val message: String
) {
    /**
     * True if the finding is an error.
     */
    val isError: Boolean get() = severity == ValidationSeverity.ERROR

    /**
     * True if the finding is a warning.
     */
    val isWarning: Boolean get() = severity == ValidationSeverity.WARNING
}

/**
 * Result of performing validation on a data structure or credential.
 *
 * @property findings the list of validation findings.
 */
data class ValidationResult(
    val findings: List<ValidationFinding> = emptyList()
) {
    /**
     * True if there is at least one error finding.
     */
    val hasErrors: Boolean get() = findings.any { it.isError }

    /**
     * True if there is at least one warning finding.
     */
    val hasWarnings: Boolean get() = findings.any { it.isWarning }

    /**
     * All error findings.
     */
    val errors: List<ValidationFinding> get() = findings.filter { it.isError }

    /**
     * All warning findings.
     */
    val warnings: List<ValidationFinding> get() = findings.filter { it.isWarning }

    /**
     * Combines this validation result with another.
     */
    operator fun plus(other: ValidationResult): ValidationResult =
        ValidationResult(findings + other.findings)

    /**
     * Throws [IllegalStateException] if [hasErrors] is true.
     */
    fun checkNoErrors() {
        if (hasErrors) {
            val errorMessages = errors.joinToString("; ") { it.message }
            throw IllegalStateException("Validation failed with errors: $errorMessages")
        }
    }

    companion object {
        /**
         * A successful validation result with no findings.
         */
        val SUCCESS = ValidationResult(emptyList())
    }
}

/**
 * Builder for constructing [ValidationResult] instances.
 */
class ValidationResultBuilder {
    private val findings = mutableListOf<ValidationFinding>()

    /**
     * Adds an error finding.
     */
    fun addError(message: String) {
        findings.add(ValidationFinding(ValidationSeverity.ERROR, message))
    }

    /**
     * Adds a warning finding.
     */
    fun addWarning(message: String) {
        findings.add(ValidationFinding(ValidationSeverity.WARNING, message))
    }

    /**
     * Adds a finding with the specified severity.
     */
    fun add(severity: ValidationSeverity, message: String) {
        findings.add(ValidationFinding(severity, message))
    }

    /**
     * Appends all findings from another [ValidationResult].
     */
    fun addAll(result: ValidationResult) {
        findings.addAll(result.findings)
    }

    /**
     * Builds the [ValidationResult].
     */
    fun build(): ValidationResult = ValidationResult(findings.toList())
}

/**
 * Convenience DSL for building a [ValidationResult].
 */
inline fun buildValidationResult(builderAction: ValidationResultBuilder.() -> Unit): ValidationResult {
    val builder = ValidationResultBuilder()
    builder.builderAction()
    return builder.build()
}
