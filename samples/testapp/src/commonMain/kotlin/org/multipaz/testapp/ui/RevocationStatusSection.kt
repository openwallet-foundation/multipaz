package org.multipaz.testapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.decodeToString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.credential.Credential
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.revocation.IdentifierList
import org.multipaz.revocation.RevocationStatus
import org.multipaz.revocation.StatusList
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.util.Logger
import org.multipaz.util.toHex

@Composable
fun RevocationStatusSection(credential: Credential) {
    val coroutineScope = rememberCoroutineScope()
    val revocationData = remember { mutableStateOf<RevocationData?>(null) }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            revocationData.value = extractRevocationData(credential)
        }
    }

    when (val status = revocationData.value?.revocationStatus) {
        is RevocationStatus.Unknown -> {
            Text(
                text = "Revocation Info Not Parsed",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
        is RevocationStatus.StatusList -> StatusListCheckSection(status, revocationData.value!!.certChain)
        is RevocationStatus.IdentifierList -> IdentifierListCheckSection(status, revocationData.value!!.certChain)
        else -> {
            Text(
                text = "Revocation Info Not Found",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

private suspend fun extractRevocationData(credential: Credential): RevocationData? {
    return when (credential) {
        is MdocCredential -> {
            val issuerSigned = Cbor.decode(credential.issuerProvidedData.toByteArray())
            val issuerAuth = issuerSigned["issuerAuth"].asCoseSign1
            val certChain = issuerAuth.unprotectedHeaders[
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN)
            ]!!.asX509CertChain
            val tagged = Cbor.decode(issuerAuth.payload!!)
            val mso = MobileSecurityObject.fromDataItem(Cbor.decode((tagged as Tagged).taggedItem.asBstr))
            mso.revocationStatus?.let { RevocationData(it, certChain) }
        }
        is SdJwtVcCredential -> {
            val sdjwt = SdJwt.fromCompactSerialization(credential.issuerProvidedData.decodeToString())
            val certChain = sdjwt.x5c ?: return null
            sdjwt.revocationStatus?.let { RevocationData(it, certChain) }
        }
        else -> null
    }
}

private class RevocationData(
    val revocationStatus: RevocationStatus,
    val certChain: X509CertChain
)

private val STATUSLIST_JWT = ContentType("application", "statuslist+jwt")
private val STATUSLIST_CWT = ContentType("application", "statuslist+cwt")

@Composable
private fun StatusListCheckSection(
    status: RevocationStatus.StatusList,
    certChain: X509CertChain
) {
    val coroutineScope = rememberCoroutineScope()
    val statusText = remember { mutableStateOf("Click to check status") }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable {
                coroutineScope.launch {
                    val client = HttpClient(TestAppConfiguration.httpClientEngineFactory)
                    val response = client.get(status.uri) {
                        // CWT is more compact, so prefer that
                        headers.append(
                            name = HttpHeaders.Accept,
                            value = "$STATUSLIST_CWT, $STATUSLIST_JWT;q=0.9"
                        )
                    }
                    if (response.status != HttpStatusCode.OK) {
                        statusText.value = "HTTP Status: ${response.status}"
                    } else {
                        try {
                            val cert = status.certificate ?: certChain.certificates.first()
                            val statusList = when (val type = response.contentType()) {
                                STATUSLIST_JWT -> StatusList.fromJwt(
                                    jwt = response.readRawBytes().decodeToString(),
                                    publicKey = cert.ecPublicKey
                                )
                                STATUSLIST_CWT -> StatusList.fromCwt(
                                    cwt = response.readRawBytes(),
                                    publicKey = cert.ecPublicKey
                                )
                                else -> throw IllegalStateException("Unknown type: $type")
                            }

                            statusText.value = when (val code = statusList[status.idx]) {
                                0 -> "Valid"
                                1 -> "Invalid"
                                2 -> "Suspended"
                                else -> "Unexpected status $code"
                            }
                        } catch (err: Exception) {
                            Logger.e(TAG, "Failed to parse status list", err)
                            statusText.value = "Failed to parse status list"
                        }
                    }
                }
            }
    ) {
        Text(
            text = "Status List Revocation",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Index: ${status.idx}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = "Url: ${status.uri}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = statusText.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
fun IdentifierListCheckSection(
    status: RevocationStatus.IdentifierList,
    certChain: X509CertChain
) {
    val coroutineScope = rememberCoroutineScope()
    val statusText = remember { mutableStateOf("Click to check status") }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable {
                coroutineScope.launch {
                    val client = HttpClient(TestAppConfiguration.httpClientEngineFactory)
                    val response = client.get(status.uri)
                    if (response.status != HttpStatusCode.OK) {
                        statusText.value = "HTTP Status: ${response.status}"
                    } else {
                        val cert = status.certificate ?: certChain.certificates.first()
                        try {
                            val identifierList = IdentifierList.fromCwt(
                                cwt = response.readRawBytes(),
                                publicKey = cert.ecPublicKey
                            )
                            statusText.value = if (identifierList.contains(status.id)) {
                                "Invalid"
                            } else {
                                "Valid"
                            }
                        } catch (err: Exception) {
                            Logger.e(TAG, "Failed to parse identifier list", err)
                            statusText.value = "Failed to parse identifier list"
                        }
                    }
                }
            }
    ) {
        Text(
            text = "Identifier List Revocation",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Identifier: ${status.id.toByteArray().toHex()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = "Url: ${status.uri}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = statusText.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

private const val TAG = "RevocationStatusSection"
