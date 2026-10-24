package org.multipaz.context

import android.content.Context
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * A [CoroutineContext.Element] for binding an Android activity [Context] to a coroutine.
 *
 * In Compose, use `org.multipaz.compose.rememberUiBoundCoroutineScope` to automatically include
 * an [AndroidUiContext] in the coroutine scope. When not using Compose, pass
 * `AndroidUiContext(activity)` to `launch` or `withContext`:
 *
 * ```kotlin
 * withContext(AndroidUiContext(activity)) {
 *     ...
 * }
 * ```
 *
 * @param context the [Context] being held; must be an [android.app.Activity] or wrap one via
 *   [android.content.ContextWrapper].
 * @see UiContext
 */
class AndroidUiContext(private val context: Context): UiContext(), CoroutineContext.Element {
    private object Key: CoroutineContext.Key<AndroidUiContext>

    override val key: CoroutineContext.Key<*>
        get() = Key

    class NotUiBoundCoroutineError:
        Error("Current coroutine is not UiContext-bound")

    companion object Companion {
        /**
         * Gets the activity associated with the coroutine.
         *
         * @return the activity context, ie. a [Context] for which [Context.getActivity] will return the
         *   associated [android.app.Activity].
         * @throws NotUiBoundCoroutineError if no context is bound to the coroutine.
         */
        suspend fun current(): Context {
            return coroutineContext[Key]?.context ?: throw NotUiBoundCoroutineError()
        }
    }
}
