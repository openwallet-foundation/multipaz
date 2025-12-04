package org.multipaz.prompt

/**
 * Thrown when [PromptDialogModel] is not bound to the UI even after calling [PromptModel.launchUi].
 */
class PromptUiNotAvailableException(): Exception("PromptModel is not bound to UI")