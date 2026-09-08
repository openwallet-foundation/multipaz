package org.multipaz.rpc

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.junit.Before
import org.multipaz.context.initializeApplication
import org.multipaz.device.DeviceCheck
import org.multipaz.device.toCbor
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.rpc.handler.RpcAuthInspectorAssertion
import org.multipaz.rpc.handler.RpcAuthIssuerAssertion
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.AesGcmCipher
import org.multipaz.rpc.handler.RpcAuthError
import org.multipaz.rpc.handler.RpcAuthException
import org.multipaz.rpc.handler.RpcDispatcher
import org.multipaz.rpc.handler.RpcDispatcherAuth
import org.multipaz.rpc.handler.RpcDispatcherLocal
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.rpc.test.TestInterfaceStub
import org.multipaz.rpc.test.TestState
import org.multipaz.rpc.test.register
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

fun getPlatformSecureAreaProvider(storage: Storage): SecureAreaProvider<SecureArea> {
    return SecureAreaProvider(Dispatchers.Default) {
        AndroidKeystoreSecureArea.create(storage)
    }
}

class TestBackendEnvironment: BackendEnvironment {
    val storage = EphemeralStorage()
    val secureAreaProvider = getPlatformSecureAreaProvider(storage)

    override fun <T : Any> getInterface(clazz: KClass<T>): T {
        return clazz.cast(when (clazz) {
            Storage::class -> storage
            SecureAreaProvider::class -> secureAreaProvider
            RpcAuthInspector::class -> RpcAuthInspectorAssertion.Default
            else -> throw IllegalArgumentException("no such class available: ${clazz.simpleName}")
        })
    }
}

class RpcAuthAssertionTest {
    private suspend fun buildDispatcher(authClientId: String? = "clientId"): RpcDispatcher {
        val builder = RpcDispatcherLocal.Builder()
        TestState.register(builder)
        val cipher = AesGcmCipher(Random.Default.nextBytes(16))
        val environment = TestBackendEnvironment()
        val local = builder.build(environment, cipher, RpcExceptionMap.Builder().build())
        val secureArea = environment.secureAreaProvider.get()
        val challenge = "clientId".encodeToByteString()
        val deviceAttestation = DeviceCheck.generateAttestation(secureArea, challenge)
        val deviceAttestationId = deviceAttestation.deviceAttestationId
        val clientTable = environment.storage.getTable(RpcAuthInspectorAssertion.rpcClientTableSpec)
        clientTable.insert(
            key = "clientId",
            data = ByteString(deviceAttestation.deviceAttestation.toCbor())
        )
        return if (authClientId != null) {
            val rpcAuth = RpcAuthIssuerAssertion(authClientId, secureArea, deviceAttestationId)
            RpcDispatcherAuth(local, rpcAuth)
        } else {
            local
        }
    }

    @Before
    fun setup() {
        initializeApplication(InstrumentationRegistry.getInstrumentation().context)
    }

    @Test
    fun testValidAuth() = runTest {
        val dispatcher = buildDispatcher()
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        val session1 = withContext(RpcAuthClientSession()) {
            val result0 = target.test("foo")
            assertTrue(result0.startsWith("foo@clientId."))
            val session = result0.substring(4)
            val result1 = target.test("bar")
            assertTrue(result1.startsWith("bar@clientId."))
            assertEquals(session, result1.substring(4))
            val result2 = target.test("buz")
            assertTrue(result2.startsWith("buz@clientId."))
            assertEquals(session, result2.substring(4))
            session
        }
        val session2 = withContext(RpcAuthClientSession()) {
            target.test("foobar").substring(7)
        }
        assertNotEquals(session1, session2)
    }

    @Test
    fun testNoSession() = runTest {
        val dispatcher = buildDispatcher()
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        try {
            target.test("foo")
            fail()
        } catch(err: IllegalStateException) {
            // noop
        }
    }

    @Test
    fun testBadClient() = runTest {
        val dispatcher = buildDispatcher("badClientId")
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        withContext(RpcAuthClientSession()) {
            try {
                target.test("foo")
                fail()
            } catch (err: RpcAuthException) {
                assertEquals(RpcAuthError.UNKNOWN_CLIENT_ID, err.rpcAuthError)
            }
        }
    }

    @Test
    fun testShortFakeNonce() = runTest {
        val dispatcher = buildDispatcher()
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        val session = RpcAuthClientSession()
        session.nonce = "badNonce".encodeToByteString()
        withContext(session) {
            val result = target.test("foo")
            assertTrue(result.startsWith("foo@clientId."))
        }
    }

    @Test
    fun testLongFakeNonce() = runTest {
        val dispatcher = buildDispatcher()
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        val session = RpcAuthClientSession()
        session.nonce = "badNonce-MustBeRelativelyLongToTestAnotherPath".encodeToByteString()
        withContext(session) {
            val result = target.test("foo")
            assertTrue(result.startsWith("foo@clientId."))
        }
    }

    @Test
    fun testNoAuth() = runTest {
        val dispatcher = buildDispatcher(null)
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        withContext(RpcAuthClientSession()) {
            try {
                target.test("foo")
                fail()
            } catch(err: RpcAuthException) {
                assertEquals(RpcAuthError.REQUIRED, err.rpcAuthError)
            }
        }
    }
}