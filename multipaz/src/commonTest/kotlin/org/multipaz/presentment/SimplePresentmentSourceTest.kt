package org.multipaz.presentment

import kotlinx.coroutines.test.runTest
import org.multipaz.prompt.promptModelSilentConsent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SimplePresentmentSourceTest {

    @Test
    fun testGetFaceMatchingModeDefault() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            showConsentPromptFn = ::promptModelSilentConsent,
        )

        val cred = harness.docMdl.getCredentials().first()
        assertEquals(FaceMatchingMode.ONLY_IF_REQUESTED, source.getFaceMatchingMode(cred))
    }

    @Test
    fun testGetFaceMatchingModeCustom() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        var queriedCred: Any? = null
        val sourceNever = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            showConsentPromptFn = ::promptModelSilentConsent,
            getFaceMatchingModeFn = { credential ->
                queriedCred = credential
                FaceMatchingMode.NEVER
            }
        )

        val cred = harness.docMdl.getCredentials().first()
        assertEquals(FaceMatchingMode.NEVER, sourceNever.getFaceMatchingMode(cred))
        assertSame(cred, queriedCred)

        val sourceAlways = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            showConsentPromptFn = ::promptModelSilentConsent,
            getFaceMatchingModeFn = { FaceMatchingMode.ALWAYS }
        )
        assertEquals(FaceMatchingMode.ALWAYS, sourceAlways.getFaceMatchingMode(cred))
    }
}
