package org.multipaz.prompt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.bytestring.ByteString
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherLivenessSession
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.securearea.PassphraseConstraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PromptModelTest {
    private lateinit var promptModel: TestPromptModel
    private var mockUiJob: Job? = null

    private class TestFaceMatcherSession(referencePortrait: ByteString) : FaceMatcherSession(referencePortrait) {
        override suspend fun feedFrame(frame: CameraFrame) {}
    }

    private class TestFaceMatcherLivenessSession : FaceMatcherLivenessSession() {
        override suspend fun feedFrame(frame: CameraFrame) {}
    }

    private class TestFaceMatcher : FaceMatcher {
        override val name: String = "test"
        override fun createSession(referencePortrait: ByteString): FaceMatcherSession =
            TestFaceMatcherSession(referencePortrait)
        override fun createLivenessSession(): FaceMatcherLivenessSession =
            TestFaceMatcherLivenessSession()
    }

    @BeforeTest
    fun resetSharedState() {
        promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        val matcher = TestFaceMatcher()
        promptModel.getDialogModel(FaceMatcherPromptDialogModel.DialogType).defaultMatcher = matcher
        promptModel.getDialogModel(FaceMatcherLivenessPromptDialogModel.DialogType).defaultMatcher = matcher
        mockUiJob = null
    }

    @Test
    fun noPromptModel() = runTest {
        val exception = try {
            PromptModel.get()
            null
        } catch (err: Exception) {
            err
        }
        assertTrue(exception is PromptModelNotAvailableException)
    }

    @Test
    fun noPromptUI() = runTest {
        val exception = try {
            promptModel.requestPassphrase(
                reason = Reason.HumanReadable("Title", "Subtitle", false),
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
            null
        } catch (err: Exception) {
            err
        }
        assertTrue(exception is PromptUiNotAvailableException)
        assertTrue(promptModel.triedToLaunchUI)
    }

    @Test
    fun unboundUI() = runTest {
        // Bind UI
        collectDialogState { "Unused" }
        promptModel.requestPassphrase(
            reason = Reason.HumanReadable("Unused", "Unused", false),
            passphraseConstraints = PassphraseConstraints.NONE,
            passphraseEvaluator = null
        )
        // Unbind UI
        mockUiJob!!.cancel()
        mockUiJob!!.join()
        assertFalse(promptModel.triedToLaunchUI)

        assertFailsWith<PromptUiNotAvailableException> {
            promptModel.requestPassphrase(
                reason = Reason.HumanReadable("Title", "Subtitle", false),
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
        }
        assertTrue(promptModel.triedToLaunchUI)
    }

    @Test
    fun simplePromptLocalScope() = runTest {
        val dialogState = collectDialogState { "Foo" }
        val passphrase = promptModel.requestPassphrase(
            reason = Reason.HumanReadable("Title", "Subtitle", false),
            passphraseConstraints = PassphraseConstraints.NONE,
            passphraseEvaluator = null
        )
        assertEquals("Foo", passphrase)

        val promptState = dialogState[0] as PromptDialogModel.DialogShownState
        assertEquals("Title", (promptState.parameters.reason as Reason.HumanReadable).title)
        assertEquals("Subtitle", (promptState.parameters.reason as Reason.HumanReadable).subtitle)
        assertEquals(PassphraseConstraints.NONE, promptState.parameters.passphraseConstraints)
        assertNull(promptState.parameters.passphraseEvaluator)
        assertTrue(dialogState[1] is PromptDialogModel.NoDialogState)
    }

    @Test
    fun simplePromptTopScope() = runTest {
        val dialogState = collectDialogState { "Bar" }
        val promptJob = promptModel.promptModelScope.launch {
            val passphrase = PromptModel.get().requestPassphrase(
                reason = Reason.HumanReadable("Title Top", "Subtitle Top", false),
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
            assertEquals("Bar", passphrase)
        }
        promptJob.join()

        val promptState = dialogState[0] as PromptDialogModel.DialogShownState
        assertEquals("Title Top", (promptState.parameters.reason as Reason.HumanReadable).title)
        assertEquals("Subtitle Top", (promptState.parameters.reason as Reason.HumanReadable).subtitle)
        assertEquals(PassphraseConstraints.NONE, promptState.parameters.passphraseConstraints)
        assertNull(promptState.parameters.passphraseEvaluator)
        assertTrue(dialogState[1] is PromptDialogModel.NoDialogState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellation() = runTest {
        val dialogState = collectDialogState { IGNORE }
        val promptJob = launch(UnconfinedTestDispatcher(testScheduler) + promptModel) {
            PromptModel.get().requestPassphrase(
                reason = Reason.HumanReadable("Title", "Subtitle", false),
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
            fail()
        }
        promptJob.cancelAndJoin()

        // Check that dialog appears and then gets dismissed
        assertTrue(dialogState[0] is PromptDialogModel.DialogShownState)
        assertTrue(dialogState[1] is PromptDialogModel.NoDialogState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dismissal() = runTest {
        collectDialogState { IGNORE }
        val exception = async(UnconfinedTestDispatcher(testScheduler) + promptModel) {
            try {
                PromptModel.get().requestPassphrase(
                    reason = Reason.HumanReadable("Title", "Subtitle", false),
                    passphraseConstraints = PassphraseConstraints.NONE,
                    passphraseEvaluator = null
                )
                null
            } catch (err: Exception) {
                err
            }
        }
        mockUiJob!!.cancel()

        // Check that PromptCancelledException was thrown from requestPassphrase
        assertTrue(exception.await() is PromptDismissedException)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun conflict() = runTest {
        // Test the scenario when a coroutine tries to pop up a dialog when another coroutine
        // has already popped it up.
        val req = Channel<PassphrasePromptDialogModel.PassphraseRequest>(Channel.RENDEZVOUS)
        mockUiJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            // This mocks the UI
            val dialogModel = promptModel.getDialogModel(PassphrasePromptDialogModel.DialogType)
            dialogModel.dialogState.collect { state ->
                if (state is PromptDialogModel.DialogShownState) {
                    req.send(state.parameters)
                }
            }
        }
        val firstRequest = promptModel.promptModelScope.async {
            try {
                PromptModel.get().requestPassphrase(
                    reason = Reason.HumanReadable("Title First", "Subtitle First", false),
                    passphraseConstraints = PassphraseConstraints.NONE,
                    passphraseEvaluator = null
                )
                null
            } catch (err: Exception) {
                err
            }
        }
        // Wait until the dialog is "up"
        val request = req.receive()
        assertEquals("Title First", (request.reason as Reason.HumanReadable).title)
        // From a different coroutine, call the PromptModel again
        val secondRequest = promptModel.promptModelScope.launch {
            PromptModel.get().requestPassphrase(
                reason = Reason.HumanReadable("Title Second", "Subtitle Second", false),
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
        }
        assertTrue(firstRequest.await() is PromptDismissedException)
        secondRequest.cancel()
    }

    @Test
    fun faceMatcherPromptSuccess() = runTest {
        val testPortrait = ByteString(byteArrayOf(1, 2, 3, 4))
        collectFaceMatcherDialogState { request ->
            assertEquals(testPortrait, request.referencePortrait)
            true
        }

        val result = promptModel.showFaceMatcherPrompt(
            referencePortrait = testPortrait,
            reason = Reason.HumanReadable("Title", "Subtitle", false)
        )
        assertTrue(result)
    }

    @Test
    fun faceMatcherPromptDismissed() = runTest {
        val testPortrait = ByteString(byteArrayOf(5, 6, 7, 8))
        collectFaceMatcherDialogState {
            throw PromptDismissedException()
        }

        val result = promptModel.showFaceMatcherPrompt(
            referencePortrait = testPortrait,
            reason = Reason.HumanReadable("Title", "Subtitle", false)
        )
        assertFalse(result)
    }

    @Test
    fun faceMatcherPromptNoMatcherConfigured() = runTest {
        val unconfiguredModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        assertFailsWith<IllegalStateException> {
            unconfiguredModel.showFaceMatcherPrompt(
                referencePortrait = ByteString(byteArrayOf(1, 2, 3, 4)),
                reason = Reason.HumanReadable("Title", "Subtitle", false)
            )
        }
    }

    @Test
    fun faceMatcherLivenessPromptSuccess() = runTest {
        val capturedBytes = ByteString(byteArrayOf(10, 20, 30))
        collectFaceMatcherLivenessDialogState {
            capturedBytes
        }

        val result = promptModel.showFaceLivenessPrompt(
            reason = Reason.HumanReadable("Title", "Subtitle", false)
        )
        assertEquals(capturedBytes, result)
    }

    @Test
    fun faceMatcherLivenessPromptDismissed() = runTest {
        collectFaceMatcherLivenessDialogState {
            throw PromptDismissedException()
        }

        val result = promptModel.showFaceLivenessPrompt(
            reason = Reason.HumanReadable("Title", "Subtitle", false)
        )
        assertNull(result)
    }

    @Test
    fun faceMatcherLivenessPromptNoMatcherConfigured() = runTest {
        val unconfiguredModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        assertFailsWith<IllegalStateException> {
            unconfiguredModel.showFaceLivenessPrompt(
                reason = Reason.HumanReadable("Title", "Subtitle", false)
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectDialogState(
        mockInput: suspend (request: PassphrasePromptDialogModel.PassphraseRequest) -> String
    ): MutableList<PromptDialogModel.DialogState<PassphrasePromptDialogModel.PassphraseRequest, String>> {
        val dialogState = mutableListOf<PromptDialogModel.DialogState<PassphrasePromptDialogModel.PassphraseRequest, String>>()
        mockUiJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            // This mocks the UI
            var pendingResultChannel: SendChannel<String>? = null
            try {
                val dialogModel = promptModel.getDialogModel(PassphrasePromptDialogModel.DialogType)
                dialogModel.dialogState.collect { state ->
                    // Skip initial "no dialog" state
                    if (dialogState.isNotEmpty() || state !is PromptDialogModel.NoDialogState) {
                        dialogState.add(state)
                    }
                    pendingResultChannel = null
                    if (state is PromptDialogModel.DialogShownState) {
                        val passphrase = mockInput(state.parameters)
                        if (passphrase == IGNORE) {
                            pendingResultChannel = state.resultChannel
                        } else {
                            state.resultChannel.send(passphrase)
                        }
                    }
                }
            } catch (err: CancellationException) {
                pendingResultChannel?.close(PromptDismissedException())
                throw err
            } catch (err: Exception) {
                fail("Unexpected error", err)
            }
        }
        return dialogState
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectFaceMatcherDialogState(
        mockInput: suspend (request: FaceMatcherPromptDialogModel.FaceMatcherRequest) -> Boolean
    ): MutableList<PromptDialogModel.DialogState<FaceMatcherPromptDialogModel.FaceMatcherRequest, Boolean>> {
        val dialogState = mutableListOf<PromptDialogModel.DialogState<FaceMatcherPromptDialogModel.FaceMatcherRequest, Boolean>>()
        mockUiJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            var pendingResultChannel: SendChannel<Boolean>? = null
            try {
                val dialogModel = promptModel.getDialogModel(FaceMatcherPromptDialogModel.DialogType)
                dialogModel.dialogState.collect { state ->
                    if (dialogState.isNotEmpty() || state !is PromptDialogModel.NoDialogState) {
                        dialogState.add(state)
                    }
                    pendingResultChannel = null
                    if (state is PromptDialogModel.DialogShownState) {
                        try {
                            val result = mockInput(state.parameters)
                            state.resultChannel.send(result)
                        } catch (e: PromptDismissedException) {
                            state.resultChannel.close(e)
                        }
                    }
                }
            } catch (err: CancellationException) {
                pendingResultChannel?.close(PromptDismissedException())
                throw err
            } catch (err: Exception) {
                fail("Unexpected error", err)
            }
        }
        return dialogState
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectFaceMatcherLivenessDialogState(
        mockInput: suspend (request: FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest) -> ByteString?
    ): MutableList<PromptDialogModel.DialogState<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, ByteString?>> {
        val dialogState = mutableListOf<PromptDialogModel.DialogState<FaceMatcherLivenessPromptDialogModel.FaceMatcherLivenessRequest, ByteString?>>()
        mockUiJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            var pendingResultChannel: SendChannel<ByteString?>? = null
            try {
                val dialogModel = promptModel.getDialogModel(FaceMatcherLivenessPromptDialogModel.DialogType)
                dialogModel.dialogState.collect { state ->
                    if (dialogState.isNotEmpty() || state !is PromptDialogModel.NoDialogState) {
                        dialogState.add(state)
                    }
                    pendingResultChannel = null
                    if (state is PromptDialogModel.DialogShownState) {
                        try {
                            val result = mockInput(state.parameters)
                            state.resultChannel.send(result)
                        } catch (e: PromptDismissedException) {
                            state.resultChannel.close(e)
                        }
                    }
                }
            } catch (err: CancellationException) {
                pendingResultChannel?.close(PromptDismissedException())
                throw err
            } catch (err: Exception) {
                fail("Unexpected error", err)
            }
        }
        return dialogState
    }

    companion object {
        // Special value to indicate that no result should be sent
        const val IGNORE = "__IGNORE__"
    }

    class TestPromptModel private constructor(builder: Builder): PromptModel(builder) {
        var triedToLaunchUI = false
        override val promptModelScope =
            CoroutineScope(Dispatchers.Default + SupervisorJob() + this)

        override suspend fun launchUi(dialogModel: PromptDialogModel<*, *>) {
            triedToLaunchUI = true
        }

        class Builder: PromptModel.Builder(
            toHumanReadable = { _, _ -> throw IllegalStateException("unexpected state") }
        ) {
            override fun build(): TestPromptModel = TestPromptModel(this)
        }
    }
}