package org.multipaz.rpc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.rpc.annotation.RpcException
import org.multipaz.rpc.annotation.RpcInterface
import org.multipaz.rpc.annotation.RpcMethod
import org.multipaz.rpc.annotation.RpcState
import org.multipaz.crypto.SecretKey
import org.multipaz.rpc.handler.AesGcmCipher
import org.multipaz.rpc.handler.RpcDispatcherHttp
import org.multipaz.rpc.handler.RpcDispatcherLocal
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.rpc.handler.RpcNotifierPoll
import org.multipaz.rpc.handler.RpcPoll
import org.multipaz.rpc.handler.RpcPollHttp
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.HttpHandler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.multipaz.rpc.client.RpcNotifiable
import org.multipaz.rpc.handler.NoopCipher
import org.multipaz.rpc.handler.RpcNotifications
import org.multipaz.rpc.handler.RpcNotificationsLocal
import org.multipaz.rpc.handler.RpcNotificationsLocalPoll
import org.multipaz.rpc.handler.SimpleCipher
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

// The following data structures are shared between the client and the server. They can be
// used as method parameters and return values.

@CborSerializable
data class QuadraticEquation(val a: Double, val b: Double, val c: Double)

@CborSerializable
data class QuadraticSolution(val x1: Double? = null, val x2: Double? = null)

// These interfaces represent APIs implemented by the back-end and called by the client. Each
// method exposed by an interface must be marked as 'suspend' as they are generally processed
// through a network request and response. Back-end implementation of this interface holds opaque
// state  that its methods can read and modify across their invocations. The state is round-tripped
// between the client and the back-end as a blob, like a big HTTP cookie, except that it is
// encrypted with the key (typically only known to the back-end).

@RpcInterface
interface QuadraticSolver {
    // A method call typically maps to a single HTTP POST request. Methods can take zero or more
    // parameters and optionally return a value.
    @RpcMethod
    suspend fun solve(equation: QuadraticEquation): QuadraticSolution

    @RpcMethod
    suspend fun getName(): String
}

@RpcInterface
interface NotificationTest: RpcNotifiable<String> {
    @RpcMethod
    suspend fun sendNotification(value: String)
}

@RpcInterface
interface SolverFactory {

    // A back-end object can create other back-end objects. This is indicated by returning
    // an `@RpcInterface`-marked interface as method's result.
    @RpcMethod
    suspend fun createQuadraticSolver(name: String): QuadraticSolver

    @RpcMethod
    suspend fun completeQuadraticSolver(solver: QuadraticSolver)

    @RpcMethod
    suspend fun getCount(): Int

    // Just to test nullable parameters and return values
    @RpcMethod
    suspend fun nullableIdentity(value: String?): String?

    // Test passing `@RpcInterface`-marked interfaces as parameters
    @RpcMethod
    suspend fun examineSolver(solver: QuadraticSolver): String

    @RpcMethod
    suspend fun createNotificationTest(key: String): NotificationTest
}

// The following definitions are back-end-side (although we can patch them to run on the client
// as well). Each state object holds state for a particular back-end object. State can be thought
// as a type of HTTP cookie, except that it  is not transported as HTTP cookie and it, being
// encrypted, it is totally opaque for the client.
//
// A single RPC interface can be implemented by multiple back-end objects. Every back-end object
// state class must  have a distinct value for the endpoint parameter (which is optional, default
// endpoint value is the state class simple name). Note that state instances are serialized and
// deserialized across invocations, moreover if server runs on multiple nodes, RPC method
// invocations may happen across multiple nodes. Thus only data stored in the state is meaningful,
// not specific object instance.

// Common interface for XXXSolverState classes.
interface AbstractSolverState {
    fun finalCount(): Int
}

@CborSerializable
@RpcState(endpoint = "direct")
data class DirectQuadraticSolverState(
    var count: Int = 0
) : AbstractSolverState, QuadraticSolver {
    companion object {}

    override suspend fun getName(): String {
        return "Direct"
    }

    override suspend fun solve(equation: QuadraticEquation): QuadraticSolution {
        val det = equation.b * equation.b - 4 * equation.a * equation.c
        count++
        val result = if (det > 0) {
            val d = sqrt(det)
            QuadraticSolution(
                (-equation.b + d)/(2*equation.a),
                (-equation.b - d)/(2*equation.a)
            )
        } else if (det == 0.0) {
            QuadraticSolution(-equation.b/(2*equation.a))
        } else {
            throw NoSolutionsSolverException("No solutions")
        }
        return result
    }

    override fun finalCount(): Int = count

    override fun toString(): String = "DirectQuadraticSolverState[$count]"
}

@CborSerializable
@RpcState(endpoint = "mock")
class MockQuadraticSolverState : AbstractSolverState, QuadraticSolver {
    companion object {}

    override suspend fun getName(): String {
        return "Mock"
    }

    override suspend fun solve(equation: QuadraticEquation): QuadraticSolution {
        if (equation.a == 1.0 && equation.b == -6.0 && equation.c == 9.0) {
            return QuadraticSolution(3.0)
        } else if (equation.a == 1.0 && equation.b == 0.0 && equation.c == 1.0) {
            throw NoSolutionsSolverException("No solutions")
        }
        throw IllegalStateException()
    }

    override fun finalCount(): Int = 2

    override fun toString(): String = "MockQuadraticSolverState"
}

@CborSerializable
@RpcException
sealed class SolverException(message: String? = null) : Exception(message) {
    companion object
}

// No need for annotations or registration for subclasses of sealed class.
class NoSolutionsSolverException(message: String? = null) : SolverException(message)
class UnknownSolverException(message: String? = null) : SolverException(message)

@RpcState(endpoint = "notification_test")
@CborSerializable
class NotificationTestState(val key: String): NotificationTest {

    override suspend fun sendNotification(value: String) {
        emit("$key: $value")
    }

    override suspend fun collect(collector: FlowCollector<String>) {
        this.collectImpl(collector)
    }

    override suspend fun dispose() {
        this.disposeImpl()
    }

    companion object
}

@CborSerializable
@RpcState(
    endpoint = "factory",
    creatable = true
)
data class SolverFactoryState(
    var totalCount: Int = 0
): SolverFactory {
    companion object {}

    override suspend fun createQuadraticSolver(name: String): QuadraticSolver {
        return when (name) {
            "Direct" -> DirectQuadraticSolverState()
            "Mock" -> MockQuadraticSolverState()
            else -> throw UnknownSolverException("No such solver")
        }
    }

    override suspend fun completeQuadraticSolver(solver: QuadraticSolver) {
        solver as AbstractSolverState
        totalCount += solver.finalCount()
    }

    override suspend fun getCount(): Int = totalCount

    override suspend fun nullableIdentity(value: String?): String? = value

    override suspend fun examineSolver(solver: QuadraticSolver): String {
        return solver.toString()
    }

    override suspend fun createNotificationTest(key: String): NotificationTestState {
        return NotificationTestState(key)
    }
}

class TestBackendEnvironment(
    private val notifications: RpcNotifications,
    private val notifier: RpcNotifier,
    private var poll: RpcPoll? = null
): BackendEnvironment {
    override fun <T : Any> getInterface(clazz: KClass<T>): T =
        clazz.cast(when (clazz) {
            RpcNotifications::class -> notifications
            RpcNotifier::class -> notifier
            RpcPoll::class -> poll ?: RpcPoll.SILENT
            else -> throw IllegalArgumentException("No implementation for $clazz")
        })
}

// RpcHandlerLocal handles RPC calls that executed locally.
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.buildLocalDispatcher(
    exceptionMap: RpcExceptionMap,
    usePolling: Boolean = false,
    useNoopCipher: Boolean = false
): RpcDispatcherLocal {
    val builder = RpcDispatcherLocal.Builder()
    DirectQuadraticSolverState.register(builder)
    MockQuadraticSolverState.register(builder)
    SolverFactoryState.register(builder)
    NotificationTestState.register(builder)
    val cipher = if (useNoopCipher) {
        NoopCipher
    } else {
        AesGcmCipher(SecretKey(Random.Default.nextBytes(16)))
    }
    val environment = if (usePolling) {
        val notifications = RpcNotificationsLocalPoll(cipher)
        val notifier = RpcNotifierPoll(notifications)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            notifier.loop()
        }
        TestBackendEnvironment(notifications, notifier, notifications)
    } else {
        val local = RpcNotificationsLocal(cipher)
        TestBackendEnvironment(local, local)
    }
    return builder.build(environment, cipher, exceptionMap)
}

private fun buildExceptionMap(): RpcExceptionMap {
    val builder = RpcExceptionMap.Builder()
    SolverException.register(builder)
    return builder.build()
}

class RpcProcessorTest {
    private val exceptionMap = buildExceptionMap()

    @Test
    fun nakedDirect() = runTest {
        // No RPC stubs involved, just direct call
        testCalls("Direct", SolverFactoryState())
    }

    @Test
    fun localDirect() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        // Local RPC call, suitable as in-process RPC mechanism
        testCalls("Direct", localFactory(localDispatcher))
    }

    @Test
    fun localMock() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        testCalls("Mock", localFactory(localDispatcher))
    }

    @Test
    fun localUnexpectedNameException() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        try {
            localFactory(localDispatcher).createQuadraticSolver("Foo")
            fail()
        } catch (e: UnknownSolverException) {
            // expected
        }
    }

    @Test
    fun remoteDirect() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        // Remote RPC call, suitable as over-the-wire RPC mechanism
        testCalls("Direct", remoteFactory(localDispatcher))
    }

    @Test
    fun remoteMock() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        testCalls("Mock", remoteFactory(localDispatcher))
    }

    @Test
    fun nullable() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        val factory = remoteFactory(localDispatcher)
        assertEquals("Foo", factory.nullableIdentity("Foo"))
        assertNull(factory.nullableIdentity(null))
    }

    @Test
    fun parameter() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        val factory = localFactory(localDispatcher)
        assertEquals(
            "MockQuadraticSolverState",
            factory.examineSolver(factory.createQuadraticSolver("Mock"))
        )
        assertEquals(
            "DirectQuadraticSolverState[0]",
            factory.examineSolver(factory.createQuadraticSolver("Direct"))
        )
    }

    @Test
    fun remoteUnexpectedNameException() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        try {
            remoteFactory(localDispatcher).createQuadraticSolver("Foo")
            fail()
        } catch (e: UnknownSolverException) {
            // expected
        }
    }

    @Test
    fun nakedNotifications() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap, useNoopCipher = true)
        testNotification(SolverFactoryState(), localDispatcher.environment)
    }

    @Test
    fun localNotifications() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap)
        testNotification(localFactory(localDispatcher))
    }

    @Test
    fun localPollNotifications() = runTest {
        val localDispatcher = buildLocalDispatcher(exceptionMap, true)
        testNotification(localFactory(localDispatcher))
    }

    private fun localFactory(localDispatcher: RpcDispatcherLocal): SolverFactory {
        return SolverFactoryStub(
            "factory",
            localDispatcher,
            localDispatcher.environment.getInterface(RpcNotifier::class)!!
        )
    }

    private fun remoteFactory(localDispatcher: RpcDispatcherLocal): SolverFactory {
        // Remote handler executes RPC calls by marshalling them to another handler, typically
        // through HTTP. We can, however short-circuit them to just go to our local handler
        // for testing.
        val http = HttpHandler(
            localDispatcher,
            localDispatcher.environment.getInterface(RpcPoll::class)!!
        )
        val remoteHandler = RpcDispatcherHttp(http, exceptionMap)
        val remoteNotifier = RpcNotifierPoll(RpcPollHttp(http))
        return SolverFactoryStub(
            "factory",
            remoteHandler,
            remoteNotifier)
    }

    private suspend fun testCalls(name: String, factory: SolverFactory) {
        val solver = factory.createQuadraticSolver(name)
        assertEquals(name, solver.getName())
        val solution1 = solver.solve(QuadraticEquation(a = 1.0, b = -6.0, c = 9.0))
        assertNotNull(solution1.x1)
        assertEquals(3.0, solution1.x1, 1e-10)
        assertNull(solution1.x2)
        try {
            solver.solve(QuadraticEquation(a = 1.0, b = 0.0, c = 1.0))
            fail()
        } catch (err: NoSolutionsSolverException) {
            // expect to happen
        }
        assertEquals(0, factory.getCount())
        factory.completeQuadraticSolver(solver)
        assertEquals(2, factory.getCount())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.testNotification(
        factory: SolverFactory,
        extraContext: CoroutineContext = EmptyCoroutineContext
    ) {
        withContext(extraContext) {
            runCurrent()
            val notificationTest = factory.createNotificationTest("foo")
            runCurrent()
            val events = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                withContext(extraContext) {
                    notificationTest.collect { event ->
                        events.add(event)
                    }
                }
            }
            runCurrent()
            notificationTest.sendNotification("bar")
            runCurrent()
            assertEquals(listOf("foo: bar"), events)
            notificationTest.dispose()
        }
    }
}