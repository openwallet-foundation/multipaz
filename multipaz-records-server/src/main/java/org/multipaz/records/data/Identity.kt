package org.multipaz.records.data

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tstr
import org.multipaz.records.payment.PaymentAccount
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.KeyExistsStorageException
import org.multipaz.storage.StorageTableSpec
import kotlin.random.Random

/**
 * Class that holds id and data for a given person.
 *
 * Id is immutable, and data can be modified.
 */
class Identity private constructor(
    val id: String,
    var data: IdentityData
) {
    /**
     * Saves updates to data in storage.
     *
     * @return (possibly adjusted) data which was actually written
     */
    suspend fun save(): IdentityData {
        val table = BackendEnvironment.getTable(tableSpec)
        check(id == data.core["utopia_id_number"]!!.asTstr)
        val paymentData = data.records["payment"]
        val dataToWrite = if (paymentData == null) {
            data
        } else {
            val existingData = table.get(id) ?: throw IdentityNotFoundException()
            val existingPaymentData =
                IdentityData.fromCbor(existingData.toByteArray()).records["payment"]
                    ?: emptyMap()
            val updatedPaymentData = paymentData.toMutableMap()
            for ((cardId, cardData) in data.records["payment"]!!) {
                val existingCardData = existingPaymentData[cardId]
                if (existingCardData == null || !existingCardData.hasKey("account_number")) {
                    val updatedCardData = cardData.asMap.toMutableMap()
                    updatedCardData[Tstr("account_number")] = Tstr(PaymentAccount.create(id))
                    updatedPaymentData[cardId] = CborMap(updatedCardData)
                } else {
                    check(cardData["account_number"] == existingCardData["account_number"])
                    updatedPaymentData[cardId] = cardData
                }
            }
            IdentityData(
                core = data.core,
                records = data.records + ("payment" to updatedPaymentData.toMap())
            )
        }
        table.update(id, ByteString(dataToWrite.toCbor()))
        return dataToWrite
    }

    companion object {
        /** Creates new identity with the given data */
        suspend fun create(data: IdentityData): Identity {
            val table = BackendEnvironment.getTable(tableSpec)
            while (true) {
                val n = Random.nextInt(100000000).toString().padStart(8, '0')
                val utopiaId = n.take(4) + "-" + n.substring(4, 8)
                val core = data.core + ("utopia_id_number" to Tstr(utopiaId))
                val records = data.records.minus("payment") // account numbers will need to be adjusted
                val dataToStore = IdentityData(core, records)
                try {
                    val id = table.insert(
                        key = utopiaId,
                        data = ByteString(dataToStore.toCbor())
                    )
                    return if (data.records.containsKey("payment")) {
                        val adjustedData = Identity(id, IdentityData(core, data.records)).save()
                        Identity(id, adjustedData)
                    } else {
                        Identity(id, dataToStore)
                    }
                } catch (_: KeyExistsStorageException) {
                    // try a different key
                }
            }
        }

        /**
         * Finds [Identity] object in the storage for the given [id].
         */
        suspend fun findById(id: String): Identity {
            val table = BackendEnvironment.getTable(tableSpec)
            val data = table.get(id) ?: throw IdentityNotFoundException()
            return Identity(id, IdentityData.fromCbor(data.toByteArray()))
        }

        /**
         * Deletes [Identity] object with the given [id] from the storage.
         */
        suspend fun deleteById(id: String): Boolean {
            val table = BackendEnvironment.getTable(tableSpec)
            return table.delete(id)
        }

        /**
         * Returns the list of all identity [id] values in the storage.
         *
         * TODO: this method will be replaced by giving each google login its own list of
         * identities.
         */
        suspend fun listAll(): List<String> {
            return BackendEnvironment.getTable(tableSpec).enumerate()
        }

        private val tableSpec = StorageTableSpec(
            name = "IdentityData",
            supportPartitions = false,
            supportExpiration = false
        )
    }
}