package org.multipaz.context

import android.content.Context
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * An object for binding an Android activity context to a coroutine.
 *
 * @param context the [Context] that is being held.
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
