package org.multipaz.rpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.device.DeviceCheck
import org.multipaz.device.toCbor
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.AesGcmCipher
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcAuthError
import org.multipaz.rpc.handler.RpcAuthException
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.rpc.handler.RpcAuthInspectorAssertion
import org.multipaz.rpc.handler.RpcAuthIssuerAssertion
import org.multipaz.rpc.handler.RpcDispatcher
import org.multipaz.rpc.handler.RpcDispatcherAuth
import org.multipaz.rpc.handler.RpcDispatcherLocal
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNonceAndSession
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.rpc.test.TestInterfaceStub
import org.multipaz.rpc.test.TestState
import org.multipaz.rpc.test.register
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmTestBackendEnvironment(
    val storage: Storage = EphemeralStorage()
) : BackendEnvironment {
    val secureAreaProvider = SecureAreaProvider(Dispatchers.Default) {
        SoftwareSecureArea.create(storage)
    }

    override fun <T : Any> getInterface(clazz: KClass<T>): T {
        return clazz.cast(when (clazz) {
            Storage::class -> storage
            SecureAreaProvider::class -> secureAreaProvider
            RpcAuthInspector::class -> RpcAuthInspectorAssertion.Default
            else -> throw IllegalArgumentException("no such class available: ${clazz.simpleName}")
        })
    }
}

class RpcAuthAssertionJvmTest {

    @BeforeTest
    fun setup() {
        RpcNonceAndSession.resetForTesting()
    }

    private suspend fun setupClient(
        environment: JvmTestBackendEnvironment,
        clientId: String
    ): Pair<SecureArea, String> {
        val secureArea = environment.secureAreaProvider.get()
        val challenge = clientId.encodeToByteString()
        val deviceAttestation = DeviceCheck.generateAttestation(secureArea, challenge)
        val deviceAttestationId = deviceAttestation.deviceAttestationId
        val clientTable = environment.storage.getTable(RpcAuthInspectorAssertion.rpcClientTableSpec)
        clientTable.insert(
            key = clientId,
            data = ByteString(deviceAttestation.deviceAttestation.toCbor())
        )
        return Pair(secureArea, deviceAttestationId)
    }

    private suspend fun buildDispatcher(
        environment: JvmTestBackendEnvironment,
        authClientId: String? = "clientId",
        clientCredentials: Pair<SecureArea, String>? = null
    ): RpcDispatcher {
        val builder = RpcDispatcherLocal.Builder()
        TestState.register(builder)
        val cipher = AesGcmCipher(Random.Default.nextBytes(16))
        val local = builder.build(environment, cipher, RpcExceptionMap.Builder().build())

        return if (authClientId != null) {
            val (secureArea, deviceAttestationId) = clientCredentials
                ?: setupClient(environment, authClientId)
            val rpcAuth = RpcAuthIssuerAssertion(authClientId, secureArea, deviceAttestationId)
            RpcDispatcherAuth(local, rpcAuth)
        } else {
            local
        }
    }

    @Test
    fun testValidAuth() = runTest {
        val environment = JvmTestBackendEnvironment()
        val dispatcher = buildDispatcher(environment)
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
    fun testServerRestartRecovery() = runTest {
        // Shared persistent storage for server restarts
        val storage = EphemeralStorage()
        val env1 = JvmTestBackendEnvironment(storage)
        val clientCredentials = setupClient(env1, "clientId")

        val dispatcher1 = buildDispatcher(env1, "clientId", clientCredentials)
        val target1 = TestInterfaceStub("test", dispatcher1, RpcNotifier.SILENT)

        val clientSession = RpcAuthClientSession()

        // 1. Client communicates with server instance 1
        withContext(clientSession) {
            val result1 = target1.test("hello")
            assertTrue(result1.startsWith("hello@clientId."))
        }

        // Nonce is populated in clientSession
        assertTrue(clientSession.nonce.size > 0)

        // 2. Simulate server restart: in-memory cipher reset and new dispatcher/environment
        RpcNonceAndSession.resetForTesting()
        val env2 = JvmTestBackendEnvironment(storage)
        val dispatcher2 = buildDispatcher(env2, "clientId", clientCredentials)
        val target2 = TestInterfaceStub("test", dispatcher2, RpcNotifier.SILENT)

        // 3. Client continues using the same clientSession (carrying the pre-restart nonce)
        withContext(clientSession) {
            val result2 = target2.test("world")
            assertTrue(result2.startsWith("world@clientId."))
        }
    }

    @Test
    fun testEphemeralStorageServerRestartRecovery() = runTest {
        // Even if server storage is completely lost/ephemeral across restarts and client has old credentials
        val env1 = JvmTestBackendEnvironment(EphemeralStorage())
        val clientCredentials = setupClient(env1, "clientId")
        val dispatcher1 = buildDispatcher(env1, "clientId", clientCredentials)
        val target1 = TestInterfaceStub("test", dispatcher1, RpcNotifier.SILENT)

        val clientSession = RpcAuthClientSession()

        withContext(clientSession) {
            val result1 = target1.test("hello")
            assertTrue(result1.startsWith("hello@clientId."))
        }

        // Simulate server restart with fresh storage and fresh client registration
        RpcNonceAndSession.resetForTesting()
        val env2 = JvmTestBackendEnvironment(EphemeralStorage())
        // Re-register device attestation on new server
        val clientTable = env2.storage.getTable(RpcAuthInspectorAssertion.rpcClientTableSpec)
        val attestation = env1.storage.getTable(RpcAuthInspectorAssertion.rpcClientTableSpec).get("clientId")!!
        clientTable.insert("clientId", attestation)

        val dispatcher2 = buildDispatcher(env2, "clientId", clientCredentials)
        val target2 = TestInterfaceStub("test", dispatcher2, RpcNotifier.SILENT)

        // Client presents nonce encrypted with old server key: should recover via NONCE_RETRY seamlessly
        withContext(clientSession) {
            val result2 = target2.test("world")
            assertTrue(result2.startsWith("world@clientId."))
        }
    }

    @Test
    fun testShortFakeNonceRecovery() = runTest {
        val environment = JvmTestBackendEnvironment()
        val dispatcher = buildDispatcher(environment)
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        val session = RpcAuthClientSession()
        session.nonce = "badNonce".encodeToByteString()
        withContext(session) {
            val result = target.test("foo")
            assertTrue(result.startsWith("foo@clientId."))
        }
    }

    @Test
    fun testLongFakeNonceRecovery() = runTest {
        val environment = JvmTestBackendEnvironment()
        val dispatcher = buildDispatcher(environment)
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        val session = RpcAuthClientSession()
        session.nonce = "badNonce-MustBeRelativelyLongToTestAnotherPath".encodeToByteString()
        withContext(session) {
            val result = target.test("foo")
            assertTrue(result.startsWith("foo@clientId."))
        }
    }

    @Test
    fun testMultiClientIsolation() = runTest {
        val environment = JvmTestBackendEnvironment()
        val credsA = setupClient(environment, "clientA")
        val credsB = setupClient(environment, "clientB")

        val dispatcherA = buildDispatcher(environment, "clientA", credsA)
        val dispatcherB = buildDispatcher(environment, "clientB", credsB)

        val targetA = TestInterfaceStub("test", dispatcherA, RpcNotifier.SILENT)
        val targetB = TestInterfaceStub("test", dispatcherB, RpcNotifier.SILENT)

        val sessionA = RpcAuthClientSession()
        val sessionB = RpcAuthClientSession()

        // Interleaved calls from clientA and clientB
        withContext(sessionA) {
            val resA1 = targetA.test("msg1")
            assertTrue(resA1.startsWith("msg1@clientA."))
        }
        withContext(sessionB) {
            val resB1 = targetB.test("msg2")
            assertTrue(resB1.startsWith("msg2@clientB."))
        }
        withContext(sessionA) {
            val resA2 = targetA.test("msg3")
            assertTrue(resA2.startsWith("msg3@clientA."))
        }
        withContext(sessionB) {
            val resB2 = targetB.test("msg4")
            assertTrue(resB2.startsWith("msg4@clientB."))
        }
    }

    @Test
    fun testNoSession() = runTest {
        val environment = JvmTestBackendEnvironment()
        val dispatcher = buildDispatcher(environment)
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        try {
            target.test("foo")
            fail()
        } catch (err: IllegalStateException) {
            // expected: RpcAuthClientSession missing
        }
    }

    @Test
    fun testBadClient() = runTest {
        val environment = JvmTestBackendEnvironment()
        val creds = setupClient(environment, "registeredClient")
        val dispatcher = buildDispatcher(environment, "unregisteredClient", creds)
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
    fun testNoAuth() = runTest {
        val environment = JvmTestBackendEnvironment()
        val dispatcher = buildDispatcher(environment, null)
        val target = TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
        withContext(RpcAuthClientSession()) {
            try {
                target.test("foo")
                fail()
            } catch (err: RpcAuthException) {
                assertEquals(RpcAuthError.REQUIRED, err.rpcAuthError)
            }
        }
    }
}
