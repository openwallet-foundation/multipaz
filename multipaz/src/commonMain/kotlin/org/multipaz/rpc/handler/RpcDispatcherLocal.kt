package org.multipaz.rpc.handler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.rpc.backend.BackendEnvironment
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * [RpcDispatcher] implementation that dispatches RPC method calls to
 * to backend ("state") object calls. This is for use in server environment and for testing.
 *
 * Each backend class should be registered with [RpcDispatcherLocal.Builder] using
 * generated State.register method.
 */
class RpcDispatcherLocal private constructor(
    val environment: BackendEnvironment,
    private val cipher: SimpleCipher,
    private val targetMap: Map<String, TargetItem<*>>,
    override val exceptionMap: RpcExceptionMap
) : RpcDispatcher {
    private val stateMap = targetMap.toList().mapIndexed { _, pair ->
        Pair(pair.second.stateClass, pair.first)
    }.toMap()

    class Builder {
        private val targetMap = mutableMapOf<String, TargetItem<*>>()

        fun<StateT: Any> addEndpoint(
            target: String,
            stateClass: KClass<StateT>,
            stateSerializer: (StateT) -> DataItem,
            stateDeserializer: (DataItem) -> StateT,
            block: TargetBuilder<StateT>.() -> Unit) {
            val builder = TargetBuilder(stateClass, stateSerializer, stateDeserializer)
            builder.block()
            targetMap[target] = builder.build()
        }

        fun build(
            environment: BackendEnvironment,
            cipher: SimpleCipher,
            exceptionMap: RpcExceptionMap
        ): RpcDispatcherLocal {
            return RpcDispatcherLocal(environment, cipher, targetMap.toMap(), exceptionMap)
        }
    }

    class TargetBuilder<StateT: Any>(
        private val stateClass: KClass<StateT>,
        private val stateSerializer: (StateT) -> DataItem,
        private val stateDeserializer: (DataItem) -> StateT
    ) {
        private var creatable = false
        private val postMap = mutableMapOf<String, suspend (RpcDispatcherLocal, StateT, List<DataItem>) -> DataItem>()

        fun dispatch(method: String, impl: suspend (RpcDispatcherLocal, StateT, List<DataItem>) -> DataItem) {
            postMap[method] = impl
        }

        fun creatable() {
            this.creatable = true
        }

        internal fun build(): TargetItem<StateT> {
            return TargetItem(stateClass, creatable, postMap.toMap(), stateSerializer,
                stateDeserializer)
        }
    }

    override suspend fun dispatch(target: String, method: String, args: DataItem): List<DataItem> {
        val endpoint = targetMap[target] ?:
            throw UnsupportedOperationException("endpoint '$target' not found")
        return withContext(environment) {
            endpoint.dispatch(this@RpcDispatcherLocal, target, method, args)
        }
    }

    suspend fun decodeStateParameter(stateParameter: DataItem): Any {
        val stateArray = stateParameter.asArray
        val item = targetMap[stateArray[0].asTstr]!!
        return (item.stateDeserializer)(Cbor.decode(cipher.decrypt(stateArray[1].asBstr)))
    }

    suspend fun encodeStateResult(result: Any): DataItem {
        val target = stateMap[result::class]
            ?: throw IllegalStateException("${result::class.simpleName} was not registered")
        return CborArray(mutableListOf(
            Tstr(target),
            Bstr(cipher.encrypt(Cbor.encode(targetMap[target]!!.serialize(result))))
        ))
    }

    internal class TargetItem<StateT: Any>(
        internal val stateClass: KClass<StateT>,
        private val creatable: Boolean,
        private val handlerMap: Map<String, suspend (RpcDispatcherLocal, StateT, List<DataItem>) -> DataItem>,
        internal val stateSerializer: (StateT) -> DataItem,
        internal val stateDeserializer: (DataItem) -> StateT
    ) {
        suspend fun dispatch(
            owner: RpcDispatcherLocal,
            target: String,
            method: String,
            args: DataItem
        ): List<DataItem> {
            val handler = handlerMap[method]
                ?: throw UnsupportedOperationException("operation $method not found")
            val authCheckRequired = args is CborMap
            val argList = if (authCheckRequired) {
                Cbor.decode(args["payload"].asBstr).asArray
            } else {
                args.asArray
            }
            val stateBlob = argList[0].asBstr
            val cipher = owner.cipher
            val decryptedState = if (stateBlob.isEmpty()) {
                byteArrayOf()
            } else {
                cipher.decrypt(stateBlob)
            }
            val state = stateDeserializer(
                if (stateBlob.isEmpty()) {
                    if (creatable) {
                        argList[0]  // empty bstr
                    } else {
                        throw IllegalStateException("endpoint '$target' is not creatable")
                    }
                } else {
                    Cbor.decode(decryptedState)
                })
            if (authCheckRequired) {
                val authContext = try {
                    if (state !is RpcAuthInspector) {
                        throw RpcAuthException(
                            message = "${state::class.simpleName} must implement RpcAuthChecker",
                            rpcAuthError = RpcAuthError.NOT_SUPPORTED
                        )
                    }
                    state.authCheck(target, method, args["payload"] as Bstr, args)
                } catch (err: RpcAuthNonceException) {
                    val newStateBlob = stateDataItem(cipher, argList[0], decryptedState, state)
                    return listOf(
                        newStateBlob,
                        Bstr(err.nonce.toByteArray()),
                        RpcReturnCode.NONCE_RETRY.ordinal.toDataItem()
                    )
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Throwable) {
                    val newStateBlob = stateDataItem(cipher, argList[0], decryptedState, state)
                    return owner.exceptionMap.exceptionReturn(newStateBlob, err)
                }
                val nextNonce = authContext[RpcAuthContext.Key]?.nextNonce
                try {
                    val result = withContext(authContext) {
                        handler(owner, state, argList.subList(1, argList.size))
                    }
                    val newStateBlob = stateDataItem(cipher, argList[0], decryptedState, state)
                    val newNonce = if (nextNonce == null) {
                        Simple.NULL
                    } else {
                        Bstr(nextNonce.toByteArray())
                    }
                    return listOf(
                        newStateBlob,
                        newNonce,
                        RpcReturnCode.RESULT.ordinal.toDataItem(),
                        result
                    )
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Throwable) {
                    val newStateBlob = stateDataItem(cipher, argList[0], decryptedState, state)
                    return owner.exceptionMap.exceptionReturn(
                        newStateBlob, err, nextNonce)
                }
            } else {
                if (state is RpcAuthInspector) {
                    throw RpcAuthException(
                        message = "${state::class.simpleName} requires authorization",
                        rpcAuthError = RpcAuthError.REQUIRED
                    )
                }
                try {
                    val result = handler(owner, state, argList.subList(1, argList.size))
                    val newStateBlob = stateDataItem(cipher, argList[0], decryptedState, state)
                    return listOf(newStateBlob, Simple.NULL,
                        RpcReturnCode.RESULT.ordinal.toDataItem(), result)
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Throwable) {
                    val newStateBlob = stateDataItem(cipher, argList[0], decryptedState, state)
                    return owner.exceptionMap.exceptionReturn(newStateBlob, err)
                }
            }
        }

        private suspend fun stateDataItem(
            cipher: SimpleCipher,
            previous: DataItem,
            previousDecryptedState: ByteArray,
            newState: StateT
        ): DataItem {
            val newDecryptedState = Cbor.encode(stateSerializer(newState))
            // Don't re-encrypt if the state did not change
            return if (newDecryptedState contentEquals previousDecryptedState) {
                previous
            } else {
                Bstr(cipher.encrypt(newDecryptedState))
            }
        }

        internal fun serialize(value: Any): DataItem {
            return stateSerializer(stateClass.cast(value))
        }
    }
}