package org.multipaz.rpc.handler

import org.multipaz.cbor.DataItem
import org.multipaz.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.seconds

/** [RpcNotifier] implementation based on [RpcPoll] interface. */
class RpcNotifierPoll(private val poll: RpcPoll) : RpcNotifier {
    private val pollSetFlow = MutableSharedFlow<PollItem>()

    companion object {
        private const val TAG = "FlowNotifierPoll"
    }

    override suspend fun<NotificationT: Any> register(
        target: String,
        opaqueState: DataItem,
        notifications: MutableSharedFlow<NotificationT>,
        deserializer: (DataItem) -> NotificationT
    ) {
        val key = RpcPoll.PollKey(target, opaqueState)
        pollSetFlow.emit(PollItem(key, Target(notifications, deserializer)))
    }

    override suspend fun unregister(target: String, opaqueState: DataItem) {
        val key = RpcPoll.PollKey(target, opaqueState)
        pollSetFlow.emit(PollItem(key, null))
    }

    suspend fun loop() {
        val pollMap = mutableMapOf<RpcPoll.PollKey, Target<*>>()
        var consumeToken = ""
        pollSetFlow.transform { item ->
            if (item.target == null) {
                Logger.i(TAG, "Stop listening for ${item.key.target}")
                pollMap.remove(item.key)
            } else {
                Logger.i(TAG, "Start listening for ${item.key.target}")
                pollMap[item.key] = item.target
            }
            emit(pollMap.toList())
        }.collectLatest { list ->
            val refs = list.map { item -> item.first }
            do {
                val result = try {
                    poll.poll(consumeToken, refs)
                } catch (_: RpcPoll.TimeoutException) {
                    continue
                } catch (e: CancellationException) {
                    // important to rethrow this one
                    Logger.w(TAG, "Polling cancelled", e)
                    throw e
                } catch (e: Throwable) {
                    Logger.w(TAG, "Error polling, retrying in 5s...", e)
                    delay(5.seconds)
                    continue
                }
                // don't want to have duplicate notifications
                consumeToken = result.consumeToken
                list[result.index].second.emit(result.notification)
            } while (true)
        }
    }

    private class PollItem(
        val key: RpcPoll.PollKey,
        val target: Target<*>?
    )

    private class Target<NotificationT : Any>(
        val flow: MutableSharedFlow<NotificationT>,
        val deserializer: (DataItem) -> NotificationT
    ) {
        suspend fun emit(dataItem: DataItem) {
            flow.emit(deserializer(dataItem))
        }
    }
}