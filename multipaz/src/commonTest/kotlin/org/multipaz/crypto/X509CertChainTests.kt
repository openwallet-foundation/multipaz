package org.multipaz.crypto

import kotlinx.coroutines.test.runTest
import org.multipaz.asn1.ASN1Integer
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.truncateToWholeSeconds
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class X509CertChainTests {
    val now = Clock.System.now()
    val validFrom = (now - 1.days).truncateToWholeSeconds()
    val validUntil = (now + 2.days).truncateToWholeSeconds()
    lateinit var rootKey: AsymmetricKey
    lateinit var intermediateKey: AsymmetricKey
    lateinit var leafKey: AsymmetricKey

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private suspend fun initKeys() {
        rootKey = AsymmetricKey.ephemeral()
        intermediateKey = AsymmetricKey.ephemeral()
        leafKey = AsymmetricKey.ephemeral()
    }

    @Test
    fun testVerifyBasic() = runTest {
        initKeys()
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Root"),
            issuer = X500Name.fromName("CN=Root"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(ca = true, pathLenConstraint = 1)
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val intermediateCert = buildX509Cert(
            publicKey = intermediateKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Intermediate"),
            issuer = rootCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(ca = true, pathLenConstraint = 0)
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }
        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = intermediateKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Leaf"),
            issuer = intermediateCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(intermediateCert)
        }

        X509CertChain(listOf(leafCert, intermediateCert, rootCert)).validate(now)

        // Negative tests: In the reverse order, it shouldn't validate
        // NB: signatures are always validated first
        assertFailsWith(X509CertChainValidationException.Signature::class) {
            X509CertChain(listOf(rootCert, intermediateCert)).validate(now)
        }
        assertFailsWith(X509CertChainValidationException.Signature::class) {
            X509CertChain(listOf(intermediateCert, leafCert)).validate(now)
        }
        assertFailsWith(X509CertChainValidationException.Signature::class) {
            X509CertChain(listOf(rootCert, leafCert)).validate(now)
        }
    }

    @Test
    fun testVerifySubjectIssuer() = runTest {
        initKeys()
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Root"),
            issuer = X500Name.fromName("CN=Root"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(ca = true, pathLenConstraint = 0)
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Leaf"),
            issuer = X500Name.fromName("CN=Foobar"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }

        assertFailsWith(X509CertChainValidationException.SubjectIssuerMismatch::class) {
            X509CertChain(listOf(leafCert, rootCert)).validate(now)
        }
    }

    @Test
    fun testVerifyExpiration() = runTest {
        initKeys()
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Root"),
            issuer = X500Name.fromName("CN=Root"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(ca = true, pathLenConstraint = 0)
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Leaf"),
            issuer = rootCert.subject,
            validFrom = validFrom,
            validUntil = now - 1.seconds,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }

        assertFailsWith(X509CertChainValidationException.Expired::class) {
            X509CertChain(listOf(leafCert, rootCert)).validate(now)
        }
    }

    @Test
    fun testVerifyNotYetValid() = runTest {
        initKeys()
        val now = Clock.System.now()
        val validFrom = (now - 1.days).truncateToWholeSeconds()
        val validUntil = (now + 2.days).truncateToWholeSeconds()
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Root"),
            issuer = X500Name.fromName("CN=Root"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(ca = true, pathLenConstraint = 0)
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Leaf"),
            issuer = rootCert.subject,
            validFrom = now + 1.seconds,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }

        assertFailsWith(X509CertChainValidationException.NotYetValid::class) {
            X509CertChain(listOf(leafCert, rootCert)).validate(now)
        }
    }

    @Test
    fun testVerifyBasicConstraints() = runTest {
        initKeys()
        val now = Clock.System.now()
        val validFrom = (now - 1.days).truncateToWholeSeconds()
        val validUntil = (now + 2.days).truncateToWholeSeconds()
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Root"),
            issuer = X500Name.fromName("CN=Root"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Leaf"),
            issuer = rootCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }

        assertFailsWith(X509CertChainValidationException.BasicConstraintsMissing::class) {
            X509CertChain(listOf(leafCert, rootCert)).validate(now)
        }

        X509CertChain(listOf(leafCert, rootCert)).validate(now, requireBasicConstraints = false)
    }

    @Test
    fun testVerifyBasicConstraintsCA() = runTest {
        initKeys()
        val now = Clock.System.now()
        val validFrom = (now - 1.days).truncateToWholeSeconds()
        val validUntil = (now + 2.days).truncateToWholeSeconds()
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Root"),
            issuer = X500Name.fromName("CN=Root"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setBasicConstraints(ca = false, pathLenConstraint = 0)
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Leaf"),
            issuer = rootCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }

        assertFailsWith(X509CertChainValidationException.BasicConstraintsNotCA::class) {
            X509CertChain(listOf(leafCert, rootCert)).validate(now)
        }

        assertFailsWith(X509CertChainValidationException.BasicConstraintsNotCA::class) {
            X509CertChain(listOf(leafCert, rootCert)).validate(now)
        }
    }

    @Test
    fun testVerifyPathLength() = runTest {
        initKeys()
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Root"),
            issuer = X500Name.fromName("CN=Root"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(ca = true, pathLenConstraint = 0)
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val intermediateCert = buildX509Cert(
            publicKey = intermediateKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Intermediate"),
            issuer = rootCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setKeyUsage(setOf(X509KeyUsage.KEY_CERT_SIGN))
            setBasicConstraints(ca = true, pathLenConstraint = 0)
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }
        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = intermediateKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Leaf"),
            issuer = intermediateCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(intermediateCert)
        }

        assertFailsWith(X509CertChainValidationException.BasicConstraintsPathLength::class) {
            X509CertChain(listOf(leafCert, intermediateCert, rootCert)).validate(now)
        }
    }

    @Test
    fun testVerifyKeyUsage() = runTest {
        initKeys()
        val now = Clock.System.now()
        val validFrom = (now - 1.days).truncateToWholeSeconds()
        val validUntil = (now + 2.days).truncateToWholeSeconds()
        val rootCert = buildX509Cert(
            publicKey = rootKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Root"),
            issuer = X500Name.fromName("CN=Root"),
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            setBasicConstraints(ca = true, pathLenConstraint = 0)
            includeSubjectKeyIdentifier()
            includeAuthorityKeyIdentifierAsSubjectKeyIdentifier()
        }
        val leafCert = buildX509Cert(
            publicKey = leafKey.publicKey,
            signingKey = rootKey,
            serialNumber = ASN1Integer.fromRandom(128),
            subject = X500Name.fromName("CN=Leaf"),
            issuer = rootCert.subject,
            validFrom = validFrom,
            validUntil = validUntil,
        ) {
            includeSubjectKeyIdentifier()
            setAuthorityKeyIdentifierToCertificate(rootCert)
        }

        assertFailsWith(X509CertChainValidationException.KeyUsageMissing::class) {
            X509CertChain(listOf(leafCert, rootCert)).validate(now)
        }
    }

    @Test
    fun testVerifyWithBothEcAndRsaKeys() = runTest {
        // This is the Web PKI certificate chain for www.multipaz.org as of late Dec 2025.
        // It includes certificates with both RSA and EC keys.
        val multipazOrgCertChain = X509CertChain(certificates = listOf(
            X509Cert.fromPem(
                """
                    -----BEGIN CERTIFICATE-----
                    MIIDjDCCAxOgAwIBAgISBjEjL0KsOxG2UNR/d0/YmQf0MAoGCCqGSM49BAMDMDIx
                    CzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1MZXQncyBFbmNyeXB0MQswCQYDVQQDEwJF
                    ODAeFw0yNTEyMjUxNjIxMjZaFw0yNjAzMjUxNjIxMjVaMBsxGTAXBgNVBAMTEHd3
                    dy5tdWx0aXBhei5vcmcwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARh+nleShTg
                    TjNQTM6X3n7Z0MyG9b80+8Hh+OjINsDeJh32obHyOITnabVRc+sGtMjjpHc+hDjJ
                    6mycC55vSB01o4ICHjCCAhowDgYDVR0PAQH/BAQDAgeAMB0GA1UdJQQWMBQGCCsG
                    AQUFBwMBBggrBgEFBQcDAjAMBgNVHRMBAf8EAjAAMB0GA1UdDgQWBBStjI5l1SkZ
                    bY3Gqt6jHxdjMSQOajAfBgNVHSMEGDAWgBSPDROi9i5+0VBsMxg4XVmOI3KRyjAy
                    BggrBgEFBQcBAQQmMCQwIgYIKwYBBQUHMAKGFmh0dHA6Ly9lOC5pLmxlbmNyLm9y
                    Zy8wGwYDVR0RBBQwEoIQd3d3Lm11bHRpcGF6Lm9yZzATBgNVHSAEDDAKMAgGBmeB
                    DAECATAuBgNVHR8EJzAlMCOgIaAfhh1odHRwOi8vZTguYy5sZW5jci5vcmcvMTE3
                    LmNybDCCAQMGCisGAQQB1nkCBAIEgfQEgfEA7wB2AMs49xWJfIShRF9bwd37yW7y
                    mlnNRwppBYWwyxTDFFjnAAABm1aGPZMAAAQDAEcwRQIgVSwcD9t/+WEcDLX8MG1N
                    VIZK8+i1mSGxUIYSl7MzAkwCIQDY3uft10sgc5EouK27Cd8+Ph7YqQw9obwyH5ej
                    LQKoqQB1ANFuqaVoB35mNaA/N6XdvAOlPEESFNSIGPXpMbMjy5UEAAABm1aGPlgA
                    AAQDAEYwRAIgLlYXPn2R4uBfeXlvD8yeM8BX/g5u74CZBEGauikpdEECIFEthLlE
                    vsm3z5Fz8Bd/m8lxeeaPJTUstRrYwTeldq2IMAoGCCqGSM49BAMDA2cAMGQCMBlf
                    iEEuWaCxLyb/xFAqJG5eubVwTlIxZPHw9Ok4gxkJU5I7MwKbn5EqR0+I6DN3igIw
                    IKt/FAH6/fkbJHzFJNLKKjWX8yC6M5TJ9tXyOxttbYLowx/Qt1QjY1KLoR8ruq/b
                    -----END CERTIFICATE-----
                """.trimIndent()
            ),
            X509Cert.fromPem(
                """
                    -----BEGIN CERTIFICATE-----
                    MIIEVjCCAj6gAwIBAgIQY5WTY8JOcIJxWRi/w9ftVjANBgkqhkiG9w0BAQsFADBP
                    MQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFy
                    Y2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMTAeFw0yNDAzMTMwMDAwMDBa
                    Fw0yNzAzMTIyMzU5NTlaMDIxCzAJBgNVBAYTAlVTMRYwFAYDVQQKEw1MZXQncyBF
                    bmNyeXB0MQswCQYDVQQDEwJFODB2MBAGByqGSM49AgEGBSuBBAAiA2IABNFl8l7c
                    S7QMApzSsvru6WyrOq44ofTUOTIzxULUzDMMNMchIJBwXOhiLxxxs0LXeb5GDcHb
                    R6EToMffgSZjO9SNHfY9gjMy9vQr5/WWOrQTZxh7az6NSNnq3u2ubT6HTKOB+DCB
                    9TAOBgNVHQ8BAf8EBAMCAYYwHQYDVR0lBBYwFAYIKwYBBQUHAwIGCCsGAQUFBwMB
                    MBIGA1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0OBBYEFI8NE6L2Ln7RUGwzGDhdWY4j
                    cpHKMB8GA1UdIwQYMBaAFHm0WeZ7tuXkAXOACIjIGlj26ZtuMDIGCCsGAQUFBwEB
                    BCYwJDAiBggrBgEFBQcwAoYWaHR0cDovL3gxLmkubGVuY3Iub3JnLzATBgNVHSAE
                    DDAKMAgGBmeBDAECATAnBgNVHR8EIDAeMBygGqAYhhZodHRwOi8veDEuYy5sZW5j
                    ci5vcmcvMA0GCSqGSIb3DQEBCwUAA4ICAQBnE0hGINKsCYWi0Xx1ygxD5qihEjZ0
                    RI3tTZz1wuATH3ZwYPIp97kWEayanD1j0cDhIYzy4CkDo2jB8D5t0a6zZWzlr98d
                    AQFNh8uKJkIHdLShy+nUyeZxc5bNeMp1Lu0gSzE4McqfmNMvIpeiwWSYO9w82Ob8
                    otvXcO2JUYi3svHIWRm3+707DUbL51XMcY2iZdlCq4Wa9nbuk3WTU4gr6LY8MzVA
                    aDQG2+4U3eJ6qUF10bBnR1uuVyDYs9RhrwucRVnfuDj29CMLTsplM5f5wSV5hUpm
                    Uwp/vV7M4w4aGunt74koX71n4EdagCsL/Yk5+mAQU0+tue0JOfAV/R6t1k+Xk9s2
                    HMQFeoxppfzAVC04FdG9M+AC2JWxmFSt6BCuh3CEey3fE52Qrj9YM75rtvIjsm/1
                    Hl+u//Wqxnu1ZQ4jpa+VpuZiGOlWrqSP9eogdOhCGisnyewWJwRQOqK16wiGyZeR
                    xs/Bekw65vwSIaVkBruPiTfMOo0Zh4gVa8/qJgMbJbyrwwG97z/PRgmLKCDl8z3d
                    tA0Z7qq7fta0Gl24uyuB05dqI5J1LvAzKuWdIjT1tP8qCoxSE/xpix8hX2dt3h+/
                    jujUgFPFZ0EVZ0xSyBNRF3MboGZnYXFUxpNjTWPKpagDHJQmqrAcDmWJnMsFY3jS
                    u1igv3OefnWjSQ==
                    -----END CERTIFICATE-----
                """.trimIndent()
            ),
            X509Cert.fromPem(
                """
                    -----BEGIN CERTIFICATE-----
                    MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
                    TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
                    cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
                    WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
                    ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
                    MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
                    h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
                    0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
                    A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
                    T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
                    B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
                    B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
                    KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
                    OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
                    jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
                    qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
                    rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
                    HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
                    hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
                    ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
                    3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
                    NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
                    ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
                    TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
                    jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
                    oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
                    4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
                    mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
                    emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
                    -----END CERTIFICATE-----
                """.trimIndent()
            ))
        )
        multipazOrgCertChain.validate(Instant.parse("2026-03-09T00:00:00Z"))
    }
}