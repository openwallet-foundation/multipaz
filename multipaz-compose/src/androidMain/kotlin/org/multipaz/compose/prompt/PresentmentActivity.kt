package org.multipaz.compose.prompt

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.document.DocumentModel
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.presentment.model.PresentmentCanceled
import org.multipaz.presentment.model.PresentmentModel
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.util.Logger
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * A generic activity for presentment on Android.
 *
 * This activity can be used together with [PresentmentModel] and [PromptModel] to drive
 * a full presentment UI and UX.
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
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars()) // Hides the bar
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE // Allows swipe to reveal

        setContent {
            val currentBranding = Branding.Current.collectAsState().value
            currentBranding.theme {
                PresentmentActivityContent(
                    window = window,
                    imageLoader = imageLoader,
                    promptModel = promptModel,
                    presentmentModel = presentmentModel,
                    onFinish = { finish() }
                )
            }
        }
    }
}

@Composable
internal fun PresentmentActivityContent(
    window: Window,
    imageLoader: ImageLoader,
    promptModel: PromptModel,
    presentmentModel: PresentmentModel,
    onFinish: () -> Unit
) {
    val documentModel = remember {
        DocumentModel(
            documentStore = presentmentModel.documentStore,
            documentTypeRepository = presentmentModel.documentTypeRepository
        )
    }
    val state = presentmentModel.state.collectAsState().value
    val numRequestsServed = presentmentModel.numRequestsServed.collectAsState().value
    val documentInfos = documentModel.documentInfos.collectAsState().value
    var startFadeIn by remember { mutableStateOf(false) }
    val fadeInAlpha by animateFloatAsState(
        targetValue = if (startFadeIn) 1.0f else 0.0f,
        animationSpec = tween(
            durationMillis = 500
        )
    )
    var startFadeOut by remember { mutableStateOf(false) }
    val fadeOutAlpha by animateFloatAsState(
        targetValue = if (startFadeOut) 0.0f else 1.0f,
        animationSpec = tween(
            durationMillis = 500
        ),
        finishedListener = { onFinish() }
    )
    var blurAvailable by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        startFadeIn = true
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        window.setBackgroundBlurRadius((80.0*fadeOutAlpha*fadeInAlpha).roundToInt())
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
                delay(2.seconds)
                startFadeOut = true
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
        modifier = Modifier.alpha(fadeOutAlpha*fadeInAlpha),
        containerColor = backgroundColor.copy(alpha = backgroundAlpha*fadeOutAlpha*fadeInAlpha)
    ) { innerPadding ->
        val docsToShow = presentmentModel.documentsSelected.collectAsState().value

        // Leave enough room for the card at the top..
        val numCards = max(docsToShow.size, 1)
        val cardArtHeightDp = (LocalConfiguration.current.screenWidthDp - (numCards + 1)*8)/numCards/1.586
        val insetsPadding = innerPadding.calculateTopPadding().value
        val maxHeight = (LocalConfiguration.current.screenHeightDp - cardArtHeightDp - 8*2 - insetsPadding - 16).dp
        PromptDialogs(
            promptModel = promptModel,
            imageLoader = imageLoader,
            maxHeight = maxHeight
        )

        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(0.1f))
                when (state) {
                    is PresentmentModel.State.Reset -> {}
                    is PresentmentModel.State.Connecting -> { ShowConnectingToReader() }
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
                            if (state.error!! !is PresentmentCanceled) {
                                ShowFailure("Something went wrong")
                            } else {
                                ShowFailure("The request was cancelled")
                            }
                        } else {
                            ShowShared()
                        }
                    }
                    is PresentmentModel.State.CanceledByUser -> {}
                }
                Spacer(modifier = Modifier.weight(0.9f))
            }

            val promptsShowing = promptModel.numPromptsShowing.collectAsState().value > 0
            val cardPosition by animateFloatAsState(
                targetValue = if (promptsShowing) 0.001f else 0.5f
            )
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(cardPosition))

                if (docsToShow.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .height(cardArtHeightDp.dp)
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (docToShow in docsToShow) {
                            val documentInfo = documentInfos.find { it.document.identifier == docToShow.identifier }
                            if (documentInfo != null) {
                                Image(
                                    modifier = Modifier.weight(1.0f).fillMaxHeight(),
                                    bitmap = documentInfo.cardArt,
                                    contentDescription = null,
                                    contentScale = ContentScale.FillHeight
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1.0f - cardPosition))
            }

            // Unless a prompt is showing or we're done, offer the user a way to cancel the whole operation
            if (!promptsShowing && state !is PresentmentModel.State.Completed) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    IconButton(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = { presentmentModel.setCanceledByUser() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowShared() {
    ShowLottieAnimation(
        message = null,
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
private fun ShowConnectingToReader() {
    val isDarkTheme = isSystemInDarkTheme()
    ShowLottieAnimation(
        message = "Connecting to reader",
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
            Text(text = message)
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