package org.multipaz.prompt

import org.multipaz.securearea.PassphraseConstraints
import kotlinx.coroutines.CancellationException
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

    @BeforeTest
    fun resetSharedState() {
        promptModel = TestPromptModel.Builder().apply { addCommonDialogs() }.build()
        mockUiJob = null
    }

    @Test
    fun noPromptModel() = runTest {
        val exception = try {
            PromptModel.get()
            null
        } catch (err: Throwable) {
            err
        }
        assertTrue(exception is PromptModelNotAvailableException)
    }

    @Test
    fun noPromptUI() = runTest {
        val exception = try {
            promptModel.requestPassphrase(
                title = "Title",
                subtitle = "Subtitle",
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
            null
        } catch (err: Throwable) {
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
            title = "Unused",
            subtitle = "Unused",
            passphraseConstraints = PassphraseConstraints.NONE,
            passphraseEvaluator = null
        )
        // Unbind UI
        mockUiJob!!.cancel()
        mockUiJob!!.join()
        assertFalse(promptModel.triedToLaunchUI)

        assertFailsWith<PromptUiNotAvailableException> {
            promptModel.requestPassphrase(
                title = "Title",
                subtitle = "Subtitle",
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
            title = "Title",
            subtitle = "Subtitle",
            passphraseConstraints = PassphraseConstraints.NONE,
            passphraseEvaluator = null
        )
        assertEquals("Foo", passphrase)

        val promptState = dialogState[0] as PromptDialogModel.DialogShownState
        assertEquals("Title", promptState.parameters.title)
        assertEquals("Subtitle", promptState.parameters.subtitle)
        assertEquals(PassphraseConstraints.NONE, promptState.parameters.passphraseConstraints)
        assertNull(promptState.parameters.passphraseEvaluator)
        assertTrue(dialogState[1] is PromptDialogModel.NoDialogState)
    }

    @Test
    fun simplePromptTopScope() = runTest {
        val dialogState = collectDialogState { "Bar" }
        val promptJob = promptModel.promptModelScope.launch {
            val passphrase = PromptModel.get().requestPassphrase(
                title = "Title Top",
                subtitle = "Subtitle Top",
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
            assertEquals("Bar", passphrase)
        }
        promptJob.join()

        val promptState = dialogState[0] as PromptDialogModel.DialogShownState
        assertEquals("Title Top", promptState.parameters.title)
        assertEquals("Subtitle Top", promptState.parameters.subtitle)
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
                title = "Title",
                subtitle = "Subtitle",
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
                    title = "Title",
                    subtitle = "Subtitle",
                    passphraseConstraints = PassphraseConstraints.NONE,
                    passphraseEvaluator = null
                )
                null
            } catch (err: Throwable) {
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
                    title = "Title First",
                    subtitle = "Subtitle First",
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
        assertEquals("Title First", request.title)
        // From a different coroutine, call the PromptModel again
        val secondRequest = promptModel.promptModelScope.launch {
            PromptModel.get().requestPassphrase(
                title = "Title Second",
                subtitle = "Subtitle Second",
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
        }
        assertTrue(firstRequest.await() is PromptDismissedException)
        secondRequest.cancel()
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
            } catch (err: Throwable) {
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