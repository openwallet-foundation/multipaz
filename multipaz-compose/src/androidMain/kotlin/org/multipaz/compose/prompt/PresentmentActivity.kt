package org.multipaz.compose.prompt

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.service.quickaccesswallet.QuickAccessWalletService
import android.view.Window
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.multipaz.compose.R
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.document.DocumentModel
import org.multipaz.compose.document.VerticalDocumentList
import org.multipaz.compose.prompt.PresentmentActivity.Companion.getPendingIntent
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.document.Document
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.presentment.DocumentChooserData
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.PresentmentModel
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * A generic activity for presentment on Android.
 *
 * This activity can be used together with [PresentmentModel] and [PromptModel] to drive
 * a full presentment UI and UX.
 *
 * It can also act as a document chooser intended to be used by a [QuickAccessWalletService],
 * see [getPendingIntent].
 */
class PresentmentActivity: FragmentActivity() {
    companion object {
        private const val TAG = "PresentmentActivity"

        val promptModel = AndroidPromptModel.Builder(
            uiLauncher = { dialogModel ->
                Logger.i(TAG, "Launching UI for $dialogModel")
                startActivity()
                try {
                    withTimeout(5.seconds) { dialogModel.waitUntilBound() }
                } catch (e: TimeoutCancellationException) {
                    Logger.w(TAG, "Failed to bind to PromptModel UI", e)
                }
            }
        ).apply { addCommonDialogs() }.build()

        val presentmentModel = PresentmentModel()

        private val imageLoader by lazy {
            ImageLoader.Builder(applicationContext).components {
                add(KtorNetworkFetcherFactory(HttpClient(Android.create())))
            }.build()
        }

        fun startActivity() {
            val intent = Intent(applicationContext, PresentmentActivity::class.java)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            applicationContext.startActivity(intent)
        }

        /**
         * Get a [PendingIntent] to launch [PresentmentActivity] in document chooser mode.
         *
         * In document chooser mode, the user is presented with a list of documents from [source]
         * rendered using [VerticalDocumentList]. The document indicated by [initiallySelectedDocumentId]
         * is selected and by tapping the document pile at the bottom the user can select another
         * document. The user is also presented with a "Open Wallet" button (replacing "Wallet"
         * with [Branding.Current.appName] if not `null`) which if pressed will launch
         * [openWalletAppPendingIntentFn].
         *
         * This is intended to be used in [QuickAccessWalletService.getGestureTargetActivityPendingIntent]
         * which is called whenever the user double-clicks the power button.
         *
         * @param source the source of truth of what to present.
         * @param initiallySelectedDocumentId the [Document] identifier for the document to focus
         * in the document chooser.
         * @param openWalletAppPendingIntentFn a function to return a [PendingIntent] to use
         * for when the user presses the "Open Wallet" button.
         * @param preferredServices a list of [ComponentName]s which will be preferred over other
         * services. This is used in [onResume] to pass to [CardEmulation.setPreferredService].
         */
        fun getPendingIntent(
            source: PresentmentSource,
            initiallySelectedDocumentId: String?,
            openWalletAppPendingIntentFn: (document: Document) -> PendingIntent,
            preferredServices: List<ComponentName>
        ): PendingIntent {
            presentmentModel.reset(
                source = source,
                showDocumentChooser = DocumentChooserData(
                    initiallySelectedDocumentId = initiallySelectedDocumentId,
                    openAppPendingIntentFn = openWalletAppPendingIntentFn,
                    preferredServices = preferredServices
                ),
                preselectedDocuments = emptyList()
            )

            var modelWatcherJob: Job? = null;
            modelWatcherJob = CoroutineScope(Dispatchers.IO).launch {
                presentmentModel.state.collect { state ->
                    when (state) {
                        is PresentmentModel.State.CanceledByUser -> {
                            presentmentModel.setCompleted(
                                PresentmentCanceledException(null)
                            )
                            modelWatcherJob?.cancel()
                            modelWatcherJob = null
                        }

                        is PresentmentModel.State.Completed -> {
                            modelWatcherJob?.cancel()
                            modelWatcherJob = null
                        }

                        else -> {}
                    }
                }
            }

            val launchIntent = Intent(
                /* packageContext = */ applicationContext,
                /* cls = */ PresentmentActivity::class.java
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getActivity(
                /* context = */ applicationContext,
                /* requestCode = */ 0,
                /* intent = */ launchIntent,
                /* flags = */ pendingIntentFlags
            )
            return pendingIntent
        }
    }

    override fun onResume() {
        super.onResume()
        val state = presentmentModel.state.value
        if (state is PresentmentModel.State.Reset && state.documentChooserData != null) {
            NfcAdapter.getDefaultAdapter(this)?.let { adapter ->
                val cardEmulation = CardEmulation.getInstance(adapter)
                for (componentName in state.documentChooserData!!.preferredServices) {
                    if (!cardEmulation.setPreferredService(this, componentName)) {
                        Logger.w(TAG, "CardEmulation.setPreferredService() returned false for $componentName")
                    }
                }
                if (!cardEmulation.categoryAllowsForegroundPreference(CardEmulation.CATEGORY_OTHER)) {
                    Logger.w(TAG, "CardEmulation.categoryAllowsForegroundPreference(CATEGORY_OTHER) returned false")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val state = presentmentModel.state.value
        if (state is PresentmentModel.State.Reset && state.documentChooserData != null) {
            NfcAdapter.getDefaultAdapter(this)?.let {
                val cardEmulation = CardEmulation.getInstance(it)
                if (!cardEmulation.unsetPreferredService(this)) {
                    Logger.w(TAG, "CardEmulation.unsetPreferredService() return false")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Logger.i(TAG, "in onStop(), canceling")
        presentmentModel.setCanceledByUser()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)

        window.isNavigationBarContrastEnforced = false

        setContent {
            val currentBranding = Branding.Current.collectAsState().value
            currentBranding.theme {
                PromptDialogs(
                    promptModel = promptModel,
                    imageLoader = imageLoader,
                )

                PresentmentActivityContent(
                    window = window,
                    getPresentmentModel = { presentmentModel },
                    onFadedIn = {},
                    onFinish = { switchToAppOnFinishPendingIntent ->
                        switchToAppOnFinishPendingIntent?.send()
                        finish()
                    }
                )
            }
        }
    }
}

/**
 * A full-screen composable for credential presentment.
 *
 * The [getPresentmentModel] function will be called a single time the first time the composable is shown.
 *
 * @param window the [Window] of the activity.
 * @param getPresentmentModel a function to return the [PresentmentModel] to use.
 * @param onFadedIn called when the composable has fully faded in.
 * @param onFinish called when the presentment is finished and fully faded out. The passed-in [PendingIntent]
 * is non-null if the user clicked the "Open Wallet" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresentmentActivityContent(
    window: Window,
    getPresentmentModel: suspend () -> PresentmentModel,
    onFadedIn: () -> Unit,
    onFinish: (switchToAppOnFinishPendingIntent: PendingIntent?) -> Unit
) {
    var presentmentModel by remember { mutableStateOf<PresentmentModel?>(null) }
    var documentModel by remember { mutableStateOf<DocumentModel?>(null) }
    var switchToAppOnFinishPendingIntent by remember { mutableStateOf<PendingIntent?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val currentBranding = Branding.Current.collectAsState().value

    var startFadeIn by remember { mutableStateOf(false) }
    val fadeInAlpha by animateFloatAsState(
        targetValue = if (startFadeIn) 1.0f else 0.0f,
        animationSpec = tween(
            durationMillis = 300
        ),
        finishedListener = { onFadedIn() }
    )
    var startFadeOut by remember { mutableStateOf(false) }
    val fadeOutAlpha by animateFloatAsState(
        targetValue = if (startFadeOut) 0.0f else 1.0f,
        animationSpec = tween(
            durationMillis = 300
        ),
        finishedListener = { onFinish(switchToAppOnFinishPendingIntent) }
    )
    var blurAvailable by remember { mutableStateOf(true) }

    // We do the initializations that require suspend functions here and fade in when done...
    LaunchedEffect(Unit) {
        presentmentModel = getPresentmentModel()
        documentModel = DocumentModel.create(
            documentStore = presentmentModel!!.source.documentStore,
            documentTypeRepository = presentmentModel!!.source.documentTypeRepository
        )
        startFadeIn = true
    }
    if (!startFadeIn) {
        Scaffold(
            modifier = Modifier.alpha(0f),
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {}
        }
        return
    }

    val state = presentmentModel!!.state.collectAsState().value
    val numRequestsServed = presentmentModel!!.numRequestsServed.collectAsState().value

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        window.setBackgroundBlurRadius((80.0 * fadeOutAlpha * fadeInAlpha).roundToInt())
    } else {
        blurAvailable = false
    }

    when (state) {
        is PresentmentModel.State.Reset -> {}
        is PresentmentModel.State.Connecting -> {}
        is PresentmentModel.State.WaitingForReader -> {}
        is PresentmentModel.State.WaitingForUserInput -> {}
        is PresentmentModel.State.Sending -> {}
        is PresentmentModel.State.Completed -> {
            LaunchedEffect(Unit) {
                if (!startFadeOut) {
                    delay(2.seconds)
                    startFadeOut = true
                }
            }
        }

        is PresentmentModel.State.CanceledByUser -> {}
    }

    // Blend between `background` and `primaryContainer`..
    val backgroundColor = MaterialTheme.colorScheme.background.blend(
        other = MaterialTheme.colorScheme.primaryContainer,
        ratio = 0.5f
    )
    // If blur is available, also make this slightly transparent
    val backgroundAlpha = if (blurAvailable) 0.8f else 1.0f
    Scaffold(
        modifier = Modifier.alpha(fadeOutAlpha * fadeInAlpha),
        containerColor = backgroundColor.copy(alpha = backgroundAlpha * fadeOutAlpha * fadeInAlpha)
    ) { innerPadding ->
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface
        ) {
            val docsToShow = presentmentModel!!.documentsSelected.collectAsState().value
            val selectedDocIdFromCardChooser = remember { mutableStateOf<String?>(null) }

            // Wrap in a Box to center the bounded Column on wide screens
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 600.dp) // Limits width for foldable/tablet support
                        .padding(
                            top = innerPadding.calculateTopPadding(),
                            start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
                            end = innerPadding.calculateEndPadding(LocalLayoutDirection.current)
                            // Omitting the bottom padding since we want to draw under the navigation bar
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state is PresentmentModel.State.Reset && state.documentChooserData != null) {
                        ShowCardChooser(
                            documentModel = documentModel!!,
                            initiallySelectedDocumentId = state.documentChooserData!!.initiallySelectedDocumentId,
                            selectedDocIdFromCardChooser = selectedDocIdFromCardChooser,
                            contentBelow = {
                                Column(
                                    modifier = Modifier.fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    ShowHoldToReader()
                                    Spacer(modifier = Modifier.weight(1.0f))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp, horizontal = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        OutlinedButton(
                                            modifier = Modifier.weight(1.0f),
                                            border = BorderStroke(
                                                width = 0.5.dp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            onClick = {
                                                coroutineScope.launch {
                                                    switchToAppOnFinishPendingIntent =
                                                        state.documentChooserData!!.openAppPendingIntentFn(
                                                            presentmentModel!!.source.documentStore.lookupDocument(
                                                                selectedDocIdFromCardChooser.value!!
                                                            )!!
                                                        )
                                                    startFadeOut = true
                                                }
                                            }
                                        ) {
                                            Text(
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                text = applicationContext.getString(
                                                    R.string.presentment_activity_open_wallet,
                                                    currentBranding.appName ?: R.string.presentment_activity_app_name_default
                                                ),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        val docIdToShow =
                            docsToShow.firstOrNull()?.identifier ?: selectedDocIdFromCardChooser.value
                        ShowCard(
                            documentModel = documentModel!!,
                            documentId = docIdToShow,
                            contentBelow = {
                                when (state) {
                                    is PresentmentModel.State.Reset -> {}
                                    is PresentmentModel.State.Connecting -> {
                                        ShowConnectingToReader()
                                    }
                                    is PresentmentModel.State.WaitingForReader -> {
                                        // Keep showing the NFC logo while waiting for a request...
                                        if (numRequestsServed == 0) {
                                            ShowConnectingToReader()
                                        } else {
                                            ShowWaiting()
                                        }
                                    }
                                    is PresentmentModel.State.WaitingForUserInput -> {}
                                    is PresentmentModel.State.Sending -> {
                                        ShowWaiting()
                                    }
                                    is PresentmentModel.State.Completed -> {
                                        if (state.error != null) {
                                            when (state.error!!) {
                                                is PresentmentCanceledException -> {
                                                    if (state.error!!.message != null) {
                                                        ShowFailure(applicationContext.getString(
                                                            R.string.presentment_activity_canceled
                                                        ))
                                                    } else {
                                                        startFadeOut = true
                                                    }
                                                }
                                                is PresentmentCannotSatisfyRequestException -> {
                                                    ShowFailure(applicationContext.getString(
                                                        R.string.presentment_activity_cannot_satisfy_request
                                                    ))
                                                }
                                                else -> {
                                                    ShowFailure(applicationContext.getString(
                                                        R.string.presentment_activity_something_went_wrong
                                                    ))
                                                }
                                            }
                                        } else {
                                            ShowShared()
                                        }
                                    }
                                    is PresentmentModel.State.CanceledByUser -> {}
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowCard(
    documentModel: DocumentModel,
    documentId: String?,
    contentBelow: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val maxCardHeight = configuration.screenHeightDp.dp / 3

    val documentInfo = documentModel.documentInfos.collectAsState().value.find { it.document.identifier == documentId }
    if (documentInfo != null) {
        VerticalDocumentList(
            documentModel = documentModel,
            focusedDocument = documentInfo,
            unfocusedVisiblePercent = 25,
            allowDocumentReordering = false,
            showStackWhileFocused = false,
            cardMaxHeight = maxCardHeight,
            showDocumentInfo = { documentInfo ->
                contentBelow()
            }
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            contentBelow()
        }
    }
}

@Composable
private fun ShowCardChooser(
    documentModel: DocumentModel,
    initiallySelectedDocumentId: String?,
    selectedDocIdFromCardChooser: MutableState<String?>,
    contentBelow: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val maxCardHeight = configuration.screenHeightDp.dp / 3

    var focusedDocumentId by rememberSaveable { mutableStateOf<String?>(
        initiallySelectedDocumentId ?: documentModel.documentInfos.value.firstOrNull()?.document?.identifier
    )}
    val focusedDocument = documentModel.documentInfos.collectAsState().value.find { documentInfo ->
        documentInfo.document.identifier == focusedDocumentId
    }

    LaunchedEffect(Unit) {
        selectedDocIdFromCardChooser.value = focusedDocumentId
    }

    VerticalDocumentList(
        documentModel = documentModel,
        focusedDocument = focusedDocument,
        unfocusedVisiblePercent = 25,
        allowDocumentReordering = false,
        showStackWhileFocused = true,
        cardMaxHeight = maxCardHeight,
        showDocumentInfo = { documentInfo -> contentBelow() },
        emptyDocumentContent = {
            Text(applicationContext.getString(
                R.string.presentment_activity_no_documents_available
            ))
        },
        onDocumentFocused = { documentInfo ->
            focusedDocumentId = documentInfo.document.identifier
            selectedDocIdFromCardChooser.value = documentInfo.document.identifier
        },
        onDocumentFocusedTapped = { _ ->
            focusedDocumentId = null
            selectedDocIdFromCardChooser.value = null
        },
        onDocumentFocusedStackTapped = {
            focusedDocumentId = null
            selectedDocIdFromCardChooser.value = null
        }
    )
}

@Composable
private fun ShowShared() {
    ShowLottieAnimation(
        message = applicationContext.getString(R.string.presentment_activity_info_was_shared),
        animationPath = "files/success_animation.json",
        repeat = false
    )
}

@Composable
private fun ShowFailure(message: String) {
    ShowLottieAnimation(
        message = message,
        animationPath = "files/error_animation.json",
        repeat = false
    )
}

@Composable
private fun ShowWaiting() {
    ShowLottieAnimation(
        message = null,
        animationPath = "files/waiting_animation.json",
        repeat = true
    )
}

@Composable
private fun ShowHoldToReader() {
    val isDarkTheme = isSystemInDarkTheme()
    ShowLottieAnimation(
        message = applicationContext.getString(R.string.presentment_activity_hold_to_reader),
        animationPath = if (isDarkTheme) {
            "files/nfc_animation_dark.json"
        } else {
            "files/nfc_animation.json"
        },
        repeat = true
    )
}

@Composable
private fun ShowConnectingToReader() {
    val isDarkTheme = isSystemInDarkTheme()
    ShowLottieAnimation(
        message = applicationContext.getString(R.string.presentment_activity_connecting_to_reader),
        animationPath = if (isDarkTheme) {
            "files/nfc_animation_dark.json"
        } else {
            "files/nfc_animation.json"
        },
        repeat = true
    )
}

@Composable
private fun ShowLottieAnimation(
    message: String?,
    animationPath: String,
    repeat: Boolean
) {
    val errorComposition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(Res.readBytes(animationPath).decodeToString())
    }
    val errorProgressState = animateLottieCompositionAsState(
        composition = errorComposition,
        iterations = if (repeat) Compottie.IterateForever else 1
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = rememberLottiePainter(
                composition = errorComposition,
                progress = { errorProgressState.value },
            ),
            contentDescription = null,
            modifier = Modifier.size(100.dp)
        )

        if (message != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun Color.blend(other: Color, ratio: Float): Color {
    return Color(
        red = this.red * (1 - ratio) + other.red * ratio,
        green = this.green * (1 - ratio) + other.green * ratio,
        blue = this.blue * (1 - ratio) + other.blue * ratio,
        alpha = this.alpha * (1 - ratio) + other.alpha * ratio
    )
}