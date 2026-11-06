package org.multipaz.compose.presentment

import android.annotation.SuppressLint
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.EmbeddedBiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil3.ImageLoader
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.branding.Branding
import org.multipaz.consent.ClassifySimpleConsentFn
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.document.Document
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_continue
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.prompt.PlatformConsentOptions
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.AndroidKeystoreKeyUnlockData
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.UserAuthenticationType
import org.multipaz.securearea.software.SoftwareKeyUnlockData
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.securearea.software.SoftwareUserAuthType
import org.multipaz.util.Logger
import kotlin.time.Duration

private const val TAG = "ConsentModalBottomSheet"

private class ResolvedEmbeddedBiometricSession(
    val promptData: SimpleConsentPromptData,
    val unlockData: KeyUnlockData?,
    val allowedAuthenticators: Int,
)

internal actual fun shouldShowOnBiometricPrompt(
    platformConsentOptions: PlatformConsentOptions?,
): Boolean = (platformConsentOptions?.showOnBiometricPrompt ?: true) &&
    Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2

@SuppressLint("NewApi")
@Composable
internal actual fun ConsentEmbeddedBiometricPrompt(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    preselectedDocuments: List<Document>,
    imageLoader: ImageLoader?,
    classifySimpleConsentFn: ClassifySimpleConsentFn,
    onDocumentsInFocus: (documents: List<Document>) -> Unit,
    onShowDetails: () -> Unit,
    onConfirm: (selection: PresentmentSelection) -> Unit,
    onCancel: () -> Unit,
) {
    if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.CINNAMON_BUN_2) {
        LaunchedEffect(Unit) {
            onShowDetails()
        }
        return
    }
    val context = LocalContext.current
    val hostView = LocalView.current
    val parentComposition = rememberCompositionContext()
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val currentBranding = Branding.Current.collectAsState().value
    val fallbackTitle = currentBranding.appName
        ?: stringResource(Res.string.credential_presentment_button_continue)

    val latestOnDocumentsInFocus by rememberUpdatedState(onDocumentsInFocus)
    val latestOnShowDetails by rememberUpdatedState(onShowDetails)
    val latestOnConfirm by rememberUpdatedState(onConfirm)
    val latestOnCancel by rememberUpdatedState(onCancel)

    var resolvedSession by remember(
        requester,
        trustedRequesterIdentity,
        presentmentData,
        preselectedDocuments,
        classifySimpleConsentFn,
    ) {
        mutableStateOf<ResolvedEmbeddedBiometricSession?>(null)
    }

    LaunchedEffect(
        requester,
        trustedRequesterIdentity,
        presentmentData,
        preselectedDocuments,
        classifySimpleConsentFn,
        currentBranding,
    ) {
        val promptData = resolveSimpleConsentPromptData(
            requester = requester,
            trustedRequesterIdentity = trustedRequesterIdentity,
            presentmentData = presentmentData,
            preselectedDocuments = preselectedDocuments,
            classifySimpleConsentFn = classifySimpleConsentFn,
        )
        if (promptData == null) {
            latestOnShowDetails()
            return@LaunchedEffect
        }

        val credential = promptData.simpleConsent.credential
        var unlockData: KeyUnlockData? = null
        var allowedAuthenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (credential is SecureAreaBoundCredential) {
            when (val secureArea = credential.secureArea) {
                is AndroidKeystoreSecureArea -> {
                    try {
                        val keyInfo = secureArea.getKeyInfo(credential.alias)
                        if (keyInfo.isUserAuthenticationRequired) {
                            if (keyInfo.userAuthenticationTimeout == Duration.ZERO) {
                                // EmbeddedBiometricPrompt does not support CryptoObject-bound (per-use)
                                // keys; fall back to the full consent prompt and standard BiometricPrompt.
                                latestOnShowDetails()
                                return@LaunchedEffect
                            }
                            var authTypes = 0
                            if (UserAuthenticationType.BIOMETRIC in keyInfo.userAuthenticationTypes) {
                                authTypes = authTypes or BiometricManager.Authenticators.BIOMETRIC_STRONG
                            }
                            if (UserAuthenticationType.LSKF in keyInfo.userAuthenticationTypes) {
                                authTypes = authTypes or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            }
                            if (authTypes == 0) {
                                latestOnShowDetails()
                                return@LaunchedEffect
                            }
                            allowedAuthenticators = authTypes
                            unlockData = AndroidKeystoreKeyUnlockData(secureArea, credential.alias)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Logger.w(TAG, "Unable to prepare AndroidKeystore key for EmbeddedBiometricPrompt, falling back", e)
                        latestOnShowDetails()
                        return@LaunchedEffect
                    }
                }
                is SoftwareSecureArea -> {
                    val keyInfo = secureArea.getKeyInfo(credential.alias)
                    if (keyInfo.isPassphraseProtected) {
                        latestOnShowDetails()
                        return@LaunchedEffect
                    }
                    if (keyInfo.isUserAuthenticationRequired) {
                        var authTypes = 0
                        if (SoftwareUserAuthType.BIOMETRIC in keyInfo.userAuthenticationTypes) {
                            authTypes = authTypes or BiometricManager.Authenticators.BIOMETRIC_STRONG
                        }
                        if (SoftwareUserAuthType.PASSCODE in keyInfo.userAuthenticationTypes) {
                            authTypes = authTypes or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        }
                        if (authTypes == 0) {
                            latestOnShowDetails()
                            return@LaunchedEffect
                        }
                        allowedAuthenticators = authTypes
                        unlockData = SoftwareKeyUnlockData(
                            secureArea = secureArea,
                            alias = credential.alias,
                            userAuthenticated = true,
                        )
                    }
                }
                else -> {
                    latestOnShowDetails()
                    return@LaunchedEffect
                }
            }
        }

        // EmbeddedBiometricPrompt only renders the embedded consent view on the biometric bottom
        // sheet; if only LSKF (DEVICE_CREDENTIAL) is allowed or enrolled, Android jumps straight
        // to the full-screen LSKF entry UI without displaying our embedded consent UI.
        val biometricManager = context.getSystemService(BiometricManager::class.java)
        if ((allowedAuthenticators and BiometricManager.Authenticators.BIOMETRIC_STRONG) == 0 ||
            biometricManager == null ||
            biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            latestOnShowDetails()
            return@LaunchedEffect
        }

        latestOnDocumentsInFocus(listOf(credential.document))
        resolvedSession = ResolvedEmbeddedBiometricSession(
            promptData = promptData,
            unlockData = unlockData,
            allowedAuthenticators = allowedAuthenticators,
        )
    }

    val activeSession = resolvedSession ?: return

    DisposableEffect(activeSession) {
        var isFinished = false
        val cancellationSignal = CancellationSignal()
        val callback = object : EmbeddedBiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (!isFinished) {
                    isFinished = true
                    val baseSelection = activeSession.promptData.simpleConsent.selection
                    val finalSelection = if (activeSession.unlockData != null) {
                        baseSelection.copy(preloadedKeyUnlockData = listOf(activeSession.unlockData))
                    } else {
                        baseSelection
                    }
                    latestOnConfirm(finalSelection)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!isFinished) {
                    isFinished = true
                    latestOnCancel()
                }
            }

            override fun onBackPressed() {
                if (!isFinished) {
                    isFinished = true
                    latestOnCancel()
                }
            }

            override fun onTappedOutside() {
                if (!isFinished) {
                    isFinished = true
                    cancellationSignal.cancel()
                    latestOnCancel()
                }
            }
        }

        try {
            val prompt = EmbeddedBiometricPrompt.Builder(context)
                .setTitle(fallbackTitle)
                .setAllowedAuthenticators(activeSession.allowedAuthenticators)
                .setEmbeddedContentFactory { windowContext ->
                    ComposeView(windowContext).apply {
                        setParentCompositionContext(parentComposition)
                        val lifecycleOwner = hostView.findViewTreeLifecycleOwner()
                        setViewTreeLifecycleOwner(lifecycleOwner)
                        setViewTreeViewModelStoreOwner(hostView.findViewTreeViewModelStoreOwner())
                        setViewTreeSavedStateRegistryOwner(hostView.findViewTreeSavedStateRegistryOwner())
                        val backPressedDispatcherOwner = if (lifecycleOwner != null) {
                            object : OnBackPressedDispatcherOwner {
                                override val lifecycle = lifecycleOwner.lifecycle
                                override val onBackPressedDispatcher = OnBackPressedDispatcher()
                            }
                        } else {
                            hostView.findViewTreeOnBackPressedDispatcherOwner()
                        }
                        if (backPressedDispatcherOwner != null) {
                            setViewTreeOnBackPressedDispatcherOwner(backPressedDispatcherOwner)
                        }
                        setContent {
                            MaterialTheme(
                                colorScheme = colorScheme,
                                typography = typography,
                                shapes = shapes,
                            ) {
                                SimpleConsentPrompt(
                                    data = activeSession.promptData,
                                    imageLoader = imageLoader,
                                    onShowDetails = {
                                        if (!isFinished) {
                                            isFinished = true
                                            cancellationSignal.cancel()
                                            latestOnShowDetails()
                                        }
                                    },
                                    onCancel = {
                                        if (!isFinished) {
                                            isFinished = true
                                            cancellationSignal.cancel()
                                            latestOnCancel()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                .build()

            prompt.startAuthenticationSession(
                cancellationSignal,
                EmbeddedBiometricPrompt.START_MODE_IMMEDIATE,
                context.mainExecutor,
                callback,
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Unable to start EmbeddedBiometricPrompt session, falling back", e)
            isFinished = true
            latestOnShowDetails()
        }

        onDispose {
            if (!isFinished) {
                cancellationSignal.cancel()
            }
        }
    }
}
