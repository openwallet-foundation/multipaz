package org.multipaz.compose.presentment

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import org.multipaz.presentment.model.PresentmentCanceled
import org.multipaz.presentment.model.PresentmentModel
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.presentment.model.PresentmentTimeout
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.prompt.PromptModel
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.presentment_canceled
import org.multipaz.multipaz_compose.generated.resources.presentment_connecting_to_reader
import org.multipaz.multipaz_compose.generated.resources.presentment_error
import org.multipaz.multipaz_compose.generated.resources.presentment_icon_error
import org.multipaz.multipaz_compose.generated.resources.presentment_icon_success
import org.multipaz.multipaz_compose.generated.resources.presentment_success
import org.multipaz.multipaz_compose.generated.resources.presentment_timeout
import org.multipaz.multipaz_compose.generated.resources.presentment_waiting_for_request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

private const val TAG = "Presentment"

/**
 * A composable used for credential presentment.
 *
 * Applications should embed this composable wherever credential presentment is required. It communicates with the
 * verifier using [PresentmentMechanism] and [PresentmentModel] and gets application-specific data sources and
 * policy using [PresentmentSource].
 *
 * @param modifier a [Modifier].
 * @param appName the name of the application.
 * @param appIconPainter the icon for the application.
 * @param presentmentModel the [PresentmentModel] to use which must have a [PromptModel] associated with it.
 * @param presentmentSource an object for application to provide data and policy.
 * @param documentTypeRepository a [DocumentTypeRepository] used to find metadata about documents being requested.
 * @param onPresentmentComplete called when the presentment is complete.
 * @param imageLoader an [ImageLoader] for loading images from the network.
 * @param onlyShowConsentPrompt if `true` only the consent prompt will be shown, never any other graphics. This is
 *   useful if using a translucent activity.
 * @param showCancelAsBack if `true` the cancel button will say "Back" instead of "Cancel".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
@Composable
fun Presentment(
    modifier: Modifier = Modifier,
    appName: String,
    appIconPainter: Painter,
    presentmentModel: PresentmentModel,
    presentmentSource: PresentmentSource,
    documentTypeRepository: DocumentTypeRepository,
    imageLoader: ImageLoader,
    onPresentmentComplete: () -> Unit,
    onlyShowConsentPrompt: Boolean = false,
    showCancelAsBack: Boolean = false,
) {
    val coroutineScope = rememberCoroutineScope { presentmentModel.promptModel!! }

    // Make sure we clean up the PresentmentModel when we're done. This is to ensure
    // the mechanism is properly shut down, for example for proximity we need to release
    // all BLE and NFC resources.
    //
    DisposableEffect(presentmentModel) {
        onDispose {
            presentmentModel.reset()
        }
    }

    val state = presentmentModel.state.collectAsState().value
    when (state) {
        PresentmentModel.State.IDLE -> {}
        PresentmentModel.State.CONNECTING -> {}
        PresentmentModel.State.WAITING_FOR_SOURCE -> {
            presentmentModel.setSource(presentmentSource)
        }
        PresentmentModel.State.PROCESSING -> {}
        PresentmentModel.State.WAITING_FOR_CONSENT -> {
            ConsentPrompt(
                coroutineScope = coroutineScope,
                presentmentModel = presentmentModel,
                appName = appName,
                appIconPainter = appIconPainter,
                imageLoader = imageLoader,
                showCancelAsBack = showCancelAsBack
            )
        }
        PresentmentModel.State.COMPLETED -> {
            if (onlyShowConsentPrompt) {
                onPresentmentComplete()
            } else {
                // Delay for a short amount of time so the user has a chance to see the success/error indication
                LaunchedEffect(Unit) {
                    delay(1.5.seconds)
                    onPresentmentComplete()
                }
            }
        }
    }

    if (!onlyShowConsentPrompt) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(0.15f))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val (appNameText, iconPainter, iconCaptionText) = when (state) {
                    PresentmentModel.State.IDLE,
                    PresentmentModel.State.CONNECTING -> {
                        Triple(
                            appName, appIconPainter,
                            stringResource(Res.string.presentment_connecting_to_reader)
                        )
                    }

                    PresentmentModel.State.WAITING_FOR_SOURCE,
                    PresentmentModel.State.WAITING_FOR_CONSENT,
                    PresentmentModel.State.PROCESSING -> {
                        Triple(
                            appName, appIconPainter,
                            if (presentmentModel.numRequestsServed.collectAsState().value == 0) {
                                ""
                            } else {
                                stringResource(Res.string.presentment_waiting_for_request)
                            }
                        )
                    }

                    PresentmentModel.State.COMPLETED -> {
                        if (presentmentModel.error == null) {
                            Triple(
                                "", painterResource(Res.drawable.presentment_icon_success),
                                stringResource(Res.string.presentment_success)
                            )
                        } else {
                            if (presentmentModel.error is PresentmentCanceled) {
                                Triple(
                                    appName, appIconPainter,
                                    stringResource(Res.string.presentment_canceled)
                                )
                            } else if (presentmentModel.error is PresentmentTimeout) {
                                Triple(
                                    "", painterResource(Res.drawable.presentment_icon_error),
                                    stringResource(Res.string.presentment_timeout)
                                )
                            } else {
                                Triple(
                                    "", painterResource(Res.drawable.presentment_icon_error),
                                    stringResource(Res.string.presentment_error)
                                )
                            }
                        }
                    }
                }
                Text(
                    text = appNameText,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Image(
                    modifier = Modifier.size(200.dp).fillMaxSize().padding(10.dp),
                    painter = iconPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                )
                Text(
                    text = iconCaptionText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal
                )
            }
            Spacer(modifier = Modifier.weight(1.0f))
        }

        // We show a X in the top-right to resemble a close button, under two circumstances
        //
        // - when connecting the remote reader, because the underlying connection via NFC / BLE
        //   could hang and/or take a long time. This gives the user an opportunity to stop the
        //   transaction. Only applicable for proximity.
        //
        // - in the case where the connection is kept alive and we're waiting for a second request from
        //   the reader. This also only applies to proximity and in this case we have a bit of
        //   hidden developer functionality insofar that if long-pressing we'll use session-specific
        //   termination (according to 18013-5) and if double-clicking we'll close the connection without
        //   sending a termination message at all. This is useful for testing and at interoperability events
        //   and since it's hidden it doesn't materially affect a production app.
        //
        if (presentmentModel.dismissable.collectAsState().value && state != PresentmentModel.State.COMPLETED) {
            // TODO: for phones with display cutouts in the top-right (for example Pixel 9 Pro Fold when unfolded)
            //   the Close icon may be obscured. Examine the displayCutouts path and move the icon so it doesn't
            //   overlap.
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd).padding(20.dp)
                        .combinedClickable(
                            onClick = { presentmentModel.dismiss(PresentmentModel.DismissType.CLICK) },
                            onLongClick = { presentmentModel.dismiss(PresentmentModel.DismissType.LONG_CLICK) },
                            onDoubleClick = { presentmentModel.dismiss(PresentmentModel.DismissType.DOUBLE_CLICK) },
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
private fun ConsentPrompt(
    coroutineScope: CoroutineScope,
    presentmentModel: PresentmentModel,
    appName: String?,
    appIconPainter: Painter?,
    imageLoader: ImageLoader,
    showCancelAsBack: Boolean
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    ConsentModalBottomSheet(
        sheetState = sheetState,
        requester = presentmentModel.consentData.requester,
        trustPoint = presentmentModel.consentData.trustPoint,
        credentialPresentmentData = presentmentModel.consentData.credentialPresentmentData,
        preselectedDocuments = presentmentModel.consentData.preselectedDocuments,
        imageLoader = imageLoader,
        dynamicMetadataResolver = presentmentModel.consentData.dynamicMetadataResolver,
        appName = appName,
        appIconPainter = appIconPainter,
        onConfirm = { selection ->
            coroutineScope.launch {
                sheetState.hide()
            }
            presentmentModel.consentObtained(selection)
        },
        onCancel = {
            coroutineScope.launch {
                sheetState.hide()
            }
            presentmentModel.consentObtained(null)
        },
        showCancelAsBack = showCancelAsBack
    )
}
