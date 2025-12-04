package org.multipaz.prompt

/**
 * Thrown when user dismisses a prompt or another coroutine tries to display the prompt of
 * the same type (that preempts the originally displayed prompt).
 */
class PromptDismissedException: Exception()