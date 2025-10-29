package org.multipaz.secure_area_test_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.cloud.CloudCreateKeySettings
import org.multipaz.securearea.cloud.CloudSecureArea
import org.multipaz.securearea.cloud.CloudUserAuthType
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testapp.App
import org.multipaz.util.Logger
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.testapp.platformHttpClientEngineFactory
import kotlin.time.Duration.Companion.days

private val TAG = "CloudSecureAreaScreen"

private var cloudSecureArea: CloudSecureArea? = null

@Composable
fun CloudSecureAreaScreen(
    app: App,
    showToast: (message: String) -> Unit,
    onViewCertificate: (encodedCertificateData: String) -> Unit
) {
    var connectText by remember { mutableStateOf(
        if (cloudSecureArea == null) {
            "Click to connect to Cloud Secure Area"
        } else {
            "Connected to ${cloudSecureArea!!.serverUrl}"
        }
    ) }
    var connectColor by remember {
        if (cloudSecureArea == null) {
            mutableStateOf(Color.Red)
        } else {
            mutableStateOf(Color.Blue)
        }
    }
    val showConnectDialog = remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope { app.promptModel }

    if (showConnectDialog.value) {
        CsaConnectDialog(
            app.settingsModel.cloudSecureAreaUrl.value,
            onDismissRequest = {
                showConnectDialog.value = false
            },
            onConnectButtonClicked = { url: String, walletPin: String, constraints: PassphraseConstraints ->
                app.settingsModel.cloudSecureAreaUrl.value = url
                showConnectDialog.value = false
                coroutineScope.launch {
                    cloudSecureArea = CloudSecureArea.create(
                        EphemeralStorage(),
                        "CloudSecureArea",
                        url,
                        platformHttpClientEngineFactory()
                    )
                    try {
                        cloudSecureArea!!.register(
                            walletPin,
                            constraints
                        ) { true }
                        showToast("Registered with CSA")
                        connectText =
                            "Connected to ${cloudSecureArea!!.serverUrl}"
                        connectColor = Color.Blue
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        cloudSecureArea = null
                        showToast("${e.message}")
                    }
                }
            }
        )
    }

    LazyColumn {

        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    if (cloudSecureArea != null) {
                        cloudSecureArea!!.unregister()
                        cloudSecureArea = null
                        connectText = "Click to connect to Cloud Secure Area"
                        connectColor = Color.Red
                    } else {
                        showConnectDialog.value = true
                    }
                }
            })
            {
                Text(
                    text = connectText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = connectColor
                )
            }
        }

        item {
            TextButton(onClick = {
                coroutineScope.launch {
                    try {
                        val attestation = csaAttestation(showToast)
                        if (attestation != null) {
                            Logger.d(TAG, "attestation: ${attestation.certChain}")
                            withContext(Dispatchers.Main) {
                                onViewCertificate(Cbor.encode(attestation.certChain!!.toDataItem()).toBase64Url())
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        showToast("${e.message}")
                    }
                }
            })
            {
                Text(
                    text = "CSA Attestation",
                    fontSize = 15.sp
                )
            }
        }

        for (algorithm in listOf(
            Algorithm.ESP256,
            Algorithm.ESP384,
            Algorithm.ESP512,
            Algorithm.ESB256,
            Algorithm.ESB320,
            Algorithm.ESB384,
            Algorithm.ESB512,
            Algorithm.ED25519,
            Algorithm.ED448,
            Algorithm.ECDH_P256,
            Algorithm.ECDH_P384,
            Algorithm.ECDH_P521,
            Algorithm.ECDH_BRAINPOOLP256R1,
            Algorithm.ECDH_BRAINPOOLP320R1,
            Algorithm.ECDH_BRAINPOOLP384R1,
            Algorithm.ECDH_BRAINPOOLP512R1,
            Algorithm.ECDH_X25519,
            Algorithm.ECDH_X448,
        )) {
            for ((passphraseRequired, passphraseDescription) in arrayOf(
                Pair(false, ""),
                Pair(true, "- Passphrase ")
            )) {
                for ((userAuthRequired, userAuthTypes, authDesc) in listOf(
                    Triple(false, setOf(), ""),
                    Triple(true, setOf(CloudUserAuthType.PASSCODE), "- Auth (PIN only)"),
                    Triple(true, setOf(CloudUserAuthType.BIOMETRIC), "- Auth (Biometric only)"),
                    Triple(true, setOf(
                        CloudUserAuthType.PASSCODE,
                        CloudUserAuthType.BIOMETRIC
                    ), "- Auth (PIN or Biometric)")
                )) {
                    // For brevity, only do passphrase and auth for first item (P-256 Signature)
                    if (!(algorithm.curve!! == EcCurve.P256 && algorithm.isSigning)) {
                        if (userAuthRequired || passphraseRequired) {
                            continue
                        }
                    }

                    item {
                        TextButton(onClick = {
                            coroutineScope.launch {
                                csaTest(
                                    algorithm = algorithm,
                                    authRequired = userAuthRequired,
                                    authTypes = userAuthTypes,
                                    passphraseRequired = passphraseRequired,
                                    showToast = showToast
                                )
                            }
                        })
                        {
                            Text(
                                text = "${algorithm.name} $passphraseDescription$authDesc",
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class CsaPassphraseChoice(
    val description: String,
    val constraints: PassphraseConstraints,
    val passphrase: String,
)

@Composable
fun CsaConnectDialog(
    url: String,
    onDismissRequest: () -> Unit,
    onConnectButtonClicked: (url: String, walletPin: String, constraints: PassphraseConstraints) -> Unit,
) {
    var urlTextField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(url))
    }

    val walletPinRadioOptions = remember {
        listOf(
            CsaPassphraseChoice("4 Digits (1111)", PassphraseConstraints.PIN_FOUR_DIGITS, "1111"),
            CsaPassphraseChoice("4 Digits (1234)", PassphraseConstraints.PIN_FOUR_DIGITS, "1234"),
            CsaPassphraseChoice("6 Digits (111111)", PassphraseConstraints.PIN_SIX_DIGITS, "111111"),
            CsaPassphraseChoice("4+ Digits (12345)", PassphraseConstraints.PIN_FOUR_DIGITS_OR_LONGER, "12345"),
            CsaPassphraseChoice("4 Chars (abcd)", PassphraseConstraints.PASSPHRASE_FOUR_CHARS, "abcd"),
            CsaPassphraseChoice("4+ Chars (abcde)", PassphraseConstraints.PASSPHRASE_FOUR_CHARS_OR_LONGER, "abcde"),
            CsaPassphraseChoice("No Constraints (multipaz)", PassphraseConstraints.NONE, "multipaz"),
        )
    }

    val (selectedOption, onOptionSelected) = remember { mutableStateOf(walletPinRadioOptions[0]) }

    Dialog(onDismissRequest = { onDismissRequest() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Cloud Secure Area",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    text = "URL for the Cloud Secure Area",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.bodyMedium
                )

                TextField(
                    value = urlTextField,
                    onValueChange = { urlTextField = it },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    )
                )

                // Provide a dropdown so it's easy to select common CSA URLs.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    val expanded = remember { mutableStateOf(false) }
                    Button(onClick = { expanded.value = !expanded.value }) {
                        Text("Select URL")
                    }
                    DropdownMenu(
                        expanded = expanded.value,
                        onDismissRequest = { expanded.value = false }
                    ) {
                        for (urlOption in listOf(
                            "https://csa.multipaz.org/",
                            "https://csa.multipaz.org/open",
                            "http://10.0.2.2:8005",
                            "http://localhost:8005",
                        )) {
                            DropdownMenuItem(
                                text = { Text(urlOption) },
                                onClick = {
                                    urlTextField = TextFieldValue(urlOption)
                                    expanded.value = false
                                })
                        }
                    }
                }

                Text(
                    text = "Knowledge-Based Factor to use",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.bodyMedium
                )

                walletPinRadioOptions.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (entry == selectedOption),
                                onClick = { onOptionSelected(entry) },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(
                            selected = (entry == selectedOption),
                            onClick = null
                        )
                        Text(
                            text = entry.description.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onDismissRequest() },
                    ) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = { onConnectButtonClicked(
                            urlTextField.text,
                            selectedOption.passphrase,
                            selectedOption.constraints
                        ) },
                    ) {
                        Text("Connect")
                    }
                }

            }
        }
    }
}

private suspend fun csaAttestation(
    showToast: (message: String) -> Unit
): KeyAttestation? {
    if (cloudSecureArea == null) {
        showToast("First connect to the CSA")
        return null
    }

    val now = Clock.System.now()
    val thirtyDaysFromNow = now + 30.days
    cloudSecureArea!!.createKey(
        "testKey",
        CloudCreateKeySettings.Builder("Challenge".encodeToByteString())
            .setUserAuthenticationRequired(true, setOf(
                CloudUserAuthType.PASSCODE,
                CloudUserAuthType.BIOMETRIC
            ))
            .setValidityPeriod(now, thirtyDaysFromNow)
            .build()
    )
    return cloudSecureArea!!.getKeyInfo("testKey").attestation
}

private suspend fun csaTest(
    algorithm: Algorithm,
    authRequired: Boolean,
    authTypes: Set<CloudUserAuthType>,
    passphraseRequired: Boolean,
    showToast: (message: String) -> Unit
) {
    Logger.d(
        TAG,
        "cksTest keyPurpose:$algorithm authRequired:$authRequired"
    )
    try {
        csaTestUnguarded(
            algorithm, authRequired, authTypes, passphraseRequired, showToast
        )
    } catch (e: Throwable) {
        showToast("${e.message}")
    }
}

private suspend fun csaTestUnguarded(
    algorithm: Algorithm,
    authRequired: Boolean,
    authTypes: Set<CloudUserAuthType>,
    passphraseRequired: Boolean,
    showToast: (message: String) -> Unit
) {

    if (cloudSecureArea == null) {
        showToast("First connect to the CSA")
        return
    }

    val builder = CloudCreateKeySettings.Builder("Challenge".encodeToByteString())
        .setAlgorithm(algorithm)
        .setUserAuthenticationRequired(authRequired, authTypes)
    if (passphraseRequired) {
        builder.setPassphraseRequired(true)
    }
    cloudSecureArea!!.createKey("testKey", builder.build())

    if (algorithm.isSigning) {
        val t0 = Clock.System.now()
        val signature = cloudSecureArea!!.sign(
            "testKey",
            "data".encodeToByteArray()
        )
        val t1 = Clock.System.now()
        Logger.d(
            TAG,
            "Made signature with key " +
                    "r=${signature.r.toHex()} s=${signature.s.toHex()}",
        )
        showToast("EC signature in (${t1 - t0})")
    } else {
        val otherKeyPairForEcdh = Crypto.createEcPrivateKey(algorithm.curve!!)
        val t0 = Clock.System.now()
        val Zab = cloudSecureArea!!.keyAgreement(
            "testKey",
            otherKeyPairForEcdh.publicKey
        )
        val t1 = Clock.System.now()
        Logger.dHex(
            TAG,
            "Calculated ECDH",
            Zab)
        showToast("ECDH in (${t1 - t0})")
    }
}
