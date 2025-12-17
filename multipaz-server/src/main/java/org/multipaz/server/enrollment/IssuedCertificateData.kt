package org.multipaz.server.enrollment

import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.fromBase64Url
import kotlin.time.Instant

/**
 * Data that describes a certificate issued on this server, recorded in the database.
 */
@CborSerializable
internal data class IssuedCertificateData(
    val subject: String,
    val publicKey: EcPublicKey,
    val expiration: Instant
) {
    companion object {
        suspend fun recordIssuedCertificate(
            serverIdentity: ServerIdentity,
            publicKey: EcPublicKey,
            subject: X500Name,
            expiration: Instant
        ): ASN1Integer {
            val issuedCertificates = BackendEnvironment.getTable(issuedCertificatesTable)
            val key = issuedCertificates.insert(
                key = null,
                partitionId = serverIdentity.name,
                data = ByteString(IssuedCertificateData(
                    subject = subject.name,
                    publicKey = publicKey,
                    expiration = expiration
                ).toCbor()),
                expiration = expiration
            )
            return keyToSerial(key)
        }

        internal suspend fun enumerateIssuedCertificates(
            serverIdentity: ServerIdentity,
            afterKey: String = "",
            limit: Int = Int.MAX_VALUE
        ): List<Pair<ASN1Integer, IssuedCertificateData>> =
            BackendEnvironment.getTable(issuedCertificatesTable)
                .enumerateWithData(serverIdentity.name, afterKey, limit).map { (key, data) ->
                    Pair(keyToSerial(key), fromCbor(data.toByteArray()))
                }

        private fun keyToSerial(key: String): ASN1Integer {
            // Create serial number from the key
            val data = key.fromBase64Url()
            return ASN1Integer(byteArrayOf(data.size.toByte()) + data)
        }

        private val issuedCertificatesTable = StorageTableSpec(
            name = "IssuedCertificates",
            supportPartitions = true,
            supportExpiration = true
        )
    }
}