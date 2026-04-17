package org.multipaz.records.request

import io.ktor.server.routing.Routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.multipaz.records.payment.PaymentProcessorImpl
import org.multipaz.records.payment.register
import org.multipaz.rpc.handler.HttpHandler
import org.multipaz.rpc.handler.RpcDispatcherLocal
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcPoll
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.request.rpc

fun Routing.recordsRpc(environment: Deferred<ServerEnvironment>) {
    rpc("/rpc", initAndCreateHttpHandler(environment))
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
            env.getInterface(SimpleCipher::class)!!,
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
    PaymentProcessorImpl.register(dispatcherBuilder)
    return dispatcherBuilder
}