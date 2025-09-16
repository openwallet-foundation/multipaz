package org.multipaz.compose

import android.content.Context
import java.lang.Exception
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class UiContext(val context: Context): CoroutineContext.Element {
    object Key: CoroutineContext.Key<UiContext>

    override val key: CoroutineContext.Key<*>
        get() = Key

    class NotUiBoundCoroutineError:
        Exception("Current coroutine was is not Android-Context-bound")

    companion object Companion {
        suspend fun current(): Context {
            return coroutineContext[Key]?.context ?: throw NotUiBoundCoroutineError()
        }
    }
}
