package org.multipaz.openid4vci.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.verifier.Openid4VpVerifierModel
import org.multipaz.verifier.fromCbor
import org.multipaz.verifier.toCbor
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.cache
import org.multipaz.server.getBaseUrl
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

suspend fun getReaderIdentity(): Openid4VpVerifierModel.ReaderIdentity {
    val baseUrl = BackendEnvironment.getBaseUrl()
    return BackendEnvironment.cache(
        clazz = Openid4VpVerifierModel.ReaderIdentity::class,
        key = baseUrl
    ) { _, _ ->
        lock.withLock {
            loadReaderIdentity(baseUrl)
        }
    }
}

private val lock = Mutex()

private suspend fun loadReaderIdentity(baseUrl: String): Openid4VpVerifierModel.ReaderIdentity {
    val table = BackendEnvironment.getTable(readerRootTableSpec)
    val existingIdentityBytes = table.get(baseUrl)
    if (existingIdentityBytes != null) {
        return Openid4VpVerifierModel.ReaderIdentity.fromCbor(existingIdentityBytes.toByteArray())
    }
    val validFrom = Clock.System.now() - 10.seconds
    val validUntil = validFrom + 3650.days
    val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
    val readerRootKeySubject = "CN=$baseUrl"
    val readerRootKeyCertificate = MdocUtil.generateReaderRootCertificate(
        readerRootKey = AsymmetricKey.anonymous(readerRootKey),
        subject = X500Name.fromName(readerRootKeySubject),
        serial = ASN1Integer(1L),
        validFrom = validFrom,
        validUntil = validUntil,
        crlUrl = "$baseUrl/crl"
    )
    val readerIdentity = Openid4VpVerifierModel.ReaderIdentity(
        privateKey = readerRootKey,
        certificateChain = X509CertChain(listOf(readerRootKeyCertificate))
    )
    table.insert(
        key = baseUrl,
        data = ByteString(readerIdentity.toCbor()),
        expiration = validUntil
    )
    return readerIdentity
}

private val readerRootTableSpec = StorageTableSpec(
    name = "ReaderRoot",
    supportPartitions = false,
    supportExpiration = true
)