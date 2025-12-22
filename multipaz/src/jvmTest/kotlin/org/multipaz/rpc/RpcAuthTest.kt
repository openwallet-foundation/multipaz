package org.multipaz.rpc

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.rpc.annotation.RpcInterface
import org.multipaz.rpc.annotation.RpcMethod
import org.multipaz.rpc.annotation.RpcState
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.AesGcmCipher
import org.multipaz.rpc.handler.RpcAuthContext
import org.multipaz.rpc.handler.RpcAuthError
import org.multipaz.rpc.handler.RpcAuthException
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.rpc.handler.RpcAuthIssuer
import org.multipaz.rpc.handler.RpcDispatcherAuth
import org.multipaz.rpc.handler.RpcDispatcherLocal
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

@RpcInterface
interface TestInterface {
    @RpcMethod
    suspend fun test(param: String): String
}

@RpcState(
    endpoint = "test",
    creatable = true
)
@CborSerializable
class TestState: TestInterface, RpcAuthInspector {
    override suspend fun test(param: String): String {
        return "$param@${RpcAuthContext.getClientId()}"
    }

    override suspend fun authCheck(
        target: String,
        method: String,
        payload: Bstr,
        authMessage: DataItem
    ): CoroutineContext {
        val expected = ByteString(authMessage["digest"].asBstr)
        val actual = ByteString(Crypto.digest(Algorithm.SHA256, payload.asBstr))
        if (expected != actual) {
            throw RpcAuthException("Failed", RpcAuthError.FAILED)
        }
        return RpcAuthContext("validClient", "")
    }

    companion object
}

class TestRpcAuthIssuer(private val valid: Boolean): RpcAuthIssuer {
    override suspend fun auth(target: String, method: String, payload: Bstr): DataItem {
        val digest = Crypto.digest(
            Algorithm.SHA256,
            if (valid) payload.asBstr else byteArrayOf()
        )
        return buildCborMap {
            put("payload", payload)
            put("digest", digest)
        }
    }
}

private fun buildDispatcher(): RpcDispatcherLocal {
    val builder = RpcDispatcherLocal.Builder()
    TestState.register(builder)
    val cipher = AesGcmCipher(Random.Default.nextBytes(16))
    val environment = BackendEnvironment.EMPTY
    return builder.build(environment, cipher, RpcExceptionMap.Builder().build())
}

private fun buildStub(authIssuer: RpcAuthIssuer?): TestInterface {
    val dispatcher = if (authIssuer != null) {
        RpcDispatcherAuth(buildDispatcher(), authIssuer)
    } else {
        buildDispatcher()
    }
    return TestInterfaceStub("test", dispatcher, RpcNotifier.SILENT)
}

class RpcAuthTest {
    @Test
    fun testValidAuth() = runTest {
        val result = buildStub(TestRpcAuthIssuer(true)).test("foo")
        assertEquals("foo@validClient", result)
    }

    @Test
    fun testInvalidAuth() = runTest {
        try {
            buildStub(TestRpcAuthIssuer(false)).test("foo")
            fail()
        } catch (err: RpcAuthException) {
            assertEquals(RpcAuthError.FAILED, err.rpcAuthError)
        }
    }

    @Test
    fun testNoAuth() = runTest {
        try {
            buildStub(null).test("foo")
            fail()
        } catch (err: RpcAuthException) {
            assertEquals(RpcAuthError.REQUIRED, err.rpcAuthError)
        }
    }
}