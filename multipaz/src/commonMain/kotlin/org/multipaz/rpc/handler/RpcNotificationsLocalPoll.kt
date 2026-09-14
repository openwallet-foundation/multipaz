package org.multipaz.rpc.handler

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.util.Logger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.multipaz.crypto.Crypto
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * Implementation for [RpcNotifications] that also implements [RpcPoll] interface to handle
 * long poll method for notification propagation.
 */
class RpcNotificationsLocalPoll(
    private val cipher: SimpleCipher,
    private val random: Random = Crypto.secureRandom
): RpcNotifications, RpcPoll {
    private val mutex = Mutex()
    private val notificationFlow = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var current = mutableMapOf<RpcNotificationKey, MutableSet<DataItem>>()
    private var last = mutableMapOf<RpcNotificationKey, MutableSet<DataItem>>()
    private var consumed = mutableMapOf<String, ConsumedItem>()
    private var lastConsumed = mutableMapOf<String, ConsumedItem>()
    private var lastRotation = Clock.System.now()

    companion object {
        private val TAG = "FlowNotificationsLocalPoll"
    }

    override suspend fun emit(target: String, state: DataItem, notification: DataItem) {
        val ref = RpcNotificationKey(target, state)
        val dropped = mutableSetOf<String>()
        mutex.withLock {
            maybeRotate(dropped)
            current.getOrPut(ref) { mutableSetOf() }.add(notification)
        }
        notificationFlow.tryEmit(true)
        logDropped(dropped)
    }

    @OptIn(FlowPreview::class, ExperimentalEncodingApi::class)
    override suspend fun poll(consumeToken: String, pollKeys: List<RpcPoll.PollKey>): RpcPoll.PollResult {
        val dropped = mutableSetOf<String>()
        val start = Clock.System.now()
        Logger.i(TAG, "polling flows: ${pollKeys.map { flowRef -> flowRef.target }.joinToString(",")}")
        do {
            var result: RpcPoll.PollResult? = null
            var notificationKey: RpcNotificationKey? = null
            mutex.withLock {
                val consumedItem = consumed.remove(consumeToken) ?: lastConsumed.remove(consumeToken)
                if (consumedItem != null) {
                    current[consumedItem.rpcNotificationKey]?.remove(consumedItem.notification)
                    last[consumedItem.rpcNotificationKey]?.remove(consumedItem.notification)
                }
                maybeRotate(dropped)
                for ((index, pollKey) in pollKeys.withIndex()) {
                    val flowRef = RpcNotificationKey(
                        pollKey.target,
                        Cbor.decode(cipher.decrypt(pollKey.opaqueState.asBstr))
                    )
                    val currentList = current[flowRef]
                    val lastList = last[flowRef]
                    val notification = if (!currentList.isNullOrEmpty()) {
                        currentList.first()
                    } else if (!lastList.isNullOrEmpty()) {
                        lastList.first()
                    } else {
                        continue
                    }
                    Logger.i(TAG, "pushing notification for flow ${flowRef.target}")
                    result = RpcPoll.PollResult(
                        consumeToken = Base64.encode(random.nextBytes(15)),
                        index = index,
                        notification = notification
                    )
                    notificationKey = flowRef
                    break
                }
            }
            logDropped(dropped)
            if (result != null) {
                mutex.withLock {
                    lastConsumed.put(result!!.consumeToken, ConsumedItem(
                        rpcNotificationKey = notificationKey!!,
                        notification = result!!.notification
                    ))
                }
                return result!!
            }
            try {
                notificationFlow.timeout(1.minutes).take(1).collect {}
            } catch(exception: TimeoutCancellationException) {
                // ignore
            }
        } while (Clock.System.now() - start < 3.minutes)
        throw RpcPoll.TimeoutException()
    }

    private fun maybeRotate(dropped: MutableSet<String>) {
        val now = Clock.System.now()
        if (now - lastRotation > 1.minutes) {
            for (key in last.keys) {
                dropped.add(key.target)
            }
            last.clear()
            val tmp = current
            current = last
            last = tmp
            lastRotation = now
            lastConsumed.clear()
            val tmpConsumed = consumed
            consumed = lastConsumed
            lastConsumed = tmpConsumed
        }
    }

    private fun logDropped(dropped: Set<String>) {
        for (flowName in dropped) {
            Logger.w(TAG, "dropped notification for $flowName")
        }
    }

    private data class ConsumedItem(
        val rpcNotificationKey: RpcNotificationKey,
        val notification: DataItem
    )
}