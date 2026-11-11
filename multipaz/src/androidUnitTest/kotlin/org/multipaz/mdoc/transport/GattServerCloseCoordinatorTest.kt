package org.multipaz.mdoc.transport

import android.bluetooth.BluetoothGatt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GattServerCloseCoordinatorTest {

    private class FakeGattServer(val id: Int)

    @Test
    fun closeAfterOnServiceAddedClosesImmediately() = runTest {
        val closedServers = mutableListOf<FakeGattServer>()
        val coordinator = GattServerCloseCoordinator<FakeGattServer>(
            closeServer = { closedServers.add(it) },
            scope = this,
            fallbackTimeout = 5.seconds
        )
        val server = FakeGattServer(1)

        coordinator.onAddServiceStarted(server)
        assertTrue(coordinator.onServiceAdded(server, BluetoothGatt.GATT_SUCCESS))
        assertTrue(closedServers.isEmpty())

        coordinator.releaseActiveServer()
        assertEquals(listOf(server), closedServers)
    }

    @Test
    fun closeWhileAddServiceInFlightDefersCloseUntilOnServiceAdded() = runTest {
        val closedServers = mutableListOf<FakeGattServer>()
        val coordinator = GattServerCloseCoordinator<FakeGattServer>(
            closeServer = { closedServers.add(it) },
            scope = this,
            fallbackTimeout = 5.seconds
        )
        val server = FakeGattServer(1)

        coordinator.onAddServiceStarted(server)
        // Calling releaseActiveServer() while addService() is in flight must NOT close immediately.
        coordinator.releaseActiveServer()
        assertTrue(closedServers.isEmpty())

        // Once onServiceAdded() fires from native, the coordinator must close the server and
        // return false so the transport callback is not invoked for the closed server.
        assertFalse(coordinator.onServiceAdded(server, BluetoothGatt.GATT_SUCCESS))
        assertEquals(listOf(server), closedServers)
    }

    @Test
    fun reopenWhilePreviousAddServiceInFlightClosesFirstOnCallbackAndKeepsSecondActive() = runTest {
        val closedServers = mutableListOf<FakeGattServer>()
        val coordinator = GattServerCloseCoordinator<FakeGattServer>(
            closeServer = { closedServers.add(it) },
            scope = this,
            fallbackTimeout = 5.seconds
        )
        val server1 = FakeGattServer(1)
        val server2 = FakeGattServer(2)

        coordinator.onAddServiceStarted(server1)
        // Re-opening releases server1 (deferred) and starts server2.
        coordinator.releaseActiveServer()
        coordinator.onAddServiceStarted(server2)
        assertTrue(closedServers.isEmpty())

        // Late onServiceAdded() for server1 closes server1 immediately without affecting server2.
        assertFalse(coordinator.onServiceAdded(server1, BluetoothGatt.GATT_SUCCESS))
        assertEquals(listOf(server1), closedServers)

        // onServiceAdded() for server2 succeeds as the active server.
        assertTrue(coordinator.onServiceAdded(server2, BluetoothGatt.GATT_SUCCESS))
        assertEquals(listOf(server1), closedServers)

        coordinator.releaseActiveServer()
        assertEquals(listOf(server1, server2), closedServers)
    }

    @Test
    fun deferredCloseFallbackTimeoutClosesServerIfOnServiceAddedNeverFires() {
        val testScope = TestScope(StandardTestDispatcher())
        testScope.runTest {
            val closedServers = mutableListOf<FakeGattServer>()
            val coordinator = GattServerCloseCoordinator<FakeGattServer>(
                closeServer = { closedServers.add(it) },
                scope = this,
                fallbackTimeout = 500.milliseconds
            )
            val server = FakeGattServer(1)

            coordinator.onAddServiceStarted(server)
            coordinator.releaseActiveServer()
            assertTrue(closedServers.isEmpty())

            advanceTimeBy(499.milliseconds)
            assertTrue(closedServers.isEmpty())

            advanceTimeBy(2.milliseconds)
            assertEquals(listOf(server), closedServers)
        }
    }

    @Test
    fun detectsPoisonedHandleMapSymptomWhenPeerDisconnectsAfterMtuWithZeroActivity() = runTest {
        val coordinator = GattServerCloseCoordinator<FakeGattServer>(
            closeServer = {},
            scope = this
        )
        val peerAddress = "AA:BB:CC:DD:EE:FF"

        // Case 1: Peer negotiates MTU and disconnects with no GATT read/write or L2CAP activity
        coordinator.onMtuChanged(peerAddress)
        assertTrue(coordinator.onPeerDisconnected(peerAddress))

        // Case 2: Peer negotiates MTU, performs GATT/L2CAP activity, then disconnects normally
        coordinator.onMtuChanged(peerAddress)
        coordinator.onGattOrL2capActivity()
        assertFalse(coordinator.onPeerDisconnected(peerAddress))

        // Case 3: Prior peer had GATT/L2CAP activity without negotiating MTU and disconnected;
        // subsequent peer negotiating MTU with zero activity is still detected.
        val otherPeer = "11:22:33:44:55:66"
        coordinator.onGattOrL2capActivity()
        assertFalse(coordinator.onPeerDisconnected(otherPeer))
        coordinator.onMtuChanged(peerAddress)
        assertTrue(coordinator.onPeerDisconnected(peerAddress))
    }
}
