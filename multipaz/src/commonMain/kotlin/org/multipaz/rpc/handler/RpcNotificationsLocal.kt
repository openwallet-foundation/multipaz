package org.multipaz.rpc.handler

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.util.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implementation for [RpcNotifications] that can also serve as [RpcNotifier] for
 * testing/developing in local environment.
 */
class RpcNotificationsLocal(
    private val cipher: SimpleCipher
): RpcNotifications, RpcNotifier {
    private val lock = Mutex()
    private val targetMap = mutableMapOf<RpcNotificationKey, Target<*>>()

    companion object {
        private const val TAG = "RpcNotificationsLocal"
    }

    override suspend fun emit(target: String, state: DataItem, notification: DataItem) {
        val ref = RpcNotificationKey(target, state)
        val targetItem = lock.withLock { targetMap[ref] };
        if (targetItem == null) {
            Logger.i(TAG, "Notification has been dropped for $target [$notification]}")
        } else {
            Logger.i(TAG, "Notification is being emitted for $target [$notification]")
            targetItem.emit(notification)
        }
    }

    override suspend fun<NotificationT: Any> register(
        target: String,
        opaqueState: DataItem,
        notifications: MutableSharedFlow<NotificationT>,
        deserializer: (DataItem) -> NotificationT
    ) {
        val ref = RpcNotificationKey(target, Cbor.decode(cipher.decrypt(opaqueState.asBstr)))
        lock.withLock {
            targetMap[ref] = Target(notifications, deserializer)
        }
    }

    override suspend fun unregister(target: String, opaqueState: DataItem) {
        val ref = RpcNotificationKey(target, Cbor.decode(cipher.decrypt(opaqueState.asBstr)))
        lock.withLock {
            targetMap.remove(ref)
        }
    }

    private class Target<NotificationT : Any>(
        val flow: MutableSharedFlow<NotificationT>,
        val deserializer: (DataItem) -> NotificationT
    ) {
        suspend fun emit(dataItem: DataItem) {
            flow.emit(deserializer(dataItem))
        }
    }
}