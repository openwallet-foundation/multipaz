package org.multipaz.records.payment

import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.rpc.annotation.RpcState
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.rpc.handler.RpcAuthInspectorSignature
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getLocalRootCertificate
import org.multipaz.server.presentment.PaymentProcessor
import org.multipaz.server.presentment.PaymentTransactionData
import org.multipaz.server.presentment.PaymentTransactionRequest
import org.multipaz.server.presentment.PresentmentRecord
import org.multipaz.server.presentment.PresentmentRecordMdoc
import org.multipaz.server.presentment.PresentmentRecordOpenID4VP
import org.multipaz.server.presentment.PresentmentResultMdoc
import org.multipaz.util.Logger
import org.multipaz.util.truncateToWholeSeconds
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RpcState(
    endpoint = "payment",
    creatable = true
)
@CborSerializable
class PaymentProcessorImpl: PaymentProcessor, RpcAuthInspector by rpcAuth {
    override suspend fun createTransaction(
        request: PaymentTransactionRequest
    ): PaymentTransactionData = try {
        val paymentTable = BackendEnvironment.getTable(PaymentData.paymentsTableSpec)
        val initial = PaymentData(
            payeeAccount = request.payeeAccount,
            payeeName = PaymentAccount.lookupAccountName(request.payeeAccount),
            description = request.description,
            amount = request.amount,
            currency = request.currency,
            time = Clock.System.now().truncateToWholeSeconds(),
            nonce = ByteString(Random.nextBytes(15)),
            payerAccount = null,
            payerName = null,
            presentmentRecord = null
        )
        val transactionId = paymentTable.insert(
            key = null,
            data = ByteString(initial.toCbor()),
            expiration = Clock.System.now() + 20.minutes
        )
        PaymentTransactionData(
            transactionId = transactionId,
            payeeName = initial.payeeName,
            nonce = initial.nonce
        )
    } catch (err: CancellationException) {
        throw err
    } catch (err: InvalidRequestException) {
        throw err
    } catch (err: Exception) {
        Logger.e("PaymentProcessor", "Error in createTransaction", err)
        throw InvalidRequestException("Error creating transaction: ${err.message}")
    }

    override suspend fun commitTransaction(
        presentmentRecord: PresentmentRecord
    ): String = try {
        val now = Clock.System.now().truncateToWholeSeconds()
        val documentTypeRepository =
            BackendEnvironment.getInterface(DocumentTypeRepository::class)!!
        val transactionData = when (presentmentRecord) {
            is PresentmentRecordMdoc ->
                presentmentRecord.getTransactionData(documentTypeRepository).let {
                    check(it.size == 1 && it.first().size == 1)
                    it.first().first()
                }
            is PresentmentRecordOpenID4VP ->
                presentmentRecord.getTransactionData(documentTypeRepository).let {
                    check(it.size == 1 && it.values.first().size == 1)
                    it.values.first().first()
                }
        }
        val transactionPayload = transactionData.attributes.getCompound("payload")!!
        val transactionId = transactionPayload.getString("transaction_id")!!
        val amount = transactionPayload.getDouble("amount")!!
        val currency = transactionPayload.getString("currency")!!
        val paymentTable = BackendEnvironment.getTable(PaymentData.paymentsTableSpec)
        val data = paymentTable.get(transactionId)
            ?: throw InvalidRequestException("Transaction '$transactionId' is invalid or expired")
        val draft = PaymentData.fromCbor(data.toByteArray())
        if ((amount * 100).roundToLong() != (draft.amount * 100).roundToLong() || currency != draft.currency) {
            throw InvalidRequestException("Inconsistent transaction amount or currency")
        }
        presentmentRecord.verifyNonce(draft.nonce)
        val result = presentmentRecord.verify(now)
        check(result.size == 1)
        val payment = result.first() as PresentmentResultMdoc
        if (!payment.trustResult.isTrusted) {
            throw InvalidRequestException("Payment instrument is not issued by a trusted issuer")
        }
        val claims = payment.mdocDocument.issuerNamespaces.data[DigitalPaymentCredential.CARD_NAMESPACE]!!
        val payerAccount = claims["payment_instrument_id"]!!.dataElementValue.asTstr
        val payerName = claims["holder_name"]?.dataElementValue?.asTstr
        // We don't have transaction support in our simplistic storage interface; this lock
        // prevents conflicts/inconsistencies on a single-machine server.
        transactionLock.withLock {
            if (draft.presentmentRecord != null) {
                throw InvalidRequestException("Transaction '$transactionId' is already committed")
            }
            // First, extend transaction expiration time, without changing actual data, so that
            // the second update command below never fails due to record expiring. This update
            // command may fail, but it will not break data consistency.
            paymentTable.update(
                key = transactionId,
                data = data,
                expiration = now + 1.hours
            )
            val paymentData = PaymentData(
                payeeAccount = draft.payeeAccount,
                payeeName = draft.payeeName,
                description = draft.description,
                amount = amount,
                currency = currency,
                time = now,
                nonce = draft.nonce,
                payerAccount = payerAccount,
                payerName = payerName,
                presentmentRecord = presentmentRecord
            )
            // NB: applyPayment will fail if the transaction cannot be applied
            PaymentAccount.applyPayment(transactionId, paymentData)
            // This update must never fail, if it does, our storage becomes inconsistent
            paymentTable.update(
                key = transactionId,
                data = ByteString(paymentData.toCbor()),
                expiration = Instant.DISTANT_FUTURE  // TODO: maybe keep transactions for 30 days?
            )
            transactionId  // for now
        }
    } catch (err: CancellationException) {
        throw err
    } catch (err: InvalidRequestException) {
        throw err
    } catch (err: Exception) {
        Logger.e("PaymentProcessor", "Error in commitTransaction", err)
        err.printStackTrace()
        throw InvalidRequestException("Error commiting transaction: ${err.message}")
    }

    companion object {
        private val rpcAuth = RpcAuthInspectorSignature {
            getLocalRootCertificate(ServerIdentity.PAYMENT_PROCESSOR, true)
        }

        private val transactionLock = Mutex()
    }
}