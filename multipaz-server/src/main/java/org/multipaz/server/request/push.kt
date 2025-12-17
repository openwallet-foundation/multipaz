package org.multipaz.server.request

import io.ktor.server.routing.Routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.multipaz.rpc.handler.HttpHandler
import org.multipaz.rpc.handler.RpcDispatcherLocal
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcPoll
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.enrollment.Enrollment
import org.multipaz.server.enrollment.EnrollmentImpl
import org.multipaz.server.enrollment.register

/**
 * Handle server-to-server communication.
 *
 * Currently only used for enrollment. See [Enrollment].
 */
fun Routing.push(environment: Deferred<ServerEnvironment>) {
    rpc("/push", initAndCreateHttpHandler(environment))
}

private fun initAndCreateHttpHandler(
    environment: Deferred<ServerEnvironment>
): Deferred<HttpHandler> {
    return CoroutineScope(Dispatchers.Default).async {
        val env = environment.await()
        val exceptionMap = buildExceptionMap()
        val dispatcherBuilder = buildDispatcher()
        val localDispatcher = dispatcherBuilder.build(
            env,
            env.cipher,
            exceptionMap
        )
        HttpHandler(localDispatcher, RpcPoll.SILENT)
    }
}

private fun buildExceptionMap(): RpcExceptionMap {
    return RpcExceptionMap.Builder().build()
}

private fun buildDispatcher(): RpcDispatcherLocal.Builder {
    val dispatcherBuilder = RpcDispatcherLocal.Builder()
    EnrollmentImpl.register(dispatcherBuilder)
    return dispatcherBuilder
}