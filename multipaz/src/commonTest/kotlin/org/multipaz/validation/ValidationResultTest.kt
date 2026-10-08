package org.multipaz.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationResultTest {

    @Test
    fun testEmptyResult() {
        val result = ValidationResult.SUCCESS
        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
        assertTrue(result.findings.isEmpty())
        result.checkNoErrors() // Should not throw
    }

    @Test
    fun testFindingsAndFiltering() {
        val finding1 = ValidationFinding(ValidationSeverity.WARNING, "Warning message")
        val finding2 = ValidationFinding(ValidationSeverity.ERROR, "Error message")

        assertTrue(finding1.isWarning)
        assertFalse(finding1.isError)
        assertTrue(finding2.isError)
        assertFalse(finding2.isWarning)

        val result = ValidationResult(listOf(finding1, finding2))
        assertTrue(result.hasErrors)
        assertTrue(result.hasWarnings)
        assertEquals(listOf(finding2), result.errors)
        assertEquals(listOf(finding1), result.warnings)

        val exception = assertFailsWith<IllegalStateException> {
            result.checkNoErrors()
        }
        assertTrue(exception.message!!.contains("Error message"))
    }

    @Test
    fun testBuilderDsl() {
        val result = buildValidationResult {
            addWarning("Warning 1")
            addError("Error 1")
            add(ValidationSeverity.WARNING, "Warning 2")
            add(ValidationSeverity.ERROR, "Error 2")
        }

        assertEquals(4, result.findings.size)
        assertEquals(2, result.warnings.size)
        assertEquals(2, result.errors.size)
    }

    @Test
    fun testCombineResults() {
        val result1 = buildValidationResult { addWarning("Warning 1") }
        val result2 = buildValidationResult { addError("Error 1") }
        val combined = result1 + result2

        assertEquals(2, combined.findings.size)
        assertTrue(combined.hasWarnings)
        assertTrue(combined.hasErrors)
    }
}
