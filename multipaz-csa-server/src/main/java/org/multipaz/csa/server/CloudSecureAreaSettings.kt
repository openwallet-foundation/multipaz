package org.multipaz.csa.server

import kotlinx.io.bytestring.ByteString
import org.multipaz.rpc.backend.Configuration
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url

class CloudSecureAreaSettings(private val conf: Configuration) {

    val iosReleaseBuild
        get() = getBool("ios_require_release_build", false)

    val iosAppIdentifiers
        get() = getStringList("ios_require_app_identifiers")

    val androidRequireGmsAttestation
        get() = getBool("android_require_gms_attestation", true)

    val androidRequireVerifiedBootGreen
        get() = getBool("android_require_verified_boot_green", true)

    val androidRequireAppSignatureCertificateDigests: List<ByteString>
        get() = getStringList("android_require_app_signature_certificate_digests").map {
            ByteString(it.fromBase64Url())
        }

    val androidRequireAppPackageNames: List<String>
        get() = getStringList("android_require_app_package_names")

    val androidRequireKeystoreSecurityLevel: String?
        get() = getString("android_require_keystore_security_level")

    val cloudSecureAreaRekeyingIntervalSeconds: Int
        get() = getInt("cloud_secure_area_rekeying_interval_seconds", 300)

    val cloudSecureAreaLockoutNumFailedAttempts: Int
        get() = getInt("cloud_secure_area_lockout_num_failed_attempts", 3)

    val cloudSecureAreaLockoutDurationSeconds: Int
        get() = getInt("cloud_secure_area_lockout_duration_seconds", 60)

    val openid4vciKeyAttestationIssuer
        get() = getString("openid4vci_key_attestation_issuer")

    val openid4vciKeyAttestationKeyStorage
        get() = getString("openid4vci_key_attestation_key_storage")

    val openid4vciKeyAttestationUserAuthentication
        get() = getString("openid4vci_key_attestation_user_authentication")

    val openid4vciKeyAttestationUserAuthenticationNoPassphrase
        get() = getString("openid4vci_key_attestation_user_authentication_no_passphrase")

    val openid4vciKeyAttestationCertification
        get() = getString("openid4vci_key_attestation_certification")

    private fun getString(key: String) = conf.getValue(key)

    private fun getBool(key: String, defaultValue: Boolean = false): Boolean {
        val value = conf.getValue(key)
        if (value == null) {
            Logger.d(TAG, "getBool: No value configuration value with key $key, return default value $defaultValue")
            return defaultValue
        }
        if (value == "true") {
            return true
        } else if (value == "false") {
            return false
        }
        Logger.d(TAG, "getBool: Unexpected value '$value' with key $key, return default value $defaultValue")
        return defaultValue
    }

    private fun getInt(key: String, defaultValue: Int = 0): Int {
        val value = conf.getValue(key)
        if (value == null) {
            Logger.d(TAG, "getInt: No value configuration value with key $key, return default value $defaultValue")
            return defaultValue
        }
        try {
            return value.toInt()
        } catch (e: Throwable) {
            Logger.d(TAG, "getInt: Unexpected value '$value' with key $key, return default value $defaultValue")
        }
        return defaultValue
    }

    fun getStringList(key: String): List<String> {
        val value = conf.getValue(key)
        if (value == null) {
            Logger.d(TAG, "getStringList: No value configuration value with key $key")
            return emptyList()
        }
        if (value.isEmpty()) {
            return emptyList()
        }
        return value.split("\\s+".toRegex())
    }

    companion object {
        private const val TAG = "CloudSecureAreaSettings"
    }
}
