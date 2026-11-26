package org.multipaz.openid4vci.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class NotificationStateTest {
    private class TestClock(var time: Instant) : Clock {
        override fun now(): Instant = time
    }

    @Test
    fun recordsOneOutcomeAndExpires() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(100))
        val storage = EphemeralStorage(clock)
        val environment = object : BackendEnvironment {
            override fun <T : Any> getInterface(clazz: KClass<T>): T? =
                if (clazz == Storage::class) clazz.cast(storage) else null
        }
        withContext(environment) {
            val id = NotificationState.create("session", clock.now() + 10.seconds, Random(42))
            assertEquals(
                NotificationState.Result.INVALID_ID,
                NotificationState.recordEvent(id, "other-session", "credential_accepted")
            )
            assertEquals(
                NotificationState.Result.RECORDED,
                NotificationState.recordEvent(id, "session", "credential_accepted")
            )
            assertEquals(
                NotificationState.Result.RETRY,
                NotificationState.recordEvent(id, "session", "credential_accepted")
            )
            assertEquals(
                NotificationState.Result.CONFLICT,
                NotificationState.recordEvent(id, "session", "credential_failure")
            )
            clock.time += 11.seconds
            assertEquals(
                NotificationState.Result.INVALID_ID,
                NotificationState.recordEvent(id, "session", "credential_accepted")
            )
        }
    }

    @Test
    fun concurrentConflictingEventsHaveOneWinner() = runTest {
        val storage = EphemeralStorage()
        val environment = object : BackendEnvironment {
            override fun <T : Any> getInterface(clazz: KClass<T>): T? =
                if (clazz == Storage::class) clazz.cast(storage) else null
        }
        withContext(environment) {
            val id = NotificationState.create("session", Clock.System.now() + 60.seconds, Random(42))
            val start = CompletableDeferred<Unit>()
            val results = (0 until 20).map { index ->
                async(Dispatchers.Default) {
                    start.await()
                    val event = if (index % 2 == 0) "credential_accepted" else "credential_failure"
                    NotificationState.recordEvent(id, "session", event)
                }
            }
            start.complete(Unit)
            val outcomes = results.awaitAll()
            assertEquals(1, outcomes.count { it == NotificationState.Result.RECORDED })
            assertEquals(9, outcomes.count { it == NotificationState.Result.RETRY })
            assertEquals(10, outcomes.count { it == NotificationState.Result.CONFLICT })
        }
    }
}
