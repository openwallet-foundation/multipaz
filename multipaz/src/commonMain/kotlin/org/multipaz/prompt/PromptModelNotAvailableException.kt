package org.multipaz.prompt

import kotlinx.coroutines.withContext

/**
 * Thrown when [PromptModel] is not present in the current coroutine context.
 *
 * This happens when [PromptModel] was not injected in the coroutine context with either
 * [withContext] or by using `rememberCoroutineScope { promptModel }`.
 */
class PromptModelNotAvailableException: Exception("PromptModel must be present in coroutine scope")