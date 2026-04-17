package org.multipaz.records.payment

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.records.data.Identity
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.StorageTableSpec
import kotlin.math.roundToLong
import kotlin.random.Random

@CborSerializable
data class PaymentAccount(
    val holderId: String,
    val balance: Double
) {
    companion object {
        suspend fun create(holderId: String): String {
            // We want accounts to be 8-digit numeric strings, so generate keys manually
            val table = BackendEnvironment.getTable(accountTableSpec)
            val data = PaymentAccount(holderId = holderId, balance = 0.0)
            while (true) {
                val account = (Random.nextInt(90000000) + 10000000).toString()
                try {
                    return table.insert(
                        key = account,
                        data = ByteString(data.toCbor())
                    )
                } catch (_: KeyExistsStorageException) {
                    // retry
                }
            }
        }

        suspend fun get(accountId: String): PaymentAccount {
            val accountTable = BackendEnvironment.getTable(accountTableSpec)
            return accountTable.get(accountId)
                ?.let { PaymentAccount.fromCbor(it.toByteArray()) }
                ?: throw InvalidRequestException("Unknown account: '$accountId'")
        }

        suspend fun applyPayment(transactionId: String, data: PaymentData) {
            if (data.amount <= 0) {
                throw InvalidRequestException("Negative payment amount: ${data.amount}")
            }
            val accountTable = BackendEnvironment.getTable(accountTableSpec)
            val accountTransactionTable = BackendEnvironment.getTable(accountTransactionTableSpec)
            val payerAccount = data.payerAccount!!
            val source = get(payerAccount)
            val payeeAccount = data.payeeAccount
            if (payerAccount == payeeAccount) {
                throw InvalidRequestException("Cannot pay to yourself")
            }
            val target = get(payeeAccount)
            val amount100 = (data.amount * 100).roundToLong()
            val newSourceBalance = 0.01 * ((source.balance * 100).roundToLong() - amount100)
            val newTargetBalance = 0.01 * ((target.balance * 100).roundToLong() + amount100)
            accountTable.update(
                key = payerAccount,
                data = ByteString(PaymentAccount(
                    holderId = source.holderId,
                    balance = newSourceBalance
                ).toCbor())
            )
            accountTable.update(
                key = payeeAccount,
                data = ByteString(PaymentAccount(
                    holderId = target.holderId,
                    balance = newTargetBalance
                ).toCbor())
            )
            val transactionData = ByteString(PaymentRecord(
                payerAccount = payerAccount,
                payerName = data.payerName!!,
                payeeAccount = payeeAccount,
                payeeName = data.payeeName,
                amount = data.amount,
                time = data.time,
                description = data.description
            ).toCbor())
            accountTransactionTable.insert(
                partitionId = payerAccount,
                key = transactionId,
                data = transactionData
            )
            accountTransactionTable.insert(
                partitionId = payeeAccount,
                key = transactionId,
                data = transactionData
            )
        }

        suspend fun getTransactions(accountId: String): List<Pair<String, PaymentRecord>> =
            BackendEnvironment.getTable(accountTransactionTableSpec)
                .enumerateWithData(accountId)
                .map { (transactionId, data ) ->
                    Pair(transactionId, PaymentRecord.fromCbor(data.toByteArray()))
                }

        suspend fun lookupAccountName(accountId: String): String {
            val account = get(accountId)
            val data = Identity.findById(account.holderId).data
            val paymentCards = data.records["payment"]?.values ?: emptyList()
            for (paymentCard in paymentCards) {
                if (paymentCard["account_number"].asTstr == accountId) {
                    if (paymentCard.hasKey("holder_name")) {
                        return paymentCard["holder_name"].asTstr
                    } else {
                        val givenName = data.core["given_name"]?.asTstr
                        val familyName = data.core["family_name"]?.asTstr
                        if (givenName != null) {
                            return if (familyName != null) {
                                "$givenName $familyName"
                            } else {
                                givenName
                            }
                        } else if (familyName != null) {
                            return familyName
                        }
                        break
                    }
                }
            }
            return "Anonymous"
        }

        val accountTableSpec = StorageTableSpec(
            name = "PaymentAccounts",
            supportPartitions = false,
            supportExpiration = false
        )

        val accountTransactionTableSpec = StorageTableSpec(
            name = "PaymentAccountTransactions",
            supportPartitions = true,
            supportExpiration = false
        )
    }
}