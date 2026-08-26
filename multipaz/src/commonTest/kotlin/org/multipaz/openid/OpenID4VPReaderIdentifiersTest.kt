package org.multipaz.openid

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.cbor.Tstr
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.crypto.buildX509Cert
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.request.RequesterIdentity
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.verification.VerifierIdentity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OpenID4VPReaderIdentifiersTest {

    companion object {
        private const val CLIENT_ID = "https://verifier.example.com/client"
        private const val ORIGIN = "https://verifier.example.com"
        private val UNKNOWN_AKI = ByteString(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        )

        private suspend fun createReader(
            harness: DocumentStoreTestHarness,
            cn: String,
            serial: Long = 1
        ): Pair<ByteString, AsymmetricKey.X509Certified> {
            val rootPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val rootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.anonymous(rootPrivateKey),
                subject = X500Name.fromName("C=US,CN=$cn Root"),
                serial = ASN1Integer(serial),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                crlUrl = "https://example.com/reader/crl"
            )
            val rootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(rootCert)), rootPrivateKey)

            val readerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = rootKey,
                readerKey = readerPrivateKey.publicKey,
                subject = X500Name.fromName("C=US,CN=$cn Reader"),
                dnsName = "verifier.example.com",
                serial = ASN1Integer(serial + 100),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            )
            val readerKey = AsymmetricKey.X509CertifiedExplicit(
                X509CertChain(listOf(readerCert, rootCert)),
                readerPrivateKey
            )
            val aki = ByteString(rootCert.subjectKeyIdentifier!!)
            return Pair(aki, readerKey)
        }

        private suspend fun createReaderWithIntermediate(
            harness: DocumentStoreTestHarness
        ): Triple<ByteString, ByteString, AsymmetricKey.X509Certified> {
            val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val rootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.anonymous(rootKey),
                subject = X500Name.fromName("C=US,CN=3-Tier Reader Root"),
                serial = ASN1Integer(10),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                crlUrl = "https://example.com/reader/root/crl"
            )

            val intermediateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val intermediateCert = buildX509Cert(
                publicKey = intermediateKey.publicKey,
                signingKey = AsymmetricKey.anonymous(rootKey, rootKey.curve.defaultSigningAlgorithm),
                serialNumber = ASN1Integer(11),
                subject = X500Name.fromName("C=US,CN=3-Tier Reader Intermediate CA"),
                issuer = rootCert.subject,
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            ) {
                includeSubjectKeyIdentifier()
                setAuthorityKeyIdentifierToCertificate(rootCert)
                setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN, X509KeyUsage.CRL_SIGN))
                setBasicConstraints(true, 0)
                addExtension(
                    OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid,
                    false,
                    rootCert.getExtensionValue(OID.X509_EXTENSION_CRL_DISTRIBUTION_POINTS.oid)!!
                )
            }

            val readerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val intermediateX509Key = AsymmetricKey.X509CertifiedExplicit(
                X509CertChain(listOf(intermediateCert, rootCert)),
                intermediateKey
            )
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = intermediateX509Key,
                readerKey = readerPrivateKey.publicKey,
                subject = X500Name.fromName("C=US,CN=3-Tier Reader DS"),
                dnsName = "verifier.example.com",
                serial = ASN1Integer(12),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            )
            val readerKey = AsymmetricKey.X509CertifiedExplicit(
                X509CertChain(listOf(readerCert, intermediateCert, rootCert)),
                readerPrivateKey
            )
            val skiRoot = ByteString(rootCert.subjectKeyIdentifier!!)
            val skiIntermediate = ByteString(intermediateCert.subjectKeyIdentifier!!)
            return Triple(skiRoot, skiIntermediate, readerKey)
        }

        private suspend fun addMdl(
            harness: DocumentStoreTestHarness,
            displayName: String,
            readerIdentifiers: List<ByteString> = emptyList(),
        ) {
            harness.provisionMdoc(
                displayName = displayName,
                docType = DrivingLicense.MDL_DOCTYPE,
                data = mapOf(
                    DrivingLicense.MDL_NAMESPACE to listOf(
                        "given_name" to Tstr("Erika"),
                        "family_name" to Tstr("Mustermann"),
                        "resident_address" to Tstr("Sample Street 123"),
                    )
                ),
                readerIdentifiers = readerIdentifiers,
            )
        }

        private suspend fun addPidSdJwtVc(
            harness: DocumentStoreTestHarness,
            displayName: String,
            readerIdentifiers: List<ByteString> = emptyList(),
        ) {
            harness.provisionSdJwtVc(
                displayName = displayName,
                vct = EUPersonalID.EUPID_VCT,
                data = listOf(
                    "given_name" to JsonPrimitive("Erika"),
                    "family_name" to JsonPrimitive("Mustermann"),
                    "address" to buildJsonObject {
                        put("formatted", JsonPrimitive("Sample Street 123, CA 90210, US"))
                    }
                ),
                readerIdentifiers = readerIdentifiers,
            )
        }

        private fun mdlDcql(): JsonObject {
            return Json.parseToJsonElement(
                """
                    {
                      "credentials": [
                        {
                          "id": "mdl_cred",
                          "format": "mso_mdoc",
                          "meta": {
                            "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                          },
                          "claims": [
                            {"path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"]},
                            {"path": ["${DrivingLicense.MDL_NAMESPACE}", "resident_address"]}
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            ).jsonObject
        }

        private fun pidSdJwtDcql(): JsonObject {
            return Json.parseToJsonElement(
                """
                    {
                      "credentials": [
                        {
                          "id": "pid_cred",
                          "format": "dc+sd-jwt",
                          "meta": {
                            "vct_values": ["${EUPersonalID.EUPID_VCT}"]
                          },
                          "claims": [
                            {"path": ["given_name"]},
                            {"path": ["address", "formatted"]}
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            ).jsonObject
        }

        private suspend fun executeOpenID4VP(
            harness: DocumentStoreTestHarness,
            dcql: JsonObject,
            verifierIdentities: List<VerifierIdentity> = emptyList(),
            version: OpenID4VP.Version = OpenID4VP.Version.DRAFT_29,
        ): OpenID4VP.OpenID4VPResponse {
            val nonce = Random.nextBytes(16).toBase64Url()
            val request = OpenID4VP.generateRequest(
                version = version,
                origin = ORIGIN,
                nonce = nonce,
                responseEncryptionKey = null,
                verifierIdentities = verifierIdentities,
                responseMode = OpenID4VP.ResponseMode.DC_API,
                responseUri = null,
                dcqlQuery = dcql,
            )

            // Extract the claims set and requester identities from the request
            val (requestObject, requesterIdentities) = if (verifierIdentities.isEmpty()) {
                Pair(request, emptyList())
            } else if (verifierIdentities.size == 1) {
                val jwt = request["request"]!!.jsonPrimitive.content
                val info = JsonWebSignature.getInfo(jwt)
                val certChain = info.x5c!!
                val clientId = requestObject_clientId(info.claimsSet)
                Pair(info.claimsSet, listOf(OpenID4VPRequesterIdentity(certChain, clientId)))
            } else {
                // Multisigned
                val signatures = request["signatures"]!!
                val payload = request["payload"]!!.jsonPrimitive.content
                val parsedPayload = Json.parseToJsonElement(payload.fromBase64Url().decodeToString()).jsonObject
                val identities = verifierIdentities.map { vi ->
                    OpenID4VPRequesterIdentity(vi.key.certChain, vi.clientId ?: CLIENT_ID)
                }
                Pair(parsedPayload, identities)
            }

            return OpenID4VP.generateResponse(
                version = version,
                preselectedDocuments = emptyList(),
                source = harness.presentmentSource,
                appId = null,
                origin = ORIGIN,
                request = requestObject,
                requesterIdentities = requesterIdentities,
            )
        }

        private fun requestObject_clientId(claimsSet: JsonObject): String {
            return claimsSet["client_id"]?.jsonPrimitive?.content ?: CLIENT_ID
        }
    }

    @Test
    fun mdocMatchingReaderIdentifier_Draft29() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(aki1))

        val response = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey1, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(response.vpToken["vp_token"])
    }

    @Test
    fun mdocNonMatchingReaderIdentifier_Draft29() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (_, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(UNKNOWN_AKI))

        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            executeOpenID4VP(
                harness = harness,
                dcql = mdlDcql(),
                verifierIdentities = listOf(VerifierIdentity(readerKey1, CLIENT_ID)),
                version = OpenID4VP.Version.DRAFT_29
            )
        }
    }

    @Test
    fun mdocUnsignedRequestBlocked_Draft29() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, _) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(aki1))

        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            executeOpenID4VP(
                harness = harness,
                dcql = mdlDcql(),
                verifierIdentities = emptyList(),
                version = OpenID4VP.Version.DRAFT_29
            )
        }
    }

    @Test
    fun mdocUnsignedRequestAllowed_WhenReaderIdentifiersEmpty_Draft29() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        addMdl(harness, "my-mDL-Public", readerIdentifiers = emptyList())

        val response = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = emptyList(),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(response.vpToken["vp_token"])
    }

    @Test
    fun sdJwtMatchingReaderIdentifier_Draft29() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1")
        addPidSdJwtVc(harness, "my-PID-Restricted", readerIdentifiers = listOf(aki1))

        val response = executeOpenID4VP(
            harness = harness,
            dcql = pidSdJwtDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey1, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(response.vpToken["vp_token"])
    }

    @Test
    fun sdJwtNonMatchingReaderIdentifier_Draft29() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (_, readerKey1) = createReader(harness, "Reader 1")
        addPidSdJwtVc(harness, "my-PID-Restricted", readerIdentifiers = listOf(UNKNOWN_AKI))

        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            executeOpenID4VP(
                harness = harness,
                dcql = pidSdJwtDcql(),
                verifierIdentities = listOf(VerifierIdentity(readerKey1, CLIENT_ID)),
                version = OpenID4VP.Version.DRAFT_29
            )
        }
    }

    @Test
    fun sdJwtUnsignedRequestBlocked_Draft29() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, _) = createReader(harness, "Reader 1")
        addPidSdJwtVc(harness, "my-PID-Restricted", readerIdentifiers = listOf(aki1))

        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            executeOpenID4VP(
                harness = harness,
                dcql = pidSdJwtDcql(),
                verifierIdentities = emptyList(),
                version = OpenID4VP.Version.DRAFT_29
            )
        }
    }

    @Test
    fun multipleDocumentsSelectionByReader_OpenID4VP() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1", serial = 1)
        val (aki2, readerKey2) = createReader(harness, "Reader 2", serial = 2)

        addMdl(harness, "doc-Reader1-Only", readerIdentifiers = listOf(aki1))
        addMdl(harness, "doc-Reader2-Only", readerIdentifiers = listOf(aki2))
        addMdl(harness, "doc-Public", readerIdentifiers = emptyList())

        // Query with Reader 1 should succeed (matches doc-Reader1-Only or doc-Public)
        val resp1 = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey1, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(resp1.vpToken["vp_token"])

        // Query with Reader 2 should succeed (matches doc-Reader2-Only or doc-Public)
        val resp2 = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey2, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(resp2.vpToken["vp_token"])

        // Query with Unknown Reader should succeed (matches doc-Public)
        val (_, readerKeyUnknown) = createReader(harness, "Unknown Reader", serial = 3)
        val respUnknown = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKeyUnknown, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(respUnknown.vpToken["vp_token"])

        // Unsigned request should succeed (matches doc-Public)
        val respUnsigned = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = emptyList(),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(respUnsigned.vpToken["vp_token"])
    }

    @Test
    fun mdoc3TierReaderCertChain_MatchesIntermediateOrRoot_OpenID4VP() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, readerKey) = createReaderWithIntermediate(harness)

        // 1. Match intermediate SKI
        addMdl(harness, "my-mDL-Intermediate", readerIdentifiers = listOf(skiIntermediate))
        val respIntermediate = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(respIntermediate.vpToken["vp_token"])

        // 2. Match root SKI
        val harness2 = DocumentStoreTestHarness()
        harness2.initialize()
        addMdl(harness2, "my-mDL-Root", readerIdentifiers = listOf(skiRoot))
        val respRoot = executeOpenID4VP(
            harness = harness2,
            dcql = mdlDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(respRoot.vpToken["vp_token"])

        // 3. Non-matching AKI fails
        val harness3 = DocumentStoreTestHarness()
        harness3.initialize()
        addMdl(harness3, "my-mDL-Unknown", readerIdentifiers = listOf(UNKNOWN_AKI))
        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            executeOpenID4VP(
                harness = harness3,
                dcql = mdlDcql(),
                verifierIdentities = listOf(VerifierIdentity(readerKey, CLIENT_ID)),
                version = OpenID4VP.Version.DRAFT_29
            )
        }
    }

    @Test
    fun sdJwt3TierReaderCertChain_MatchesIntermediateOrRoot_OpenID4VP() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (skiRoot, skiIntermediate, readerKey) = createReaderWithIntermediate(harness)

        // 1. Match intermediate SKI
        addPidSdJwtVc(harness, "my-PID-Intermediate", readerIdentifiers = listOf(skiIntermediate))
        val respIntermediate = executeOpenID4VP(
            harness = harness,
            dcql = pidSdJwtDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(respIntermediate.vpToken["vp_token"])

        // 2. Match root SKI
        val harness2 = DocumentStoreTestHarness()
        harness2.initialize()
        addPidSdJwtVc(harness2, "my-PID-Root", readerIdentifiers = listOf(skiRoot))
        val respRoot = executeOpenID4VP(
            harness = harness2,
            dcql = pidSdJwtDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(respRoot.vpToken["vp_token"])
    }

    @Test
    fun multisignedRequest_OpenID4VP() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1", serial = 1)
        val (_, readerKey2) = createReader(harness, "Reader 2", serial = 2)

        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(aki1))

        // Multisigned with Reader 2 (unknown) and Reader 1 (matching)
        val resp = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = listOf(
                VerifierIdentity(readerKey2, "https://verifier2.example.com/client"),
                VerifierIdentity(readerKey1, CLIENT_ID)
            ),
            version = OpenID4VP.Version.DRAFT_29
        )
        assertNotNull(resp.vpToken["vp_token"])
    }

    @Test
    fun mdocMatchingReaderIdentifier_Draft24() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (aki1, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(aki1))

        val response = executeOpenID4VP(
            harness = harness,
            dcql = mdlDcql(),
            verifierIdentities = listOf(VerifierIdentity(readerKey1, CLIENT_ID)),
            version = OpenID4VP.Version.DRAFT_24
        )
        assertNotNull(response.vpToken["vp_token"])
    }

    @Test
    fun mdocNonMatchingReaderIdentifier_Draft24() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val (_, readerKey1) = createReader(harness, "Reader 1")
        addMdl(harness, "my-mDL-Restricted", readerIdentifiers = listOf(UNKNOWN_AKI))

        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            executeOpenID4VP(
                harness = harness,
                dcql = mdlDcql(),
                verifierIdentities = listOf(VerifierIdentity(readerKey1, CLIENT_ID)),
                version = OpenID4VP.Version.DRAFT_24
            )
        }
    }
}
