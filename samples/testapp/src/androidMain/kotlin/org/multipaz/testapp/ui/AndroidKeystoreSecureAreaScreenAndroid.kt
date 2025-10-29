package org.multipaz.testapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.multipaz.securearea.AndroidKeystoreCreateKeySettings
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.UserAuthenticationType
import org.multipaz.cbor.Cbor
import org.multipaz.context.applicationContext
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.javaX509Certificate
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.KeyAttestation
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.securearea.UnlockReason
import org.multipaz.util.Platform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "AndroidKeystoreSecureAreaScreen"

private val androidKeystoreCapabilities: AndroidKeystoreSecureArea.Capabilities by lazy {
    AndroidKeystoreSecureArea.Capabilities()
}

private val keymintVersionTee: Int by lazy {
    getFeatureVersionKeystore(applicationContext, false)
}

private val keymintVersionStrongBox: Int by lazy {
    getFeatureVersionKeystore(applicationContext, true)
}

@Composable
actual fun AndroidKeystoreSecureAreaScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
    onViewCertificate: (encodedCertificateData: String) -> Unit
) {
    val showCapabilitiesDialog = remember {
        mutableStateOf<AndroidKeystoreSecureArea.Capabilities?>(null)
    }

    if (showCapabilitiesDialog.value != null) {
        ShowCapabilitiesDialog(
            showCapabilitiesDialog.value!!,
            onDismissRequest = {
                showCapabilitiesDialog.value = null
            })
    }

    val coroutineScope = rememberCoroutineScope { promptModel }

    LazyColumn {

        item {
            TextButton(onClick = {
                // TODO: Does a lot of I/O, cannot run on UI thread                 
                showCapabilitiesDialog.value = androidKeystoreCapabilities
            })
            {
                Text(
                    text = "Versions and Capabilities",
                    fontSize = 15.sp
                )
            }
        }

        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    try {
                        val attestation = aksAttestation(useStrongBox = false, useAttestKey = false)
                        Logger.d(TAG, "attestation: " + attestation)
                        withContext(Dispatchers.Main) {
                            onViewCertificate(Cbor.encode(attestation.certChain!!.toDataItem()).toBase64Url())
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace();
                        showToast("${e.message}")
                    }
                }
            })
            {
                Text(
                    text = "Attestation",
                    fontSize = 15.sp
                )
            }
        }

        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    try {
                        val attestation = aksAttestation(useStrongBox = false, useAttestKey = true)
                        Logger.d(TAG, "attestation: " + attestation)
                        withContext(Dispatchers.Main) {
                            onViewCertificate(Cbor.encode(attestation.certChain!!.toDataItem()).toBase64Url())
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace();
                        showToast("${e.message}")
                    }
                }
            })
            {
                Text(
                    text = "Attestation w/ Attest Key",
                    fontSize = 15.sp
                )
            }
        }

        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    try {
                        val attestation = aksAttestation(useStrongBox = true, useAttestKey = false)
                        Logger.d(TAG, "attestation: " + attestation)
                        withContext(Dispatchers.Main) {
                            onViewCertificate(
                                Cbor.encode(attestation.certChain!!.toDataItem()).toBase64Url()
                            )
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace();
                        showToast("${e.message}")
                    }
                }
            })
            {
                Text(
                    text = "StrongBox Attestation",
                    fontSize = 15.sp
                )
            }
        }

        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    try {
                        val attestation = aksAttestation(useStrongBox = true, useAttestKey = true)
                        Logger.d(TAG, "attestation: " + attestation)
                        withContext(Dispatchers.Main) {
                            onViewCertificate(
                                Cbor.encode(attestation.certChain!!.toDataItem()).toBase64Url()
                            )
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace();
                        showToast("${e.message}")
                    }
                }
            })
            {
                Text(
                    text = "StrongBox Attestation w/ Attest Key",
                    fontSize = 15.sp
                )
            }
        }

        for ((strongBox, strongBoxDesc) in arrayOf(
            Pair(false, ""), Pair(true, "StrongBox ")
        )) {
            for (algorithm in listOf(
                Algorithm.ESP256,
                Algorithm.ED25519,
                Algorithm.ECDH_P256,
                Algorithm.ECDH_X25519,
            )) {
                val AUTH_NONE = setOf<UserAuthenticationType>()
                val AUTH_LSKF_OR_BIOMETRIC = setOf(
                    UserAuthenticationType.LSKF,
                    UserAuthenticationType.BIOMETRIC)
                val AUTH_LSKF_ONLY = setOf(UserAuthenticationType.LSKF)
                val AUTH_BIOMETRIC_ONLY = setOf(UserAuthenticationType.BIOMETRIC)
                for ((userAuthType, authTimeout, authDesc) in arrayOf(
                    Triple(AUTH_NONE, 0L, ""),
                    Triple(AUTH_LSKF_OR_BIOMETRIC, 0L, "- Auth"),
                    Triple(AUTH_LSKF_OR_BIOMETRIC, 10 * 1000L, "- Auth (10 sec)"),
                    Triple(AUTH_LSKF_ONLY, 0L, "- Auth (LSKF Only)"),
                    Triple(AUTH_BIOMETRIC_ONLY, 0L, "- Auth (Biometric Only)"),
                    Triple(AUTH_LSKF_OR_BIOMETRIC, -1L, "- Auth (No Confirmation)"),
                )) {
                    // For brevity, Only do auth for P-256 Sign and Mac
                    if (algorithm.curve!! != EcCurve.P256 && userAuthType != AUTH_NONE) {
                        continue
                    }

                    val biometricConfirmationRequired = (authTimeout >= 0L)
                    item {
                        TextButton(onClick = {
                            coroutineScope.launch {
                                aksTest(
                                    algorithm,
                                    userAuthType != AUTH_NONE,
                                    if (authTimeout < 0) 0.seconds else authTimeout.milliseconds,
                                    userAuthType,
                                    biometricConfirmationRequired,
                                    strongBox,
                                    showToast
                                )
                            }
                        })
                        {
                            Text(
                                text = "$strongBoxDesc$algorithm $authDesc",
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// Unfortunately this is API is only available to system apps so we
// have to use reflection to use it.
@SuppressLint("PrivateApi")
private fun getFirstApiLevel(): Int =
    try {
        val c = Class.forName("android.os.SystemProperties")
        val get = c.getMethod("get", String::class.java)
        val firstApiLevelString = get.invoke(c, "ro.product.first_api_level") as String
        firstApiLevelString.toInt()
    } catch (e: java.lang.Exception) {
        Logger.w(TAG, "Error getting ro.product.first_api_level", e)
        0
    }

private fun getNameForApiLevel(apiLevel: Int): String {
    val fields = Build.VERSION_CODES::class.java.fields
    var codeName = "UNKNOWN"
    fields.filter { it.getInt(Build.VERSION_CODES::class) == apiLevel }
        .forEach { codeName = it.name }
    return codeName
}

@Composable
fun ShowCapabilitiesDialog(capabilities: AndroidKeystoreSecureArea.Capabilities,
                           onDismissRequest: () -> Unit) {
    Dialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Versions and Capabilities",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge
                )
                Column(
                    modifier = Modifier
                        .size(400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val apiLevel = Build.VERSION.SDK_INT
                    val firstApiLevel = getFirstApiLevel()
                    // Would be nice to show first API level but that's only available to tests.
                    Text(
                        text = "API Level: ${apiLevel} (${getNameForApiLevel(apiLevel)})\n" +
                                "First API Level: ${firstApiLevel} (${getNameForApiLevel(firstApiLevel)})\n" +
                                "TEE KeyMint version: ${keymintVersionTee}\n" +
                                "StrongBox KeyMint version: ${keymintVersionStrongBox}",
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val userAuthText =
                        if (capabilities.multipleAuthenticationTypesSupported)
                            "LSKF or Bio or LSKF+Bio"
                        else "Only LSKF+Bio"
                    val secureLockScreenText =
                        if (capabilities.secureLockScreenSetup)
                            "Enabled"
                        else
                            "Not Enabled"
                    Text(
                        text = "User Auth: $userAuthText\n" +
                                "Secure Lock Screen: $secureLockScreenText",
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Attest Key support (TEE): ${capabilities.attestKeySupported}\n" +
                                "Key Agreement support (TEE): ${capabilities.keyAgreementSupported}\n" +
                                "Curve 25519 support (TEE): ${capabilities.curve25519Supported}",
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "StrongBox Available: ${capabilities.strongBoxSupported}\n" +
                                "Attest Key support (StrongBox): ${capabilities.strongBoxAttestKeySupported}\n" +
                                "Key Agreement support (StrongBox): ${capabilities.strongBoxKeyAgreementSupported}\n" +
                                "Curve 25519 support (StrongBox): ${capabilities.strongBoxCurve25519Supported}",
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onDismissRequest() },
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowCertificateDialog(attestation: X509CertChain,
                          onDismissRequest: () -> Unit) {
    var certNumber by rememberSaveable() { mutableIntStateOf(0) }
    if (certNumber < 0 || certNumber >= attestation.certificates.size) {
        certNumber = 0
    }
    Dialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(650.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Certificates",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge
                )
                Row() {
                    Text(
                        text = "Certificate ${certNumber + 1} of ${attestation.certificates.size}",
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        enabled = (certNumber > 0),
                        onClick = {
                            certNumber -= 1
                        }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    IconButton(
                        enabled = (certNumber < attestation.certificates.size - 1),
                        onClick = {
                            certNumber += 1
                        }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                    }
                }
                Column(
                    modifier = Modifier
                        .size(470.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = styledX509CertificateText(
                            attestation.certificates[certNumber].javaX509Certificate.toString()),
                        //text = attestation[certNumber].toString(),
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Start,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onDismissRequest() },
                    ) {
                        Text("Close")
                    }
                }

            }
        }
    }
}

@Composable
private fun styledX509CertificateText(certificateText: String): AnnotatedString {
    val lines = certificateText.split("\n")
    return buildAnnotatedString {
        for (line in lines) {
            var colonPos = line.indexOf(':')
            if (colonPos > 0 && line.length > (colonPos + 1) && !line[colonPos + 1].isWhitespace()) {
                colonPos = -1
            }
            if (colonPos > 0) {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(line.subSequence(0, colonPos))
                }
                append(line.subSequence(colonPos,line.length))
            } else {
                append(line)
            }
            append("\n")
        }
    }
}

private fun getFeatureVersionKeystore(appContext: Context, useStrongbox: Boolean): Int {
    var feature = "NOT_DEFINED"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        feature = PackageManager.FEATURE_HARDWARE_KEYSTORE
    }
    if (useStrongbox) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            feature = PackageManager.FEATURE_STRONGBOX_KEYSTORE
        }
    }
    val pm = appContext.packageManager
    if (pm.hasSystemFeature(feature)) {
        var info: FeatureInfo? = null
        val infos = pm.systemAvailableFeatures
        for (n in infos.indices) {
            val i = infos[n]
            if (i.name == feature) {
                info = i
                break
            }
        }
        var version = 0
        if (info != null) {
            version = info.version
        }
        // It's entirely possible that the feature exists but the version number hasn't
        // been set. In that case, assume it's at least KeyMaster 4.1.
        if (version < 41) {
            version = 41
        }
        return version
    }
    // It's only a requirement to set PackageManager.FEATURE_HARDWARE_KEYSTORE since
    // Android 12 so for old devices this isn't set. However all devices since Android
    // 8.1 has had HW-backed keystore so in this case we can report KeyMaster 4.1
    if (!useStrongbox) {
        return 41
    }
    return 0
}

private suspend fun aksAttestation(
    useStrongBox: Boolean,
    useAttestKey: Boolean
): KeyAttestation {
    val now = Clock.System.now()
    val thirtyDaysFromNow = now + 30.days
    val androidKeystoreSecureArea = Platform.getSecureArea() as AndroidKeystoreSecureArea

    val attestKeyAlias = if (useAttestKey) {
        androidKeystoreSecureArea.createKey(
            "attestKey",
            AndroidKeystoreCreateKeySettings.Builder("AttestKeyChallenge".encodeToByteString())
                .setAlgorithm(Algorithm.ANDROID_KEYSTORE_ATTEST_KEY)
                .setUseStrongBox(useStrongBox)
                .build()
        )
        "attestKey"
    } else {
        null
    }

    androidKeystoreSecureArea.createKey(
        "testKey",
        AndroidKeystoreCreateKeySettings.Builder("Challenge".encodeToByteString())
            .setUserAuthenticationRequired(
                true, 10.seconds,
                setOf(UserAuthenticationType.LSKF, UserAuthenticationType.BIOMETRIC)
            )
            .setValidityPeriod(now, thirtyDaysFromNow)
            .setUseStrongBox(useStrongBox)
            .setAttestKeyAlias(attestKeyAlias)
            .build()
    )
    return androidKeystoreSecureArea.getKeyInfo("testKey").attestation
}

private suspend fun aksTest(
    algorithm: Algorithm,
    authRequired: Boolean,
    authTimeout: Duration,
    userAuthType: Set<UserAuthenticationType>,
    biometricConfirmationRequired: Boolean,
    strongBox: Boolean,
    showToast: (message: String) -> Unit) {
    Logger.d(
        TAG,
        "aksTest algorith:$algorithm authRequired:$authRequired authTimeout:$authTimeout strongBox:$strongBox"
    )
    try {
        aksTestUnguarded(algorithm, authRequired, authTimeout, userAuthType,
            biometricConfirmationRequired, strongBox, showToast)
    } catch (e: Throwable) {
        e.printStackTrace();
        showToast("${e.message}")
    }
}

private suspend fun aksTestUnguarded(
    algorithm: Algorithm,
    authRequired: Boolean,
    authTimeout: Duration,
    userAuthType: Set<UserAuthenticationType>,
    biometricConfirmationRequired: Boolean,
    strongBox: Boolean,
    showToast: (message: String) -> Unit) {

    val androidKeystoreSecureArea = Platform.getSecureArea() as AndroidKeystoreSecureArea
    androidKeystoreSecureArea.createKey(
        "testKey",
        AndroidKeystoreCreateKeySettings.Builder("Challenge".encodeToByteString())
            .setAlgorithm(algorithm)
            .setUserAuthenticationRequired(
                authRequired, authTimeout, userAuthType)
            .setUseStrongBox(strongBox)
            .build()
    )

    if (algorithm.isSigning) {
        val dataToSign = "data".toByteArray()
        val t0 = System.currentTimeMillis()
        val signature = androidKeystoreSecureArea.sign(
            "testKey",
            dataToSign,
            unlockReason = UnlockReason.HumanReadable(
                title = "Test prompt title",
                subtitle = "Test prompt subtitle",
                requireConfirmation = biometricConfirmationRequired
            )
        )
        val t1 = System.currentTimeMillis()
        Logger.d(
            TAG,
            "Made signature with key " +
                    "r=${signature.r.toHex()} s=${signature.s.toHex()}",
        )
        showToast("EC signature in (${t1 - t0} msec)")
    } else {
        val otherKeyPairForEcdh = Crypto.createEcPrivateKey(algorithm.curve!!)
        val t0 = System.currentTimeMillis()
        val Zab = androidKeystoreSecureArea.keyAgreement(
            "testKey",
            otherKeyPairForEcdh.publicKey,
            unlockReason = UnlockReason.HumanReadable(
                title = "Test prompt title",
                subtitle = "Test prompt subtitle",
                requireConfirmation = biometricConfirmationRequired
            )
        )
        val t1 = System.currentTimeMillis()
        Logger.dHex(
            TAG,
            "Calculated ECDH",
            Zab)
        showToast("ECDH in (${t1 - t0} msec)")
    }
}
