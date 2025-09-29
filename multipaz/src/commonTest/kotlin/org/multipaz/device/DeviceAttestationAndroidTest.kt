package org.multipaz.device

import org.multipaz.util.fromBase64Url
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509Cert
import kotlin.collections.setOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test

class DeviceAttestationAndroidTest {
    @Test
    fun testValidationPixel7a() {
        val deviceAttestation = DeviceAttestation.fromCbor(ATTESTATION_PIXEL7A)
        deviceAttestation.validate(
            DeviceAttestationValidationData(
                attestationChallenge = ATTESTATION_CHALLENGE_PIXEL7A.encodeToByteString(),
                iosReleaseBuild = false,
                iosAppIdentifiers = setOf(),
                androidGmsAttestation = true,
                androidVerifiedBootGreen = true,
                androidRequiredKeyMintSecurityLevel = AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
                androidAppSignatureCertificateDigests = setOf(
                    ByteString("VEpxrWMf2GFLy2_HHTuN7xlW5fy6mKhVAmRADo4aLh0".fromBase64Url())
                ),
                androidAppPackageNames = setOf("org.multipaz_credential.wallet")
            )
        )
    }

    @Test
    fun testAssertionPixel7a() {
        val deviceAttestation = DeviceAttestation.fromCbor(ATTESTATION_PIXEL7A)
        val deviceAssertion = DeviceAssertion.fromCbor(ASSERTION_PIXEL7A)
        deviceAttestation.validateAssertion(deviceAssertion)
    }

    @Test
    fun testAttestationEmulatorPixel3a() {
        val deviceAttestation = DeviceAttestation.fromCbor(ATTESTATION_EMULATOR_PIXEL3A)
        deviceAttestation.validate(
            DeviceAttestationValidationData(
                attestationChallenge = ATTESTATION_CHALLENGE_EMULATOR_PIXEL3A.encodeToByteString(),
                iosReleaseBuild = false,
                iosAppIdentifiers = setOf(),
                androidRequiredKeyMintSecurityLevel = AndroidKeystoreSecurityLevel.SOFTWARE,
                androidGmsAttestation = false,
                androidVerifiedBootGreen = false,
                androidAppSignatureCertificateDigests = setOf(
                    ByteString("VEpxrWMf2GFLy2_HHTuN7xlW5fy6mKhVAmRADo4aLh0".fromBase64Url())
                ),
                androidAppPackageNames = setOf("org.multipaz_credential.wallet")
            )
        )
    }

    @Test
    fun testAssertionEmulatorPixel3a() {
        val deviceAttestation = DeviceAttestation.fromCbor(ATTESTATION_EMULATOR_PIXEL3A)
        val deviceAssertion = DeviceAssertion.fromCbor(ASSERTION_EMULATOR_PIXEL3A)
        deviceAttestation.validateAssertion(deviceAssertion)
    }

    @Test
    fun testFoo() {
        val attestationCertificate = X509Cert.fromPem("""
            -----BEGIN CERTIFICATE-----
            MIIBxTCCAUugAwIBAgIJAOQTL9qcQopZMAoGCCqGSM49BAMDMDgxNjA0BgNVBAMT
            LXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjAe
            Fw0yNDA5MjMyMjUxMzFaFw0zNDA5MjMyMjUxMzFaMDgxNjA0BgNVBAMTLXVybjp1
            dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjB2MBAGByqG
            SM49AgEGBSuBBAAiA2IABN4D7fpNMAv4EtxyschbITpZ6iNH90rGapa6YEO/uhKn
            C6VpPt5RUrJyhbvwAs0edCPthRfIZwfwl5GSEOS0mKGCXzWdRv4GGX/Y0m7EYypo
            x+tzfnRTmoVX3v6OxQiapKMhMB8wHQYDVR0OBBYEFPqAK5EjiQbxFAeWt//DCaWt
            C57aMAoGCCqGSM49BAMDA2gAMGUCMEO01fJKCy+iOTpaVp9LfO7jiXcXksn2BA22
            reiR9ahDRdGNCrH1E3Q2umQAssSQbQIxAIz1FTHbZPcEbA5uE5lCZlRG/DQxlZhk
            /rZrkPyXFhqEgfMnQ45IJ6f8Utlg+4Wiiw==
            -----END CERTIFICATE-----
                """.trimIndent()
        )

        val attestationPrivateKey = EcPrivateKey.fromPem("""
                -----BEGIN PRIVATE KEY-----
                ME4CAQAwEAYHKoZIzj0CAQYFK4EEACIENzA1AgEBBDBn7jeRC9u9de3kOkrt9lLT
                Pvd1hflNq1FCgs7D+qbbwz1BQa4XXU0SjsV+R1GjnAY=
                -----END PRIVATE KEY-----
                """.trimIndent(),
            attestationCertificate.ecPublicKey
        )

        val text = attestationPrivateKey.toJwk()

        println(text)
    }

    companion object {
        // Test data obtained by adding temporary server code to capture and save it.

        private const val ATTESTATION_CHALLENGE_PIXEL7A = "hJvYMWSqFp_75DYDqF6F13PB"

        @OptIn(ExperimentalEncodingApi::class)
        val ATTESTATION_PIXEL7A = Base64.decode("""
            omR0eXBlZ0FuZHJvaWRwY2VydGlmaWNhdGVDaGFpboVZAqkwggKlMIICS6ADAgECAgEBMAoGCCqG
            SM49BAMCMDkxKTAnBgNVBAMTIDNmYTQ2MjU1MTQ4NGM0NDNiMzA2M2MxNjI1MGFhYzlhMQwwCgYD
            VQQKEwNURUUwHhcNNzAwMTAxMDAwMDAwWhcNNDgwMTAxMDAwMDAwWjAfMR0wGwYDVQQDExRBbmRy
            b2lkIEtleXN0b3JlIEtleTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABG22ZlDtjL5xvETEs5hM
            pDE5kcZ+Oxh81T0u63hvYt3oPaycZoYgfyi2kZ48m/S5yAAwWZnMVLvBVr7XR8mgJrWjggFcMIIB
            WDAOBgNVHQ8BAf8EBAMCB4AwggFEBgorBgEEAdZ5AgERBIIBNDCCATACAgEsCgEBAgIBLAoBAQQY
            aEp2WU1XU3FGcF83NURZRHFGNkYxM1BCBAAwYL+FPQgCBgGVjdKzOb+FRVAETjBMMSYwJAQeb3Jn
            Lm11bHRpcGF6X2NyZWRlbnRpYWwud2FsbGV0AgIC8zEiBCBUSnGtYx/YYUvLb8cdO43vGVbl/LqY
            qFUCZEAOjhouHTCBoaEFMQMCAQKiAwIBA6MEAgIBAKUFMQMCAQSqAwIBAb+DdwIFAL+FPgMCAQC/
            hUBMMEoEIAA/Gt6dR25hKwDymD5q19zRXmqAzC27AI2n1oOe1zqPAQH/CgEABCBb3i/pqkl1iwRQ
            bp1JEFpJaV5SC+hwGiiMg9cbgVhBa7+FQQUCAwJJ8L+FQgUCAwMXBr+FTgYCBAE0/l2/hU8GAgQB
            NP5dMAoGCCqGSM49BAMCA0gAMEUCIHXHGgc1DdcsCVGlLaV3JvJmrINptgJ3YJbmI599WylgAiEA
            3sZxWYLKo4KdH3MzrXgTLg8HrkAjoKaKTRhSOuAZvN5ZAeIwggHeMIIBhaADAgECAhA/pGJVFITE
            Q7MGPBYlCqyaMAoGCCqGSM49BAMCMCkxEzARBgNVBAoTCkdvb2dsZSBMTEMxEjAQBgNVBAMTCURy
            b2lkIENBMzAeFw0yNTAyMjcwMTIxMTdaFw0yNTAzMjQyMzI3MzVaMDkxKTAnBgNVBAMTIDNmYTQ2
            MjU1MTQ4NGM0NDNiMzA2M2MxNjI1MGFhYzlhMQwwCgYDVQQKEwNURUUwWTATBgcqhkjOPQIBBggq
            hkjOPQMBBwNCAATNfHNlaxd8B8jYWMx+G0+6oKTdwkQmFV0Oy7X2v7qmKstK/ZuJXOK6kxIlhTdq
            lgExrEuuRnp6NvC6sN9s+/kmo38wfTAdBgNVHQ4EFgQUVtuQGpYuPGTda041SDA7iKM6xsIwHwYD
            VR0jBBgwFoAUvQSMplM06hE6Xtg7eRVbEg5uXNwwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8E
            BAMCAgQwGgYKKwYBBAHWeQIBHgQMogEYIANmR29vZ2xlMAoGCCqGSM49BAMCA0cAMEQCIAMTNXIX
            oZUav9cKuJs6lAt3P/wMjMJmxeRQBNUTjCq8AiB8Cw6hQIeBoX5Lv5ze8yiCArPyT8mTc6UMsvo+
            YKPqUVkB2jCCAdYwggFcoAMCAQICEzBrBApbGyYLdUfHebdqQ6kuHRIwCgYIKoZIzj0EAwMwKTET
            MBEGA1UEChMKR29vZ2xlIExMQzESMBAGA1UEAxMJRHJvaWQgQ0EyMB4XDTI1MDIxODEyMTUwOFoX
            DTI1MDQyOTEyMTUwN1owKTETMBEGA1UEChMKR29vZ2xlIExMQzESMBAGA1UEAxMJRHJvaWQgQ0Ez
            MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIqmNhxGFtsxVXFws4BTdtl7nG2Vhto0YCijE6MaW
            DaqYv7yCnutizl0jZ2l0V5gTkZJoIZF4symM+QGBqwpU46NjMGEwDgYDVR0PAQH/BAQDAgIEMA8G
            A1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFL0EjKZTNOoROl7YO3kVWxIOblzcMB8GA1UdIwQYMBaA
            FKYLhqTwyH8ztWE5Ys0956c6QoNIMAoGCCqGSM49BAMDA2gAMGUCMGdsdr4KLf0ksysw9DmAvPhF
            nmLTk2vfw/ddAvQKx/68uNm3EwrXHBpp6Rwn2HeIDgIxAOoJtsX33CIOoh5Fa7ve7js+ryhtcOGb
            K1LYM2sSFddWH9Gpx4YR+iNUgVXW+eFla1kDhDCCA4AwggFooAMCAQICCgOIJmdgZYmWhg4wDQYJ
            KoZIhvcNAQELBQAwGzEZMBcGA1UEBRMQZjkyMDA5ZTg1M2I2YjA0NTAeFw0yMjAxMjYyMjQ5NDVa
            Fw0zNzAxMjIyMjQ5NDVaMCkxEzARBgNVBAoTCkdvb2dsZSBMTEMxEjAQBgNVBAMTCURyb2lkIENB
            MjB2MBAGByqGSM49AgEGBSuBBAAiA2IABPvZm1gnQjZ60WGi6+1WzSwHO9izHy2E0JlXXCwM++ww
            NJM+KP4zXAEUPTQ5SfZt9Tlvsrb+3Y2Rdzb79X2D76Z1vpKhEaYn8Ab1JxjrQspIDuI9PuBn/n6r
            S2cQ89kkKqNmMGQwHQYDVR0OBBYEFKYLhqTwyH8ztWE5Ys0956c6QoNIMB8GA1UdIwQYMBaAFDZh
            4QB8iAUJUYtEbEf/GkzJ6k8SMBIGA1UdEwEB/wQIMAYBAf8CAQIwDgYDVR0PAQH/BAQDAgEGMA0G
            CSqGSIb3DQEBCwUAA4ICAQCukHZ4tsoc1xLpDdN3c3tkKBUs3SoDsJugz4W27hhgBwfGH57+yTtP
            tc6DlLh31r8MPuYheAQ1IE8H99WoKcFpIMo0nedS4LX/2BxP3mniQomjS2pyxbDxaApUmE25kI3t
            3/OIcuJFFysq1qP3jhMfOzLu8H6lJqLuBNqH9EmsNAj3VsNQmCV3z+n9ABtWdgDyj6aw7SxErGAX
            VXlC4HAFN+l2SqyGovefAGbSyh+oj+sz4k5Bq9rf4i6se9+TOKiI4nS4UmcEZWOlkXAbCybIrDOX
            7epEqWQeCAA4q3eVBDgaImc4KbXQmvE9rZlrQrr+TKTpjK+9FwU1qLl6Pecwok+D554badJPo3p4
            8W5p6g1CcPheRqAYURBjNyq+x0WyaxtQUyCEWXvMRVh2xy5KAIb3f3DFbUmArIblx7sIjjTfbzD0
            i45/yZRL9beLiNJ/GoIlt6DKj9IoOkMgueElQHLRPd83ya/0DI7S6gkGW+eiAhW/ZJ2lhS33a/I6
            IYzLcnH9lFaMhFruIhXdWeMsj/+mAKtdAsyKdlYRazFXGDbmeuTy7fadl3rsMk3eTYW59FuyYdmX
            L0IujQ/v2WQgQLeuJ8uqol4PIdA1oExQLoFINnlEkK0yNJnDbEvnPRf3/MGwpU+QxbrWEyErNWZg
            0t7UMlAA1E/56eu7mLWheVkFIDCCBRwwggMEoAMCAQICCQDVD/Jbo/LWszANBgkqhkiG9w0BAQsF
            ADAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MB4XDTE5MTEyMjIwMzc1OFoXDTM0MTExODIw
            Mzc1OFowGzEZMBcGA1UEBRMQZjkyMDA5ZTg1M2I2YjA0NTCCAiIwDQYJKoZIhvcNAQEBBQADggIP
            ADCCAgoCggIBAK+2x4IrsacB7Cu0LovMVBZjq++YLzLHf3UxAwyXUksbX+gJ+8cqqUUfdDy9mm8T
            NXRKpV539rasNTXuF8JeY5UX3ZyS5jdKU8v+JY+P+7b9EpN4oipMqZxFLUelnzIB9EGXyhzNfnYv
            svUxUbb+sv/9K2/k/lvGvZ7DS/4II52q/OuOtajtKzrNnF46d5DhtRRCeTFZhZgRrZ6yqWu916V8
            k6kcQfzNJ9Z/1vZxqguBUmGtOE+jeUSGRgTds9jE+SChmxZWwvFK1tA8VuwGCJkEHB7Rpf5tNEC1
            VrrR0KFSWJxT5V03B2LwEi7vkYYbGw5sTICSdJnA6b7AuD47wfk8csBJYEu9LxNF5iw/jibb7AbJ
            R2bzwSgjnU9DEvrYEjiH4Gvs9WdYO/g1WoH+6rr5moPI3z4qMir8ZyvxILE1FYtoIc6vMJtu7nf5
            iDOwGNqhDkUfBqN01QeB81kIKWa7d4uTCJQmmOdOC80kYooBwswD5R8LPltKweTfnq+f9qSSp3wU
            g4gohQFbQizme4C4jJtI4TtgerVFxyP/jET48tNoufZSDTEUXr+ehirXHfajv9JFCVnWU3QNl6Ev
            NosT72bV0KVKbi9dmm/vRGgyvGeERyWGHwk90ObzQF2olkPvD01ptkIAUf25MElnPjaVBYDTzfT7
            0IvFhIOVJgBjAgMBAAGjYzBhMB0GA1UdDgQWBBQ2YeEAfIgFCVGLRGxH/xpMyepPEjAfBgNVHSME
            GDAWgBQ2YeEAfIgFCVGLRGxH/xpMyepPEjAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIC
            BDANBgkqhkiG9w0BAQsFAAOCAgEATjGgXPKLpl29r6HO1wlp7lyoQQSt3tijBs9/be5QN110XtmS
            ywJCzOctye7VEZH+WtUrrX3TslwJnhOkkaPN1IelrM6HZjJMSuRjOCRq57eKQYrLuYoFxMnWlu6q
            tgnQugzhoxvphJDfP0wOqd3J6C/7D8s+nr3Yy5UnifKxQR+sVsiGQm63KWBCc12lDhGscV8YGM+f
            3E4lSjdjNRtqJEAVCGEmOm4xC+GlDeXH6O6ID91L5YhKNxKNGIMLs0dr9CkegtXGamSUk54ISAv7
            wA99inTUPnNzfr5djk7FFTAtRolpJ4DcdTjtfpF1vmE5501DrTiLMFD/1aneUmIACJjAH2PFPf4i
            IJEI+k9luhbEnMveCDfXxYRNVLc5i6ASLlBbFVyTE8/ibnLYfiKqFhbmvb9Ufd/5PfKeNaY7RV/h
            /A7JVYHz9Pe747uCg5ajeuMVdYK8N2S5eAojnvwPdaHi5tlBzqusJ93rAeK9hCECm+o01RrubGAn
            HVqV69AFFanAAT3YC/h+6iYLgcNPaI5usTSK8NjqHKwyrLnZP6JK/wMKhMjysPVpzJUICyCsNazg
            xtjb1PaEdxlRnTJFAWbrS/FbhZBEUBrer0NjgsNLFeO1TJLmG2nCv8cmRYkXKzyT2+Nc4G0I/VwB
            Miygh3sdEnQ68frVlA6hvALdiRw=
        """.trimIndent().replace("\n", ""))

        @OptIn(ExperimentalEncodingApi::class)
        val ASSERTION_PIXEL7A = Base64.decode("""
            om1hc3NlcnRpb25EYXRhU6JkdHlwZWVOb25jZWVub25jZUBxcGxhdGZvcm1Bc3NlcnRpb25YQG0O
            jZsCCv9aDPL2Ptz8lXIkjFZv4hz30JXNyycMTIX3szKiwQ3kA9zdlFdUTqhgy2XRmVZ+9T1qZJW6
            yVn2wzE=
        """.trimIndent().replace("\n", ""))

        private const val ATTESTATION_CHALLENGE_EMULATOR_PIXEL3A = "f34fEQlaaRg2QEUuo6U8L-YO"

        @OptIn(ExperimentalEncodingApi::class)
        val ATTESTATION_EMULATOR_PIXEL3A = Base64.decode("""
            omR0eXBlZ0FuZHJvaWRwY2VydGlmaWNhdGVDaGFpboNZAvUwggLxMIICmKADAgECAgEBMAoGCCqG
            SM49BAMCMIGIMQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEVMBMGA1UECgwMR29v
            Z2xlLCBJbmMuMRAwDgYDVQQLDAdBbmRyb2lkMTswOQYDVQQDDDJBbmRyb2lkIEtleXN0b3JlIFNv
            ZnR3YXJlIEF0dGVzdGF0aW9uIEludGVybWVkaWF0ZTAeFw03MDAxMDEwMDAwMDBaFw00ODAxMDEw
            MDAwMDBaMB8xHTAbBgNVBAMTFEFuZHJvaWQgS2V5c3RvcmUgS2V5MFkwEwYHKoZIzj0CAQYIKoZI
            zj0DAQcDQgAEfEZ6swttifZj2hWdUUKtFzktRpVO7EwsrU6OlZMXc+pJKKRNHf4RdOuuNZcfvdIY
            /0i1HSB5IDNZ6Z3AmLkmj6OCAVkwggFVMA4GA1UdDwEB/wQEAwIHgDCCAUEGCisGAQQB1nkCAREE
            ggExMIIBLQICASwKAQACAgEsCgEABBhmMzRmRVFsYWFSZzJRRVV1bzZVOEwtWU8EADCB/qEFMQMC
            AQKiAwIBA6MEAgIBAKUFMQMCAQSqAwIBAb+DdwIFAL+FPQgCBgGVjdqnwL+FPgMCAQC/hUBMMEoE
            IAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQEACgECBCAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAL+FQQUCAwIi4L+FQgUCAwMWRb+FRVAETjBMMSYwJAQeb3JnLm11bHRp
            cGF6X2NyZWRlbnRpYWwud2FsbGV0AgIC8zEiBCBUSnGtYx/YYUvLb8cdO43vGVbl/LqYqFUCZEAO
            jhouHb+FTgMCAQC/hU8GAgQBNLL1MAAwCgYIKoZIzj0EAwIDRwAwRAIgWnC1SAmbUgnEOkNhtQJv
            vesYiB19IuNZNNgUcni89fMCIDbxZXm+G+m5FMBrysrqLPLNN05LqmP9YOUZdfo4Qy1pWQJ8MIIC
            eDCCAh6gAwIBAgICEAEwCgYIKoZIzj0EAwIwgZgxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxp
            Zm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRUwEwYDVQQKDAxHb29nbGUsIEluYy4xEDAO
            BgNVBAsMB0FuZHJvaWQxMzAxBgNVBAMMKkFuZHJvaWQgS2V5c3RvcmUgU29mdHdhcmUgQXR0ZXN0
            YXRpb24gUm9vdDAeFw0xNjAxMTEwMDQ2MDlaFw0yNjAxMDgwMDQ2MDlaMIGIMQswCQYDVQQGEwJV
            UzETMBEGA1UECAwKQ2FsaWZvcm5pYTEVMBMGA1UECgwMR29vZ2xlLCBJbmMuMRAwDgYDVQQLDAdB
            bmRyb2lkMTswOQYDVQQDDDJBbmRyb2lkIEtleXN0b3JlIFNvZnR3YXJlIEF0dGVzdGF0aW9uIElu
            dGVybWVkaWF0ZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABOueefhCY1msyyqRTImGzHCtkGaT
            gqlzJhP+rMv4ISdMIXSXSir+pblNf2bU4GUQZjW8U7ego6ZxWD7bPhGuEBSjZjBkMB0GA1UdDgQW
            BBQ//KzWGrE6noEguNUlHMVlux6RqTAfBgNVHSMEGDAWgBTIrel3TEXDo88NFhDkeUM6IVowzzAS
            BgNVHRMBAf8ECDAGAQH/AgEAMA4GA1UdDwEB/wQEAwIChDAKBggqhkjOPQQDAgNIADBFAiBLipt7
            7oK8wDOHri/AiZi03cONqycqRZ9pDMfDktQPjgIhAO7aAV229DLp1IQ7YkyUBO86fMy9Xvsiu+f+
            uXc/WT/7WQKPMIICizCCAjKgAwIBAgIJAKIFntEOQ1tXMAoGCCqGSM49BAMCMIGYMQswCQYDVQQG
            EwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzEVMBMGA1UE
            CgwMR29vZ2xlLCBJbmMuMRAwDgYDVQQLDAdBbmRyb2lkMTMwMQYDVQQDDCpBbmRyb2lkIEtleXN0
            b3JlIFNvZnR3YXJlIEF0dGVzdGF0aW9uIFJvb3QwHhcNMTYwMTExMDA0MzUwWhcNMzYwMTA2MDA0
            MzUwWjCBmDELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50
            YWluIFZpZXcxFTATBgNVBAoMDEdvb2dsZSwgSW5jLjEQMA4GA1UECwwHQW5kcm9pZDEzMDEGA1UE
            AwwqQW5kcm9pZCBLZXlzdG9yZSBTb2Z0d2FyZSBBdHRlc3RhdGlvbiBSb290MFkwEwYHKoZIzj0C
            AQYIKoZIzj0DAQcDQgAE7l1ex+HA220Dpn7mthvsTWpdamguD/9/SQ59dx9EIm29sa/6FsvHrcV3
            0lacqrewLVQBXT5DKyqO107sSHVBpKNjMGEwHQYDVR0OBBYEFMit6XdMRcOjzw0WEOR5QzohWjDP
            MB8GA1UdIwQYMBaAFMit6XdMRcOjzw0WEOR5QzohWjDPMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0P
            AQH/BAQDAgKEMAoGCCqGSM49BAMCA0cAMEQCIDUho++LNEYenNVg8x1YiSBq3KNlQfYNns6KGYxm
            SGB7AiBNC/NR2TB8fVvaNTQdqEcbY6WFZTytTySn502vQX3xvw==
        """.trimIndent().replace("\n", ""))

        @OptIn(ExperimentalEncodingApi::class)
        val ASSERTION_EMULATOR_PIXEL3A = Base64.decode("""
            om1hc3NlcnRpb25EYXRhU6JkdHlwZWVOb25jZWVub25jZUBxcGxhdGZvcm1Bc3NlcnRpb25YQHRt
            c4So4V5Z6is6nMKHvaBCfYD5RIEoKNVlkY6Z2Ji68eiJdy7LTQJ7OIOSUbxjsOZQ5FgntUuZc8Af
            QCONlv0=
        """.trimIndent().replace("\n", ""))
    }
}