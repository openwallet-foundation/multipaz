package org.multipaz.compose.provisioning

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.PassphraseEntryField
import org.multipaz.compose.decodeImage
import org.multipaz.document.Document
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.issuer_loading
import org.multipaz.multipaz_compose.generated.resources.issuer_loading_failed
import org.multipaz.multipaz_compose.generated.resources.provisioning_authorization_failed
import org.multipaz.multipaz_compose.generated.resources.provisioning_authorized
import org.multipaz.multipaz_compose.generated.resources.provisioning_browser
import org.multipaz.multipaz_compose.generated.resources.provisioning_cancel_button
import org.multipaz.multipaz_compose.generated.resources.provisioning_connected
import org.multipaz.multipaz_compose.generated.resources.provisioning_credentials_issued
import org.multipaz.multipaz_compose.generated.resources.provisioning_error
import org.multipaz.multipaz_compose.generated.resources.provisioning_initial
import org.multipaz.multipaz_compose.generated.resources.provisioning_processing_authorization
import org.multipaz.multipaz_compose.generated.resources.provisioning_requestion_credentials
import org.multipaz.multipaz_compose.generated.resources.provisioning_retry
import org.multipaz.multipaz_compose.generated.resources.provisioning_select_credential
import org.multipaz.multipaz_compose.generated.resources.provisioning_title
import org.multipaz.multipaz_compose.generated.resources.provisioning_tx_code_fallback_prompt
import org.multipaz.multipaz_compose.generated.resources.provisioning_tx_code_fallback_prompt_numeric
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationException
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.provisioning.openid4vci.OpenID4VCILocalBackend
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.util.Logger
import kotlin.time.Duration.Companion.seconds

/**
 * Bottom sheet that interacts with the user and drives credential provisioning in the given
 * [ProvisioningModel], only visible when model state is not [ProvisioningModel.Idle].
 *
 * If the bottom sheet is dismissed, the provisioning session is canceled.
 *
 * When OpenId-style user authorization is launched, user interacts with the browser, at
 * the end of this interaction, browser is navigated to a redirect URL that the app should
 * intercept. Meanwhile [waitForRedirectLinkInvocation] is invoked (asynchronously), it should
 * return the redirect URL once it was navigated to. Although an exotic possibility (and not
 * supported using [ProvisioningBottomSheet] as UI), multiple
 * authorization sessions can run in parallel (each with its own model). Each authorization
 * session is assigned a unique state value (passed as url parameter on the redirect url). It is
 * important that url with the correct state parameter value is returned by
 * [waitForRedirectLinkInvocation]. This is important in all cases to avoid contaminating
 * an active authorization session with stale URLs (e.g. from a browser tab); this function
 * should return null if navigation is not likely to happen and the session should be
 * treated as abandoned.
 *
 * @param modifier Compose [Modifier] for this UI control
 * @param provisioningModel model that manages credential provisioning
 * @param waitForRedirectLinkInvocation wait for redirect url with the given state parameter
 *  being navigated to in the browser.
 * @param clientPreferences preferences for OpenID4VCI protocol
 * @param backend OpenID4VCI protocol support object
 * @param onFinishedProvisioning called when the document is provisioned (or it fails provisioning)
 * @param issuerUrl if given, starts provisioning from the metadata exposed by the given issuer,
 *  credential offer is not needed in this case
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisioningBottomSheet(
    modifier: Modifier = Modifier,
    provisioningModel: ProvisioningModel,
    waitForRedirectLinkInvocation: suspend (state: String) -> String?,
    clientPreferences: Deferred<OpenID4VCIClientPreferences>,
    backend: Deferred<OpenID4VCIBackend>,
    onFinishedProvisioning: (document: Document?, isNewlyIssued: Boolean) -> Unit = { _, _ -> },
    issuerUrl: String? = null,
) {
    val provisioningState = provisioningModel.state.collectAsState().value
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (provisioningState !== ProvisioningModel.Idle || issuerUrl != null) {
        ModalBottomSheet(
            onDismissRequest = {
                onFinishedProvisioning.invoke(null, false)
                provisioningModel.cancel()
            },
            sheetState = sheetState,
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surfaceBright,
        ) {
            val issuerMetadata = remember { mutableStateOf<ProvisioningMetadata?>(null) }
            val metadata = provisioningModel.metadata.collectAsState().value
            ProvisioningHeader(
                provisioningMetadata = metadata,
                issuerMetadata = issuerMetadata.value,
                onClose = {
                    onFinishedProvisioning.invoke(null, false)
                    provisioningModel.cancel()
                }
            )
            ProvisioningBottomSheetBody(
                provisioningModel = provisioningModel,
                provisioningMetadata = metadata,
                provisioningState = provisioningState,
                waitForRedirectLinkInvocation = waitForRedirectLinkInvocation,
                onFinishedProvisioning = onFinishedProvisioning,
                sheetState = sheetState,
                issuerUrl = issuerUrl,
                issuerMetadata = issuerMetadata,
                clientPreferences = clientPreferences,
                backend = backend,
            )
        }
    }
}

@Composable
private fun ProvisioningHeader(
    onClose: () -> Unit,
    provisioningMetadata: ProvisioningMetadata?,
    issuerMetadata: ProvisioningMetadata?
) {
    val issuerDisplay = (provisioningMetadata ?: issuerMetadata)?.display
    Row(
        modifier = Modifier
            .padding(24.dp, 8.dp, 24.dp, 0.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (issuerDisplay != null) {
            RenderImage(
                modifier = Modifier.height(32.dp),
                bytes = issuerDisplay.logo
            )
            Text(
                modifier = Modifier.padding(8.dp),
                text = issuerDisplay.text,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
            )
        } else {
            Text(
                modifier = Modifier.padding(24.dp, 8.dp),
                text = stringResource(Res.string.provisioning_title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        IconButton(
            modifier = Modifier.size(32.dp),
            onClick = onClose
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(Res.string.provisioning_cancel_button),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvisioningBottomSheetBody(
    provisioningModel: ProvisioningModel,
    provisioningMetadata: ProvisioningMetadata?,
    provisioningState: ProvisioningModel.State,
    waitForRedirectLinkInvocation: suspend (state: String) -> String?,
    onFinishedProvisioning: (document: Document?, isNewlyIssued: Boolean) -> Unit,
    sheetState: SheetState,
    issuerUrl: String?,
    issuerMetadata: MutableState<ProvisioningMetadata?>,
    clientPreferences: Deferred<OpenID4VCIClientPreferences>,
    backend: Deferred<OpenID4VCIBackend>,
) {
    val clientPreferencesHolder = remember { mutableStateOf<OpenID4VCIClientPreferences?>(null) }
    val backendHolder = remember { mutableStateOf<OpenID4VCIBackend?>(null) }
    val issuerError = remember { mutableStateOf<String?>(null) }
    val preferences = clientPreferencesHolder.value
    val be = backendHolder.value

    LaunchedEffect(clientPreferences) {
        try {
            clientPreferencesHolder.value = clientPreferences.await()
        } catch (err: CancellationException) {
            issuerError.value = "Cancelled"  // for completeness, should never show up in UI
            throw err
        } catch (err: Exception) {
            issuerError.value = err.message
            Logger.e(TAG, "Could not load OpenID4VCI client preferences", err)
        }
    }
    LaunchedEffect(backend) {
        try {
            backendHolder.value = backend.await()
        } catch (err: CancellationException) {
            issuerError.value = "Cancelled"  // for completeness, should never show up in UI
            throw err
        } catch (err: Exception) {
            issuerError.value = err.message
            Logger.e(TAG, "Could not load OpenID4VCI backend", err)
        }
    }

    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(issuerUrl, clientPreferences) {
        issuerMetadata.value = try {
            issuerUrl?.let {
                provisioningModel.getOpenID4VCIIssuerMetadata(
                    issuerUrl = issuerUrl,
                    clientPreferences = clientPreferences.await()
                )
            }
        } catch (err: CancellationException) {
            issuerError.value = "Cancelled"  // for completeness, should never show up in UI
            throw err
        } catch (err: Exception) {
            issuerError.value = err.message
            Logger.e(TAG, "Could not load metadata", err)
            null
        }?.also {
            coroutineScope.launch {
                delay(50)
                // this can get cancelled, we do not want to be affected by it
                sheetState.expand()
            }
        }
    }
    val errorText = issuerError.value
    if (errorText != null) {
        Text(
            modifier = Modifier
                .padding(16.dp, 4.dp),
            text = stringResource(Res.string.issuer_loading_failed)
        )
        if (errorText.isNotEmpty()) {
            Text(
                modifier = Modifier.padding(16.dp, 4.dp),
                text = errorText
            )
        }
    } else if (preferences == null || be == null || (
                provisioningState == ProvisioningModel.Idle
                    && issuerMetadata.value == null)) {
        Text(
            modifier = Modifier
                .padding(16.dp, 4.dp),
            text = stringResource(Res.string.issuer_loading)
        )
    } else {
        ProvisioningBottomSheetContent(
            provisioningModel = provisioningModel,
            provisioningMetadata = provisioningMetadata,
            provisioningState = provisioningState,
            waitForRedirectLinkInvocation = waitForRedirectLinkInvocation,
            onFinishedProvisioning = onFinishedProvisioning,
            issuerUrl = issuerUrl,
            issuerMetadata = issuerMetadata.value,
            clientPreferences = preferences,
            backend = be,
       )
    }
}

@Composable
private fun ProvisioningBottomSheetContent(
    provisioningModel: ProvisioningModel,
    provisioningMetadata: ProvisioningMetadata?,
    provisioningState: ProvisioningModel.State,
    waitForRedirectLinkInvocation: suspend (state: String) -> String?,
    onFinishedProvisioning: (document: Document?, isNewlyIssued: Boolean) -> Unit,
    issuerUrl: String?,
    issuerMetadata: ProvisioningMetadata?,
    clientPreferences: OpenID4VCIClientPreferences?,
    backend: OpenID4VCIBackend?,
) {
    val coroutineScope = rememberCoroutineScope()
    val credentialIdState = remember { mutableStateOf<String?>(null) }
    val credentialMap = if (provisioningState == ProvisioningModel.Idle) {
        // Selecting the credential
        Text(
            modifier = Modifier
                .padding(16.dp, 4.dp),
            text = stringResource(Res.string.provisioning_select_credential)
        )
        issuerMetadata!!.credentials
    } else {
        // Just displaying the selected credential (or the credential from the credential offer)
        provisioningMetadata?.credentials
            ?: issuerMetadata?.let { metadata ->
                credentialIdState.value?.let { credentialId ->
                    mapOf(credentialId to metadata.credentials[credentialId]!!)
                }
            }
    }

    if (credentialMap != null) {
        val scrollState = rememberScrollState()
        DisplayCredentialMetadata(
            modifier = Modifier
                .padding(16.dp, 8.dp)
                .fillMaxWidth()
                .let {
                    if (provisioningState == ProvisioningModel.Idle) {
                        it
                            .heightIn(max = 192.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(scrollState)
                    } else {
                        it
                    }
                },
            credentialMap = credentialMap,
            onCredentialSelected = if (provisioningState == ProvisioningModel.Idle) {
                // Selecting a credential
                { credentialId ->
                    credentialIdState.value = credentialId
                    coroutineScope.launch {
                        provisioningModel.launchOpenID4VCIProvisioning(
                            issuerUrl = issuerUrl!!,
                            credentialId = credentialId,
                            clientPreferences = clientPreferences!!,
                            backend = backend!!
                        )
                    }
                }
            } else {
                // Just displaying selected credential
                null
            }
        )
    }

    when (provisioningState) {
        ProvisioningModel.Idle -> {}
        is ProvisioningModel.Authorizing -> {
            Authorize(
                provisioningModel = provisioningModel,
                clientPreferences = clientPreferences,
                waitForRedirectLinkInvocation = waitForRedirectLinkInvocation,
                onFinishedProvisioning = onFinishedProvisioning,
                challenges = provisioningState.authorizationChallenges
            )
        }

        is ProvisioningModel.Error -> {
            if (provisioningState.err is AuthorizationException) {
                Text(
                    modifier = Modifier
                        .padding(16.dp, 4.dp),
                    text = stringResource(Res.string.provisioning_authorization_failed)
                )
                val err = provisioningState.err as AuthorizationException
                Text(
                    modifier = Modifier.padding(16.dp, 4.dp),
                    text = stringResource(
                        Res.string.provisioning_error,
                        err.code
                    )
                )
                err.description?.let {
                    Text(
                        modifier = Modifier.padding(16.dp, 4.dp),
                        text = it
                    )
                }
            } else {
                Text(
                    modifier = Modifier
                        .padding(16.dp, 4.dp),
                    text = stringResource(
                        Res.string.provisioning_error,
                        provisioningState.err.message ?: "unknown"
                    )
                )
            }
            LaunchedEffect(true) {
                delay(3.seconds)
                provisioningModel.cancel()  // resets to Idle
                onFinishedProvisioning(null, false)
            }
        }

        else -> {
            val text = when (provisioningState) {
                ProvisioningModel.Idle -> throw IllegalStateException()
                ProvisioningModel.Initial -> stringResource(Res.string.provisioning_initial)
                ProvisioningModel.Connected -> stringResource(Res.string.provisioning_connected)
                ProvisioningModel.ProcessingAuthorization -> stringResource(Res.string.provisioning_processing_authorization)
                ProvisioningModel.Authorized -> stringResource(Res.string.provisioning_authorized)
                ProvisioningModel.RequestingCredentials -> stringResource(Res.string.provisioning_requestion_credentials)
                is ProvisioningModel.CredentialsIssued -> {
                    stringResource(Res.string.provisioning_credentials_issued, provisioningState.numCredentialsFetched)
                }
                is ProvisioningModel.Error -> throw IllegalStateException()
                is ProvisioningModel.Authorizing -> throw IllegalStateException()
            }
            Text(
                modifier = Modifier
                    .padding(16.dp, 4.dp),
                text = text
            )
            if (provisioningState is ProvisioningModel.CredentialsIssued) {
                LaunchedEffect(true) {
                    onFinishedProvisioning(
                        provisioningState.document,
                        provisioningState.isNewlyIssued
                    )
                    delay(3.seconds)
                    provisioningModel.cancel()  // resets to Idle
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun Authorize(
    provisioningModel: ProvisioningModel,
    clientPreferences: OpenID4VCIClientPreferences?,
    waitForRedirectLinkInvocation: suspend (state: String) -> String?,
    onFinishedProvisioning: (document: Document?, isNewlyIssued: Boolean) -> Unit,
    challenges: List<AuthorizationChallenge>
) {
    when (val challenge = challenges.first()) {
        is AuthorizationChallenge.OAuth ->
            EvidenceRequestWebView(
                provisioningModel = provisioningModel,
                clientPreferences = clientPreferences!!,
                waitForRedirectLinkInvocation = waitForRedirectLinkInvocation,
                onFinishedProvisioning = onFinishedProvisioning,
                evidenceRequest = challenge
            )
        is AuthorizationChallenge.SecretText ->
            EvidenceRequestSecretText(
                provisioningModel = provisioningModel,
                challenge = challenge
            )
    }
}

@Composable
private fun EvidenceRequestWebView(
    provisioningModel: ProvisioningModel,
    clientPreferences: OpenID4VCIClientPreferences,
    waitForRedirectLinkInvocation: suspend (state: String) -> String?,
    onFinishedProvisioning: (document: Document?, isNewlyIssued: Boolean) -> Unit,
    evidenceRequest: AuthorizationChallenge.OAuth
) {
    EvidenceRequestOAuthBrowser(
        url = evidenceRequest.url,
        redirectUrl = clientPreferences.redirectUrl,
        waitForRedirect = { waitForRedirectLinkInvocation(evidenceRequest.state) },
        onRedirectReceived = { invokedUrl ->
            if (invokedUrl != null) {
                provisioningModel.provideAuthorizationResponse(
                    response = AuthorizationResponse.OAuth(
                        id = evidenceRequest.id,
                        parameterizedRedirectUrl = invokedUrl
                    )
                )
            } else {
                onFinishedProvisioning.invoke(null, false)
                provisioningModel.cancel()
            }
        }
    )
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(Res.string.provisioning_browser),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp, 8.dp)
            )
        }
    }
}

@Composable
private fun EvidenceRequestSecretText(
    provisioningModel: ProvisioningModel,
    challenge: AuthorizationChallenge.SecretText
) {
    val coroutineScope = rememberCoroutineScope()
    val passphraseRequest = challenge.request
    val constraints = PassphraseConstraints(
        minLength = passphraseRequest.length ?: 1,
        maxLength = passphraseRequest.length ?: 10,
        passphraseRequest.isNumeric
    )
    val fallback = if (passphraseRequest.isNumeric) {
        stringResource(Res.string.provisioning_tx_code_fallback_prompt_numeric)
    } else {
        stringResource(Res.string.provisioning_tx_code_fallback_prompt)
    }
    Column {
        Text(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(16.dp, 8.dp),
            text = passphraseRequest.description ?: fallback
        )
        if (challenge.retry) {
            Text(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(0.dp, 8.dp),
                text = stringResource(Res.string.provisioning_retry)
            )
        }
        PassphraseEntryField(
            constraints = constraints,
            checkWeakPassphrase = false
        ) { passphrase, meetsRequirements, donePressed ->
            if (meetsRequirements && donePressed) {
                coroutineScope.launch {
                    provisioningModel.provideAuthorizationResponse(
                        AuthorizationResponse.SecretText(
                            id = challenge.id,
                            secret = passphrase
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun DisplayCredentialMetadata(
    credentialMap: Map<String, CredentialMetadata>,
    modifier: Modifier = Modifier,
    onCredentialSelected: ((credentialId: String) -> Unit)?,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((id, credential) in credentialMap) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                RenderImage(
                    modifier = Modifier.height(32.dp).padding(4.dp),
                    bytes = credential.display.logo
                )
                val format = when (credential.format) {
                    is CredentialFormat.Mdoc -> "mDoc"
                    is CredentialFormat.SdJwt -> "SD-JWT"
                }
                Text(
                    modifier = Modifier
                        .padding(4.dp)
                        .let {
                            if (onCredentialSelected == null) {
                                it
                            } else {
                                it.clickable {
                                    onCredentialSelected.invoke(id)
                                }
                            }
                        },
                    text = "${credential.display.text} ($format)",
                )
            }
        }
    }
}

@Composable
private fun RenderImage(
    bytes: ByteString?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val image = remember { mutableStateOf<ImageBitmap?>(null)}
    LaunchedEffect(bytes) {
        image.value = bytes?.let { decodeImage(it.toByteArray()) }
    }
    image.value?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier
        )
    }
}

private const val TAG = "ProvisioningBottomSheet"
