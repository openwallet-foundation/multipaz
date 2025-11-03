package org.multipaz.mdoc.zkp.longfellow

import org.junit.Assert.assertThrows
import kotlin.test.assertEquals
import org.junit.Test

internal class LongfellowNativesTest {
    /* commented out for now for failures, needs investigation
    @Test
    fun testFullVerificationFlow_success() {
        // Proof generation attributes match proof verification attributes.
        val verifierCodeEnum = LongfellowNativeTestsCommon.runFullVerification(
            MdocTestDataProvider.getAgeOver18Attributes(true),
            MdocTestDataProvider.getAgeOver18Attributes(true)
        )

        assertEquals(VerifierCodeEnum.MDOC_VERIFIER_SUCCESS, verifierCodeEnum)
    }

    @Test
    fun testFullVerificationFlow_failVerificationAttributesChanged() {
        val verifierCodeEnum = LongfellowNativeTestsCommon.runFullVerification(
            MdocTestDataProvider.getAgeOver18Attributes(true),
            MdocTestDataProvider.getAgeOver18Attributes(false)
        )

        assertEquals(VerifierCodeEnum.MDOC_VERIFIER_GENERAL_FAILURE, verifierCodeEnum)
    }

    @Test
    fun testFullVerificationFlow_failProofGenerationDoesNotMatchDoc() {
        // The test MDoc has value age_over_18 set to true, so generating a proof should fail.
        assertThrows(ProofGenerationException::class.java) {
            LongfellowNativeTestsCommon.runFullVerification(
                MdocTestDataProvider.getAgeOver18Attributes(false),
                MdocTestDataProvider.getAgeOver18Attributes(false)
            )
        }
    }
     */
}
