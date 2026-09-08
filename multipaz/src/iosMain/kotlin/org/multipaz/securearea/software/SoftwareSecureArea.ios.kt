package org.multipaz.securearea.software

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.PromptModelNotAvailableException
import org.multipaz.prompt.Reason
import org.multipaz.securearea.KeyLockedException
import org.multipaz.util.Logger
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAAccessControlOperationUseItem
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAccessControlDevicePasscode
import platform.Security.kSecAccessControlUserPresence
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import kotlin.coroutines.resume

private const val TAG = "SoftwareSecureArea"

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun softwareSecureAreaPerformUserAuth(
    alias: String,
    userAuthenticationTypes: Set<SoftwareUserAuthType>,
    unlockReason: Reason
): Boolean {
    val promptModel = try {
        PromptModel.get()
    } catch (_: PromptModelNotAvailableException) {
        throw KeyLockedException("Key is locked and PromptModel is not available to unlock interactively")
    }

    val humanReadable = promptModel.toHumanReadable(unlockReason, null)
    val localizedReason = if (humanReadable.subtitle.isNotBlank()) {
        "${humanReadable.title} - ${humanReadable.subtitle}"
    } else {
        humanReadable.title
    }

    var flags = 0UL
    if (userAuthenticationTypes.contains(SoftwareUserAuthType.PASSCODE) &&
        userAuthenticationTypes.contains(SoftwareUserAuthType.BIOMETRIC)
    ) {
        flags = kSecAccessControlUserPresence
    } else if (userAuthenticationTypes.contains(SoftwareUserAuthType.PASSCODE)) {
        flags = kSecAccessControlDevicePasscode
    } else if (userAuthenticationTypes.contains(SoftwareUserAuthType.BIOMETRIC)) {
        flags = kSecAccessControlBiometryCurrentSet
    } else {
        flags = kSecAccessControlUserPresence
    }

    val accessControl = SecAccessControlCreateWithFlags(
        allocator = null,
        protection = kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        flags = flags,
        error = null
    )

    return suspendCancellableCoroutine { continuation ->
        val laContext = LAContext()
        if (accessControl != null) {
            laContext.evaluateAccessControl(
                accessControl = accessControl,
                operation = LAAccessControlOperationUseItem,
                localizedReason = localizedReason
            ) { success, error ->
                if (continuation.isActive) {
                    if (error != null) {
                        Logger.d(TAG, "LAContext evaluateAccessControl error: ${error.localizedDescription}")
                    }
                    continuation.resume(success)
                }
            }
        } else {
            val requireBiometricsOnly = userAuthenticationTypes.contains(SoftwareUserAuthType.BIOMETRIC) &&
                    !userAuthenticationTypes.contains(SoftwareUserAuthType.PASSCODE)
            val policy = if (requireBiometricsOnly) {
                LAPolicyDeviceOwnerAuthenticationWithBiometrics
            } else {
                LAPolicyDeviceOwnerAuthentication
            }
            if (requireBiometricsOnly) {
                laContext.localizedFallbackTitle = ""
            }
            laContext.evaluatePolicy(
                policy = policy,
                localizedReason = localizedReason
            ) { success, error ->
                if (continuation.isActive) {
                    if (error != null) {
                        Logger.d(TAG, "LAContext evaluatePolicy error: ${error.localizedDescription}")
                    }
                    continuation.resume(success)
                }
            }
        }
    }
}


