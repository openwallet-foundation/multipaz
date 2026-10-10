package org.multipaz.presentment

import kotlinx.coroutines.test.runTest
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.RequesterIdentity
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
        assertEquals(
            FaceMatchingMode.ONLY_IF_REQUESTED,
            source.getFaceMatchingMode(cred, emptyList())
        )
    }

    @Test
    fun testGetFaceMatchingModeCustom() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()

        var queriedCred: Any? = null
        var queriedIdentities: Any? = null
        val sourceNever = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            showConsentPromptFn = ::promptModelSilentConsent,
            getFaceMatchingModeFn = { credential, requesterIdentities ->
                queriedCred = credential
                queriedIdentities = requesterIdentities
                FaceMatchingMode.NEVER
            }
        )

        val cred = harness.docMdl.getCredentials().first()
        val identities = emptyList<RequesterIdentity>()
        assertEquals(
            FaceMatchingMode.NEVER,
            sourceNever.getFaceMatchingMode(cred, identities)
        )
        assertSame(cred, queriedCred)
        assertSame(identities, queriedIdentities)
    }
}
