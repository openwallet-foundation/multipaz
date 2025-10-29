package org.multipaz.prompt

import org.multipaz.securearea.PassphraseConstraints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PromptModelTest {
    private lateinit var promptModel: TestPromptModel
    private var mockUiJob: Job? = null

    @BeforeTest
    fun resetSharedState() {
        promptModel = TestPromptModel()
        mockUiJob = null
    }

    @Test
    fun noPromptModel() = runTest {
        val exception = try {
            PromptModel.get(coroutineContext)
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

        val promptState = dialogState[0] as SinglePromptModel.DialogShownState
        assertEquals("Title", promptState.parameters.title)
        assertEquals("Subtitle", promptState.parameters.subtitle)
        assertEquals(PassphraseConstraints.NONE, promptState.parameters.passphraseConstraints)
        assertNull(promptState.parameters.passphraseEvaluator)
        assertTrue(dialogState[1] is SinglePromptModel.NoDialogState)
    }

    @Test
    fun simplePromptTopScope() = runTest {
        val dialogState = collectDialogState { "Bar" }
        val promptJob = promptModel.promptModelScope.launch {
            val passphrase = PromptModel.get(coroutineContext).requestPassphrase(
                title = "Title Top",
                subtitle = "Subtitle Top",
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
            assertEquals("Bar", passphrase)
        }
        promptJob.join()

        val promptState = dialogState[0] as SinglePromptModel.DialogShownState
        assertEquals("Title Top", promptState.parameters.title)
        assertEquals("Subtitle Top", promptState.parameters.subtitle)
        assertEquals(PassphraseConstraints.NONE, promptState.parameters.passphraseConstraints)
        assertNull(promptState.parameters.passphraseEvaluator)
        assertTrue(dialogState[1] is SinglePromptModel.NoDialogState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellation() = runTest {
        val dialogState = collectDialogState { IGNORE }
        val promptJob = launch(UnconfinedTestDispatcher(testScheduler) + promptModel) {
            PromptModel.get(coroutineContext).requestPassphrase(
                title = "Title",
                subtitle = "Subtitle",
                passphraseConstraints = PassphraseConstraints.NONE,
                passphraseEvaluator = null
            )
            fail()
        }
        promptJob.cancelAndJoin()

        // Check that dialog appears and then gets dismissed
        assertTrue(dialogState[0] is SinglePromptModel.DialogShownState)
        assertTrue(dialogState[1] is SinglePromptModel.NoDialogState)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dismissal() = runTest {
        collectDialogState { IGNORE }
        val exception = async(UnconfinedTestDispatcher(testScheduler) + promptModel) {
            try {
                PromptModel.get(coroutineContext).requestPassphrase(
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
    private fun TestScope.collectDialogState(
        mockInput: suspend (request: PassphraseRequest) -> String?
    ): MutableList<SinglePromptModel.DialogState<PassphraseRequest, String?>> {
        val dialogState = mutableListOf<SinglePromptModel.DialogState<PassphraseRequest, String?>>()
        mockUiJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            // This mocks the UI
            var pendingResultChannel: SendChannel<String?>? = null
            try {
                promptModel.passphrasePromptModel.dialogState.collect { state ->
                    dialogState.add(state)
                    pendingResultChannel = null
                    if (state is SinglePromptModel.DialogShownState) {
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

    class TestPromptModel: PromptModel {
        override val passphrasePromptModel = SinglePromptModel<PassphraseRequest, String?>()
        override val promptModelScope =
            CoroutineScope(Dispatchers.Default + SupervisorJob() + this)

        override val toHumanReadable: ConvertToHumanReadableFn = { _, _ ->
            throw IllegalStateException("unexpected state")
        }

        fun onClose() {
            promptModelScope.cancel()
        }
    }
}