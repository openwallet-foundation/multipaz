package org.multipaz.openid4vci.credential

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.PublicKey
import org.multipaz.documenttype.knowntypes.SampleData
import org.multipaz.mdoc.issuersigned.buildIssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObject
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.revocation.RevocationStatus
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.getBaseUrl
import org.multipaz.util.toBase64Url
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket

/**
 * [CredentialFactory] for non-key-bound (keyless) [UtopiaMovieTicket] credentials in ISO mdoc format.
 */
class CredentialFactoryUtopiaMovieTicketMdoc : CredentialFactory {
    override val configurationId: String
        get() = "utopia_movie_ticket_mdoc"

    override val scope: String
        get() = "movie"

    override val format
        get() = FORMAT

    override val requireKeyAttestation: Boolean
        get() = false

    override val proofSigningAlgorithms: List<String>
        get() = emptyList() // keyless

    override val cryptographicBindingMethods: List<String>
        get() = emptyList() // keyless

    override val name: String
        get() = "Movie ticket"

    override val logo: String
        get() = "movie_ticket.jpg"

    override suspend fun mint(
        systemOfRecordData: DataItem,
        authenticationKey: PublicKey?,
        credentialId: CredentialId
    ): MintedCredential {
        check(authenticationKey == null)

        val records = systemOfRecordData["records"]
        if (!records.hasKey("movie")) {
            throw IllegalArgumentException("No movie ticket for this person")
        }
        val ticket = records["movie"].asMap.values.firstOrNull() ?: buildCborMap { }

        val now = Clock.System.now()
        val timeSigned = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validFrom = Instant.fromEpochSeconds(now.epochSeconds, 0)
        val validUntil = validFrom + 30.days

        val issuerNamespaces = buildIssuerNamespaces {
            addNamespace(UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE) {
                addDataElement(
                    "ticket_id",
                    ticket.getOrNull("ticket_id") ?: SampleData.TICKET_NUMBER.toString().toDataItem()
                )
                addDataElement(
                    "cinema",
                    ticket.getOrNull("cinema") ?: SampleData.CINEMA.toDataItem()
                )
                addDataElement(
                    "movie",
                    ticket.getOrNull("movie")
                        ?: ticket.getOrNull("movie_title")
                        ?: SampleData.MOVIE.toDataItem()
                )
                val showDateTime = ticket.getOrNull("show_date_time")?.let { item ->
                    if (item is Tstr) item.asTstr.toDataItemDateTimeString() else item
                } ?: SampleData.MOVIE_DATE_TIME.toDataItemDateTimeString()
                addDataElement("show_date_time", showDateTime)
                ticket.getOrNull("movie_rating")?.let {
                    addDataElement("movie_rating", it)
                }
                addDataElement(
                    "theater_id",
                    ticket.getOrNull("theater_id") ?: SampleData.THEATRE_NAME.toDataItem()
                )
                addDataElement(
                    "seat_id",
                    ticket.getOrNull("seat_id") ?: SampleData.THEATRE_SEAT.toDataItem()
                )
                ticket.getOrNull("parking_option")?.let {
                    addDataElement("parking_option", it)
                }
                ticket.getOrNull("poster")?.let {
                    addDataElement("poster", it)
                }
            }
        }

        val baseUrl = BackendEnvironment.getBaseUrl()
        val revocationStatus = RevocationStatus.StatusList(
            idx = credentialId.index,
            uri = "$baseUrl/status_list/${credentialId.bucket}",
            certificate = null
        )

        val mso = MobileSecurityObject(
            version = "1.0",
            docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
            signedAt = timeSigned,
            validFrom = validFrom,
            validUntil = validUntil,
            expectedUpdate = null,
            digestAlgorithm = Algorithm.SHA256,
            valueDigests = issuerNamespaces.getValueDigests(Algorithm.SHA256),
            deviceKey = null,
            revocationStatus = revocationStatus
        )
        val taggedEncodedMso = Cbor.encode(
            Tagged(
                Tagged.ENCODED_CBOR,
                Bstr(Cbor.encode(mso.toDataItem()))
            )
        )

        val signingKey = getSigningKey()
        val signatureAlgorithm = signingKey.algorithm.curve?.defaultSigningAlgorithm ?: signingKey.algorithm
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_ALG),
                signatureAlgorithm.coseAlgorithmIdentifier!!.toDataItem()
            ),
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_X5T),
                signingKey.certChain.toCoseX5t()
            )
        )
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                signingKey.certChain.toCoseX5Chain()
            )
        )
        val encodedIssuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                signingKey,
                taggedEncodedMso,
                true,
                protectedHeaders,
                unprotectedHeaders
            ).toDataItem()
        )
        val issuerProvidedAuthenticationData = Cbor.encode(
            buildCborMap {
                put("nameSpaces", issuerNamespaces.toDataItem())
                put("issuerAuth", RawCbor(encodedIssuerAuth))
            }
        )

        return MintedCredential(
            credential = issuerProvidedAuthenticationData.toBase64Url(),
            creation = validFrom,
            expiration = validUntil
        )
    }

    companion object {
        private val FORMAT = CredentialFormat.Mdoc(UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE)
    }
}
