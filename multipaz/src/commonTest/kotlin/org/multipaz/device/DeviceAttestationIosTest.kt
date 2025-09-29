package org.multipaz.device

import kotlinx.io.bytestring.encodeToByteString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test

class DeviceAttestationIosTest {
    private val attestationChallenge = "n5xXQBgTcPnceKioxKYjdcXI"
    private val deviceAttestation = DeviceAttestation.fromCbor(ATTESTATION)
    private val deviceAssertion = DeviceAssertion.fromCbor(ASSERTION)

    @Test
    fun testValidation() {
        deviceAttestation.validate(
            DeviceAttestationValidationData(
                attestationChallenge = attestationChallenge.encodeToByteString(),
                iosReleaseBuild = false,
                iosAppIdentifiers = setOf("74HWMG89B3.com.sorototkin.testapp5"),
                androidGmsAttestation = false,
                androidVerifiedBootGreen = false,
                androidRequiredKeyMintSecurityLevel = AndroidKeystoreSecurityLevel.SOFTWARE,
                androidAppSignatureCertificateDigests = setOf(),
                androidAppPackageNames = setOf()
            )
        )
    }

    @Test
    fun testAssertion() {
        deviceAttestation.validateAssertion(deviceAssertion)
    }

    companion object {
        // Test data obtained by adding temporary server code to capture and save it.

        @OptIn(ExperimentalEncodingApi::class)
        val ATTESTATION = Base64.decode("""
            omR0eXBlY0lvc2RibG9iWRT7o2NmbXRvYXBwbGUtYXBwYXR0ZXN0Z2F0dFN0bXSiY3g1Y4JZAzAw
            ggMsMIICs6ADAgECAgYBlY33gCAwCgYIKoZIzj0EAwIwTzEjMCEGA1UEAwwaQXBwbGUgQXBwIEF0
            dGVzdGF0aW9uIENBIDExEzARBgNVBAoMCkFwcGxlIEluYy4xEzARBgNVBAgMCkNhbGlmb3JuaWEw
            HhcNMjUwMzEyMDUyNjAyWhcNMjYwMTI2MDIxODAyWjCBkTFJMEcGA1UEAwxAYzNiYzkwZjM1ODYw
            MmQ3MWE2NzRmNTNiZWRkZjgzYzU1MDg0ZWQ0ZjRlZTg0Y2U3MDI2YmM0NGFjZmUwNzczYzEaMBgG
            A1UECwwRQUFBIENlcnRpZmljYXRpb24xEzARBgNVBAoMCkFwcGxlIEluYy4xEzARBgNVBAgMCkNh
            bGlmb3JuaWEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATlsvw0DTNVC7q1EeWCHknF9K3DEEUB
            kidroXoUKNgQEBFRr8HOBCwMSbaSZu6xNpakKaDBcPM7cMjz6TaSSUCao4IBNjCCATIwDAYDVR0T
            AQH/BAIwADAOBgNVHQ8BAf8EBAMCBPAwgYMGCSqGSIb3Y2QIBQR2MHSkAwIBCr+JMAMCAQG/iTED
            AgEAv4kyAwIBAb+JMwMCAQG/iTQkBCI3NEhXTUc4OUIzLmNvbS5zb3JvdG90a2luLnRlc3RhcHA1
            pQYEBHNrcyC/iTYDAgEFv4k3AwIBAL+JOQMCAQC/iToDAgEAv4k7AwIBADBXBgkqhkiG92NkCAcE
            SjBIv4p4CAQGMTcuNi4xv4hQBwIFAP////6/insHBAUyMUc5M7+KfQgEBjE3LjYuMb+KfgMCAQC/
            iwwPBA0yMS43LjkzLjAuMCwwMDMGCSqGSIb3Y2QIAgQmMCShIgQgU4omGdLda25B5YDChjOYsG2o
            UDEn1KtF3WtEvNlcANswCgYIKoZIzj0EAwIDZwAwZAIwCXXuaY3ro/WN1X6GvlIag9JwE4VdOdgY
            XKVh7jaaeNqGOXneZsPLPrF3j3oY712RAjA7e7clV3p7S+G6yUuiLP92wbUY2w+n8aUX53EOoMIa
            xFBOWNWCz1qQoLMo90PmPvxZAkcwggJDMIIByKADAgECAhAJusXhvEAa2dRTlbw4GghUMAoGCCqG
            SM49BAMDMFIxJjAkBgNVBAMMHUFwcGxlIEFwcCBBdHRlc3RhdGlvbiBSb290IENBMRMwEQYDVQQK
            DApBcHBsZSBJbmMuMRMwEQYDVQQIDApDYWxpZm9ybmlhMB4XDTIwMDMxODE4Mzk1NVoXDTMwMDMx
            MzAwMDAwMFowTzEjMCEGA1UEAwwaQXBwbGUgQXBwIEF0dGVzdGF0aW9uIENBIDExEzARBgNVBAoM
            CkFwcGxlIEluYy4xEzARBgNVBAgMCkNhbGlmb3JuaWEwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAASu
            Wzegd015sjWPQOfR8iYm8cJf7xeALeqzgmpZh0/40q0VJXiaomYEGRJItjy5ZwaemNNjvV43D7+g
            jjKegHOphed0bqNZovZvKdsyr0VeIRZY1WevniZ+smFNwhpmzpmjZjBkMBIGA1UdEwEB/wQIMAYB
            Af8CAQAwHwYDVR0jBBgwFoAUrJEQUzO9vmhB/6cMqeX66uXliqEwHQYDVR0OBBYEFD7jXRwEGanJ
            tDH4hHTW4eFXcuObMA4GA1UdDwEB/wQEAwIBBjAKBggqhkjOPQQDAwNpADBmAjEAu76IjXONBQLP
            vP1mbQlXUDW81ocsP4QwSSYp7dH5FOh5mRya6LWu+NOoVDP3tg0GAjEAqzjt0MyB7QCkUsO6RPmT
            Y2VT/swpfy60359evlpKyraZXEuCDfkEOG94B7tYlDm3Z3JlY2VpcHRZDqEwgAYJKoZIhvcNAQcC
            oIAwgAIBATEPMA0GCWCGSAFlAwQCAQUAMIAGCSqGSIb3DQEHAaCAJIAEggPoMYIEWzAqAgECAgEB
            BCI3NEhXTUc4OUIzLmNvbS5zb3JvdG90a2luLnRlc3RhcHA1MIIDOgIBAwIBAQSCAzAwggMsMIIC
            s6ADAgECAgYBlY33gCAwCgYIKoZIzj0EAwIwTzEjMCEGA1UEAwwaQXBwbGUgQXBwIEF0dGVzdGF0
            aW9uIENBIDExEzARBgNVBAoMCkFwcGxlIEluYy4xEzARBgNVBAgMCkNhbGlmb3JuaWEwHhcNMjUw
            MzEyMDUyNjAyWhcNMjYwMTI2MDIxODAyWjCBkTFJMEcGA1UEAwxAYzNiYzkwZjM1ODYwMmQ3MWE2
            NzRmNTNiZWRkZjgzYzU1MDg0ZWQ0ZjRlZTg0Y2U3MDI2YmM0NGFjZmUwNzczYzEaMBgGA1UECwwR
            QUFBIENlcnRpZmljYXRpb24xEzARBgNVBAoMCkFwcGxlIEluYy4xEzARBgNVBAgMCkNhbGlmb3Ju
            aWEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATlsvw0DTNVC7q1EeWCHknF9K3DEEUBkidroXoU
            KNgQEBFRr8HOBCwMSbaSZu6xNpakKaDBcPM7cMjz6TaSSUCao4IBNjCCATIwDAYDVR0TAQH/BAIw
            ADAOBgNVHQ8BAf8EBAMCBPAwgYMGCSqGSIb3Y2QIBQR2MHSkAwIBCr+JMAMCAQG/iTEDAgEAv4ky
            AwIBAb+JMwMCAQG/iTQkBCI3NEhXTUc4OUIzLmNvbS5zb3JvdG90a2luLnRlc3RhcHA1pQYEBHNr
            cyC/iTYDAgEFv4k3AwIBAL+JOQMCAQC/iToDAgEAv4k7AwIBADBXBgkqhkiG92NkCAcESjBIv4p4
            CAQGMTcuNi4xv4hQBwIFAP////6/insHBAUyMUc5M7+KfQgEBjE3LjYuMb+KfgMCAQC/iwwPBA0y
            MS43LjkzLjAuMCwwMDMGCSqGSIb3Y2QIAgQmMCShIgQgU4omGdLda25B5YDChjOYsG2oUDEn1KtF
            3WtEvNlcANswCgYIKoZIzj0EAwIDZwAwZAIwCXXuaY3ro/WN1X6GvlIag9JwE4VdOdgYXKVh7jaa
            eNqGOXneZsPLPrF3j3oY712RAjA7e7clV3p7S+G6yUuiLP92wbUY2w+n8aUX53EOoMIaxFBOWNWC
            z1qQoLMo90PmPvwwKAIBBAIBAQQg6dXnJ4NOy8Usrr0cjZPn/KwSsWBRcEPFaVJnEeXB+aUwYAIB
            BQIBAQRYYjMzN3FpWTFIWVFndEhnWk44NDNQdEFKRVRjSVpSRlJrYlpHM0sxRUxDeFRhVnRSUldv
            bFZjZjhYdUNWVEwva2FKaEdWUwR3WWZuVjBsTEx6ZkQ1NFlKQT09MA4CAQYCAQEEBkFUVEVTVDAP
            AgEHAgEBBAdzYW5kYm94MCACAQwCAQEEGDIwMjUtMDMtMTNUMDU6MjY6MDIuNTQ2WjAgAgEVAgEB
            BBgyMDI1LTA2LTExVDA1OjI2OjAyLjU0NloAAAAAAACggDCCA68wggNUoAMCAQICEEIE0y1OY8zf
            v4PrmK9VdjEwCgYIKoZIzj0EAwIwfDEwMC4GA1UEAwwnQXBwbGUgQXBwbGljYXRpb24gSW50ZWdy
            YXRpb24gQ0EgNSAtIEcxMSYwJAYDVQQLDB1BcHBsZSBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0eTET
            MBEGA1UECgwKQXBwbGUgSW5jLjELMAkGA1UEBhMCVVMwHhcNMjUwMTIyMTgyNjExWhcNMjYwMjE3
            MTk1NjA0WjBaMTYwNAYDVQQDDC1BcHBsaWNhdGlvbiBBdHRlc3RhdGlvbiBGcmF1ZCBSZWNlaXB0
            IFNpZ25pbmcxEzARBgNVBAoMCkFwcGxlIEluYy4xCzAJBgNVBAYTAlVTMFkwEwYHKoZIzj0CAQYI
            KoZIzj0DAQcDQgAEm4aYmZfU6Ubcy75EPyv3KRHTQGvELx/CJKsVC0Xukvpr1Kz0rRwcEYpNJOI+
            t1KBolOJYbQqw5OIe4QfYw/s46OCAdgwggHUMAwGA1UdEwEB/wQCMAAwHwYDVR0jBBgwFoAU2Rf+
            S2eQOEuS9NvO1VeAFAuPPckwQwYIKwYBBQUHAQEENzA1MDMGCCsGAQUFBzABhidodHRwOi8vb2Nz
            cC5hcHBsZS5jb20vb2NzcDAzLWFhaWNhNWcxMDEwggEcBgNVHSAEggETMIIBDzCCAQsGCSqGSIb3
            Y2QFATCB/TCBwwYIKwYBBQUHAgIwgbYMgbNSZWxpYW5jZSBvbiB0aGlzIGNlcnRpZmljYXRlIGJ5
            IGFueSBwYXJ0eSBhc3N1bWVzIGFjY2VwdGFuY2Ugb2YgdGhlIHRoZW4gYXBwbGljYWJsZSBzdGFu
            ZGFyZCB0ZXJtcyBhbmQgY29uZGl0aW9ucyBvZiB1c2UsIGNlcnRpZmljYXRlIHBvbGljeSBhbmQg
            Y2VydGlmaWNhdGlvbiBwcmFjdGljZSBzdGF0ZW1lbnRzLjA1BggrBgEFBQcCARYpaHR0cDovL3d3
            dy5hcHBsZS5jb20vY2VydGlmaWNhdGVhdXRob3JpdHkwHQYDVR0OBBYEFJuus8UlZbxcy9jrSqZH
            Uacp8NrCMA4GA1UdDwEB/wQEAwIHgDAPBgkqhkiG92NkDA8EAgUAMAoGCCqGSM49BAMCA0kAMEYC
            IQD+WwmyAylN6mTzl340MFHMNFMRuVTvwKgV4AWeQZwJOwIhAI4UD0DpN/2HzRIxe61tWGsgAByt
            NG+45yeH5oiwxhyDMIIC+TCCAn+gAwIBAgIQVvuD1Cv/jcM3mSO1Wq5uvTAKBggqhkjOPQQDAzBn
            MRswGQYDVQQDDBJBcHBsZSBSb290IENBIC0gRzMxJjAkBgNVBAsMHUFwcGxlIENlcnRpZmljYXRp
            b24gQXV0aG9yaXR5MRMwEQYDVQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzAeFw0xOTAzMjIx
            NzUzMzNaFw0zNDAzMjIwMDAwMDBaMHwxMDAuBgNVBAMMJ0FwcGxlIEFwcGxpY2F0aW9uIEludGVn
            cmF0aW9uIENBIDUgLSBHMTEmMCQGA1UECwwdQXBwbGUgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkx
            EzARBgNVBAoMCkFwcGxlIEluYy4xCzAJBgNVBAYTAlVTMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcD
            QgAEks5jvX2GsasoCjsc4a/7BJSAkaz2Md+myyg1b0RL4SHlV90SjY26gnyVvkn6vjPKrs0EGfEv
            QyX69L6zy4N+uqOB9zCB9DAPBgNVHRMBAf8EBTADAQH/MB8GA1UdIwQYMBaAFLuw3qFYM4iapIqZ
            3r6966/ayySrMEYGCCsGAQUFBwEBBDowODA2BggrBgEFBQcwAYYqaHR0cDovL29jc3AuYXBwbGUu
            Y29tL29jc3AwMy1hcHBsZXJvb3RjYWczMDcGA1UdHwQwMC4wLKAqoCiGJmh0dHA6Ly9jcmwuYXBw
            bGUuY29tL2FwcGxlcm9vdGNhZzMuY3JsMB0GA1UdDgQWBBTZF/5LZ5A4S5L0287VV4AUC489yTAO
            BgNVHQ8BAf8EBAMCAQYwEAYKKoZIhvdjZAYCAwQCBQAwCgYIKoZIzj0EAwMDaAAwZQIxAI1vpp+h
            4OTsW05zipJ/PXhTmI/02h9YHsN1Sv44qEwqgxoaqg2mZG3huZPo0VVM7QIwZzsstOHoNwd3y9Xs
            dqgaOlU7PzVqyMXmkrDhYb6ASWnkXyupbOERAqrMYdk4t3NKMIICQzCCAcmgAwIBAgIILcX8iNLF
            S5UwCgYIKoZIzj0EAwMwZzEbMBkGA1UEAwwSQXBwbGUgUm9vdCBDQSAtIEczMSYwJAYDVQQLDB1B
            cHBsZSBDZXJ0aWZpY2F0aW9uIEF1dGhvcml0eTETMBEGA1UECgwKQXBwbGUgSW5jLjELMAkGA1UE
            BhMCVVMwHhcNMTQwNDMwMTgxOTA2WhcNMzkwNDMwMTgxOTA2WjBnMRswGQYDVQQDDBJBcHBsZSBS
            b290IENBIC0gRzMxJjAkBgNVBAsMHUFwcGxlIENlcnRpZmljYXRpb24gQXV0aG9yaXR5MRMwEQYD
            VQQKDApBcHBsZSBJbmMuMQswCQYDVQQGEwJVUzB2MBAGByqGSM49AgEGBSuBBAAiA2IABJjpLz1A
            cqTtkyJygRMc3RCV8cWjTnHcFBbZDuWmBSp3ZHtfTjjTuxxEtX/1H7YyYl3J6YRbTzBPEVoA/VhY
            DKX1DyxNB0cTddqXl5dvMVztK517IDvYuVTZXpmkOlEKMaNCMEAwHQYDVR0OBBYEFLuw3qFYM4ia
            pIqZ3r6966/ayySrMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMAoGCCqGSM49BAMD
            A2gAMGUCMQCD6cHEFl4aXTQY2e3v9GwOAEZLuN+yRhHFD/3meoyhpmvOwgPUnPWTxnS4at+qIxUC
            MG1mihDK1A3UT82NQz60imOlM27jbdoXt2QfyFMm+YhidDkLF1vLUagM6BgD56KyKAAAMYH8MIH5
            AgEBMIGQMHwxMDAuBgNVBAMMJ0FwcGxlIEFwcGxpY2F0aW9uIEludGVncmF0aW9uIENBIDUgLSBH
            MTEmMCQGA1UECwwdQXBwbGUgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkxEzARBgNVBAoMCkFwcGxl
            IEluYy4xCzAJBgNVBAYTAlVTAhBCBNMtTmPM37+D65ivVXYxMA0GCWCGSAFlAwQCAQUAMAoGCCqG
            SM49BAMCBEYwRAIgSrxUj464l0Zt2QcRj2j/mS4e8io7/2uBgQBv45l5hisCIC3hbQm9zKM/rgGf
            HcbSldc1kgZdNW0Pybznj3sw7xsuAAAAAAAAaGF1dGhEYXRhWKScUc/671kk0hRalBqSjlvrEK72
            y88dNDlxiRhpbZ9eDEAAAAAAYXBwYXR0ZXN0ZGV2ZWxvcAAgw7yQ81hgLXGmdPU77d+DxVCE7U9O
            6EznAmvESs/gdzylAQIDJiABIVgg5bL8NA0zVQu6tRHlgh5JxfStwxBFAZIna6F6FCjYEBAiWCAR
            Ua/BzgQsDEm2kmbusTaWpCmgwXDzO3DI8+k2kklAmg==
        """.trimIndent().replace("\n", ""))

        @OptIn(ExperimentalEncodingApi::class)
        val ASSERTION = Base64.decode("""
            om1hc3NlcnRpb25EYXRhU6JkdHlwZWVOb25jZWVub25jZUBxcGxhdGZvcm1Bc3NlcnRpb25YjqJp
            c2lnbmF0dXJlWEgwRgIhAMxcZR76H1kwqR2/iqcAQNrXXsR9N+4ur45mdPW6pe0bAiEAzBhho0+u
            jvWvgRpbj/px4Ctmjc7A/BWtg6BFrA/0cipxYXV0aGVudGljYXRvckRhdGFYJZxRz/rvWSTSFFqU
            GpKOW+sQrvbLzx00OXGJGGltn14MQAAAAAE=
        """.trimIndent().replace("\n", ""))
    }
}

