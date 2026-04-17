package org.multipaz.upay.server

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.client.RpcAuthorizedServerClient
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.RpcAuthClientSession
import org.multipaz.rpc.handler.RpcExceptionMap
import org.multipaz.rpc.handler.RpcNotifier
import org.multipaz.server.common.enrollmentServerUrl
import org.multipaz.server.common.getBaseUrl
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import org.multipaz.server.presentment.PaymentProcessor
import org.multipaz.server.presentment.PaymentProcessorStub
import org.multipaz.server.presentment.PaymentTransactionRequest
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.utopia.knowntypes.PaymentTransaction
import org.multipaz.verifier.customization.VerifierAssistant
import org.multipaz.verifier.customization.VerifierPresentment

internal object TransactionProcessor: VerifierAssistant {
    // accept all transactions for testing
    override suspend fun processRequest(request: JsonObject): VerifierAssistant.ExpandedRequest? {
        val configuration = BackendEnvironment.getInterface(Configuration::class)!!
        val serviceUrl = configuration.enrollmentServerUrl ?: return null
        val payeeAccount = (request["payee_account"] as? JsonPrimitive)?.content
            ?: throw InvalidRequestException("'payee_account' is missing or invalid")
        val amount = (request["amount"] as? JsonPrimitive)?.doubleOrNull
            ?: throw InvalidRequestException("'amount' is missing or invalid")
        val description = (request["description"] as? JsonPrimitive)?.content
        val paymentProcessor = getPaymentProcessor(serviceUrl)
        val transactionData = withContext(RpcAuthClientSession()) {
            paymentProcessor.createTransaction(PaymentTransactionRequest(
                payeeAccount = payeeAccount,
                description = description,
                amount = amount,
                currency = "USD",
            ))
        }
        val verifierRequest = buildJsonObject {
            putJsonObject("dcql") {
                putJsonArray("credentials") {
                    addJsonObject {
                        put("id", "payment")
                        put("format", "mso_mdoc")
                        putJsonObject("meta") {
                            put("doctype_value", PAYMENT_DOCTYPE)
                        }
                        putJsonArray("claims") {
                            addJsonObject {
                                putJsonArray("path") {
                                    add(PAYMENT_NS)
                                    add("issuer_name")
                                }
                                put("intent_to_retain", true)
                            }
                            addJsonObject {
                                putJsonArray("path") {
                                    add(PAYMENT_NS)
                                    add("payment_instrument_id")
                                }
                                put("intent_to_retain", true)
                            }
                            addJsonObject {
                                putJsonArray("path") {
                                    add(PAYMENT_NS)
                                    add("expiry_date")
                                }
                                put("intent_to_retain", true)
                            }
                            addJsonObject {
                                putJsonArray("path") {
                                    add(PAYMENT_NS)
                                    add("holder_name")
                                }
                                put("intent_to_retain", true)
                            }
                        }
                    }
                }
            }
            putJsonArray("transaction_data") {
                addJsonObject {
                    put("type", PaymentTransaction.identifier)
                    putJsonArray("credential_ids") {
                        add("payment")
                    }
                    putJsonObject("payload") {
                        put("transaction_id", transactionData.transactionId)
                        putJsonObject("payee") {
                            put("name", transactionData.payeeName)
                            put("id", payeeAccount)
                        }
                        put("currency", "USD")
                        put("amount", amount)
                    }
                }
            }
            request["protocols"]?.let { put("protocols", it) }
        }
        return VerifierAssistant.ExpandedRequest(verifierRequest, transactionData.nonce)
    }

    override suspend fun processResponse(
        presentment: VerifierPresentment
    ): JsonObject {
        if (!presentment.presentmentResults.first().trustResult.isTrusted) {
            throw InvalidRequestException("Payment card is not from a trusted issuer")
        }
        val configuration = BackendEnvironment.getInterface(Configuration::class)!!
        val serviceUrl = configuration.enrollmentServerUrl!!
        val paymentProcessor = getPaymentProcessor(serviceUrl)
        withContext(RpcAuthClientSession()) {
            paymentProcessor.commitTransaction(presentment.presentmentRecord)
        }
        return presentment.transactions[0]["payload"]!!.jsonObject
    }

    private suspend fun getPaymentProcessor(serviceUrl: String): PaymentProcessor {
        val exceptionMap = RpcExceptionMap.Builder().build()
        val dispatcher = RpcAuthorizedServerClient.connect(
            exceptionMap = exceptionMap,
            rpcEndpointUrl = "$serviceUrl/rpc",
            callingServerUrl = BackendEnvironment.getBaseUrl(),
            signingKey = getServerIdentity(ServerIdentity.PAYMENT_PROCESSOR)
        )
        return PaymentProcessorStub("payment", dispatcher, RpcNotifier.SILENT)
    }

    const val PAYMENT_DOCTYPE = DigitalPaymentCredential.CARD_DOCTYPE
    const val PAYMENT_NS = DigitalPaymentCredential.CARD_NAMESPACE
}