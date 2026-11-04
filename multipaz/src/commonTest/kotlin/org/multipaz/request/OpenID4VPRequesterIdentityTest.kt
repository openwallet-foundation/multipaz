package org.multipaz.request

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.testUtilSetupCryptoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class OpenID4VPRequesterIdentityTest {
    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private fun generalName(tag: Int, value: String) = ASN1TaggedObject(
        ASN1TagClass.CONTEXT_SPECIFIC,
        ASN1Encoding.PRIMITIVE,
        tag,
        value.encodeToByteArray()
    )

    private suspend fun certChain(subjectAltNames: List<ASN1TaggedObject>?): X509CertChain {
        val key = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val builder = X509Cert.Builder(
            publicKey = key.publicKey,
            signingKey = AsymmetricKey.anonymous(key, key.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Verifier"),
            issuer = X500Name.fromName("CN=Verifier"),
            validFrom = now - 1.hours,
            validUntil = now + 1.hours
        )
        if (subjectAltNames != null) {
            builder.addExtension(
                OID.X509_EXTENSION_SUBJECT_ALT_NAME.oid,
                false,
                ASN1.encode(ASN1Sequence(subjectAltNames))
            )
        }
        return X509CertChain(listOf(builder.build()))
    }

    @Test
    fun subjectAlternativeNameDnsNamesSkipsOtherNames() = runTest {
        val chain = certChain(
            listOf(
                generalName(2, "verifier.example"),
                generalName(6, "https://verifier.example"),
                generalName(2, "other.example"),
            )
        )
        assertEquals(
            listOf("verifier.example", "other.example"),
            chain.certificates.first().subjectAlternativeNameDnsNames
        )
    }

    @Test
    fun subjectAlternativeNameDnsNamesWithoutExtension() = runTest {
        assertEquals(emptyList(), certChain(null).certificates.first().subjectAlternativeNameDnsNames)
    }

    @Test
    fun x509SanDnsMatchingAnyDnsName() = runTest {
        val chain = certChain(listOf(generalName(2, "verifier.example"), generalName(2, "other.example")))
        val identity = OpenID4VPRequesterIdentity.fromSignedRequest(chain, "x509_san_dns:other.example")
        assertEquals("x509_san_dns:other.example", identity.clientId)
        assertEquals(chain, identity.certChain)
    }

    @Test
    fun x509SanDnsMatchesIgnoringCase() = runTest {
        val chain = certChain(listOf(generalName(2, "Verifier.Example")))
        OpenID4VPRequesterIdentity.fromSignedRequest(chain, "x509_san_dns:verifier.example")
    }

    @Test
    fun x509SanDnsNotInCertificateIsRejected() = runTest {
        val chain = certChain(listOf(generalName(2, "verifier.example")))
        assertFailsWith<IllegalArgumentException> {
            OpenID4VPRequesterIdentity.fromSignedRequest(chain, "x509_san_dns:impersonated.example")
        }
    }

    @Test
    fun x509SanDnsOnlyInOtherNameTypesIsRejected() = runTest {
        val chain = certChain(listOf(generalName(6, "verifier.example")))
        assertFailsWith<IllegalArgumentException> {
            OpenID4VPRequesterIdentity.fromSignedRequest(chain, "x509_san_dns:verifier.example")
        }
    }

    @Test
    fun x509SanDnsWithoutSubjectAlternativeNameIsRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            OpenID4VPRequesterIdentity.fromSignedRequest(certChain(null), "x509_san_dns:verifier.example")
        }
    }

    @Test
    fun x509SanDnsWithEmptyNameIsRejected() = runTest {
        val chain = certChain(listOf(generalName(2, "")))
        assertFailsWith<IllegalArgumentException> {
            OpenID4VPRequesterIdentity.fromSignedRequest(chain, "x509_san_dns:")
        }
    }

    @Test
    fun otherPrefixesAreNotChecked() = runTest {
        val chain = certChain(null)
        OpenID4VPRequesterIdentity.fromSignedRequest(chain, "redirect_uri:https://verifier.example/callback")
        OpenID4VPRequesterIdentity.fromSignedRequest(chain, "verifier.example")
    }
}
