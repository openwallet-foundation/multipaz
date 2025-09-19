package org.multipaz.compose.provisioning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.PassphraseEntryField
import org.multipaz.models.provisioning.ProvisioningModel
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.provisioning_authorization_failed
import org.multipaz.multipaz_compose.generated.resources.provisioning_authorized
import org.multipaz.multipaz_compose.generated.resources.provisioning_browser
import org.multipaz.multipaz_compose.generated.resources.provisioning_connected
import org.multipaz.multipaz_compose.generated.resources.provisioning_credentials_issued
import org.multipaz.multipaz_compose.generated.resources.provisioning_error
import org.multipaz.multipaz_compose.generated.resources.provisioning_idle
import org.multipaz.multipaz_compose.generated.resources.provisioning_initial
import org.multipaz.multipaz_compose.generated.resources.provisioning_processing_authorization
import org.multipaz.multipaz_compose.generated.resources.provisioning_requestion_credentials
import org.multipaz.multipaz_compose.generated.resources.provisioning_retry
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationException
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.securearea.PassphraseConstraints

/**
 * UI Panel (implemented as Compose [Column]) that interacts with the user and
 * drives credential provisioning in the given [ProvisioningModel].
 *
 * When OpenId-style user authorization is launched, user interacts with the browser, at
 * the end of this interaction, browser is navigated to a redirect URL that the app should
 * intercept. Meanwhile [waitForRedirectLinkInvocation] is invoked (asynchronously), it should
 * return the redirect URL once it was navigated to. Although an exotic possibility, multiple
 * authorization sessions can run in parallel (each with its own model). Each authorization
 * session is assigned a unique state value (passed as url parameter on the redirect url). It is
 * important that url with the correct state parameter value is returned by
 * [waitForRedirectLinkInvocation]. This is important in all cases to avoid contaminating
 * an active authorization session with stale URLs (e.g. from a browser tab)
 *
 * @param modifier Compose [Modifier] for this UI control
 * @param provisioningModel model that manages credential provisioning
 * @param waitForRedirectLinkInvocation wait for redirect url with the given state parameter
 *     being navigated to in the browser.
 */
@Composable
fun Provisioning(
    modifier: Modifier = Modifier,
    provisioningModel: ProvisioningModel,
    waitForRedirectLinkInvocation: suspend (state: String) -> String
) {
    val provisioningState = provisioningModel.state.collectAsState().value
    Column(modifier = modifier) {
        when (provisioningState) {
            is ProvisioningModel.Authorizing -> {
                Authorize(
                    provisioningModel = provisioningModel,
                    waitForRedirectLinkInvocation = waitForRedirectLinkInvocation,
                    challenges = provisioningState.authorizationChallenges
                )
            }

            is ProvisioningModel.Error -> if (provisioningState.err is AuthorizationException) {
                Text(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    text = stringResource(Res.string.provisioning_authorization_failed)
                )
                val err = provisioningState.err as AuthorizationException
                Text(
                    modifier = Modifier.padding(4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    text = stringResource(
                        Res.string.provisioning_error,
                        err.code
                    )
                )
                err.description?.let {
                    Text(
                        modifier = Modifier.padding(4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        text = it
                    )
                }
            } else {
                Text(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    text = stringResource(
                        Res.string.provisioning_error,
                        provisioningState.err.message ?: "unknown"
                    )
                )
            }

            else -> {
                val text = stringResource(when (provisioningState) {
                    ProvisioningModel.Idle -> Res.string.provisioning_idle
                    ProvisioningModel.Initial -> Res.string.provisioning_initial
                    ProvisioningModel.Connected -> Res.string.provisioning_connected
                    ProvisioningModel.ProcessingAuthorization -> Res.string.provisioning_processing_authorization
                    ProvisioningModel.Authorized -> Res.string.provisioning_authorized
                    ProvisioningModel.RequestingCredentials -> Res.string.provisioning_requestion_credentials
                    ProvisioningModel.CredentialsIssued -> Res.string.provisioning_credentials_issued
                    is ProvisioningModel.Error -> throw IllegalStateException()
                    is ProvisioningModel.Authorizing -> throw IllegalStateException()
                })
                Text(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    text = text
                )
            }
        }
    }
}

@Composable
private fun Authorize(
    provisioningModel: ProvisioningModel,
    waitForRedirectLinkInvocation: suspend (state: String) -> String,
    challenges: List<AuthorizationChallenge>
) {
    when (val challenge = challenges.first()) {
        is AuthorizationChallenge.OAuth ->
            EvidenceRequestWebView(
                provisioningModel = provisioningModel,
                waitForRedirectLinkInvocation = waitForRedirectLinkInvocation,
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
    waitForRedirectLinkInvocation: suspend (state: String) -> String,
    evidenceRequest: AuthorizationChallenge.OAuth
) {
    // NB: these scopes will be cancelled when navigating outside of this screen.
    LaunchedEffect(evidenceRequest.url) {
        val invokedUrl = waitForRedirectLinkInvocation(evidenceRequest.state)
        provisioningModel.provideAuthorizationResponse(
            AuthorizationResponse.OAuth(evidenceRequest.id, invokedUrl)
        )
    }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(evidenceRequest.url) {
        // Launch the browser
        // TODO: use Chrome Custom Tabs instead?
        uriHandler.openUri(evidenceRequest.url)
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(Res.string.provisioning_browser),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodyLarge
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
    Column {
        Text(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(8.dp),
            style = MaterialTheme.typography.titleLarge,
            text = passphraseRequest.description
        )
        if (challenge.retry) {
            Text(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(8.dp),
                style = MaterialTheme.typography.titleLarge,
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
