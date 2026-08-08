package org.multipaz.server.common

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * Provides access to ktor [ApplicationCall] object.
 *
 * @param call Ktor [ApplicationCall] which is being handled.
 */
class KtorCall internal constructor(
    val call: ApplicationCall
): CoroutineContext.Element {
    object Key:CoroutineContext.Key<KtorCall>

    override val key: CoroutineContext.Key<KtorCall>
        get() = Key

    companion object {
        /**
         * Finds ktor [ApplicationCall] which is being handled by this coroutine.
         *
         * This can be called only when it is known that the code is invoked by a ktor handler
         * which was set up using [runServer].
         *
         * @return [ApplicationCall] which is being handled
         */
        suspend fun getCall(): ApplicationCall =
            (currentCoroutineContext()[Key]
                ?: throw IllegalStateException("Not in ktor handler")).call
    }
}
