package org.multipaz.testapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.decodeImage
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherLivenessPromptState
import org.multipaz.facematch.FaceMatcherLivenessSession
import org.multipaz.facematch.FaceMatcherPromptState
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.facematch.FacePose
import org.multipaz.facenet.testdata.FaceTestData
import org.multipaz.testapp.ui.getFaceMatcherReferencePortrait

/**
 * Sample [FaceMatcher] implementation with [usesCustomUserInterface] set to `true` that launches
 * its own [CustomUiFaceMatcherActivity] for both face matching and liveness verification.
 */
class CustomUiFaceMatcher : FaceMatcher {
    override val name: String = "custom_ui"
    override val displayName: String = "Custom UI"
    override val supportsLiveness: Boolean = true
    override val usesCustomUserInterface: Boolean = true

    override fun createSession(referencePortrait: ByteString): FaceMatcherSession {
        require(referencePortrait.size > 0) { "Reference portrait must not be empty" }
        val bitmap = try {
            decodeImage(referencePortrait.toByteArray())
        } catch (e: Throwable) {
            throw IllegalArgumentException("Failed to decode reference portrait", e)
        }
        val session = CustomUiFaceMatcherSession(referencePortrait, bitmap)
        CustomUiFaceMatcherActivity.activeEntry = CustomUiSessionEntry.Match(session)
        launchActivity()
        return session
    }

    override fun createLivenessSession(poses: List<FacePose>): FaceMatcherLivenessSession {
        require(poses.isNotEmpty()) { "At least one pose must be specified" }
        val session = CustomUiFaceMatcherLivenessSession(poses)
        CustomUiFaceMatcherActivity.activeEntry = CustomUiSessionEntry.Liveness(session)
        launchActivity()
        return session
    }

    private fun launchActivity() {
        val intent = Intent(applicationContext, CustomUiFaceMatcherActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        applicationContext.startActivity(intent)
    }
}

internal sealed interface CustomUiSessionEntry {
    var activity: CustomUiFaceMatcherActivity?
    fun simulateCancel()

    class Match(val session: CustomUiFaceMatcherSession) : CustomUiSessionEntry {
        override var activity: CustomUiFaceMatcherActivity?
            get() = session.activity
            set(value) {
                session.activity = value
            }

        override fun simulateCancel() = session.simulateCancel()
    }

    class Liveness(val session: CustomUiFaceMatcherLivenessSession) : CustomUiSessionEntry {
        override var activity: CustomUiFaceMatcherActivity?
            get() = session.activity
            set(value) {
                session.activity = value
            }

        override fun simulateCancel() = session.simulateCancel()
    }
}

internal class CustomUiFaceMatcherSession(
    referencePortrait: ByteString,
    val imageBitmap: ImageBitmap
) : FaceMatcherSession(referencePortrait) {

    @Volatile
    var activity: CustomUiFaceMatcherActivity? = null

    fun simulateMatch() {
        if (state.value.status == FaceMatcherPromptState.Status.IN_PROGRESS) {
            updateState(status = FaceMatcherPromptState.Status.SUCCESS)
        }
    }

    fun simulateCancel() {
        if (state.value.status == FaceMatcherPromptState.Status.IN_PROGRESS) {
            updateState(status = FaceMatcherPromptState.Status.FAILED)
        }
    }

    override fun cancel() {
        val active = CustomUiFaceMatcherActivity.activeEntry
        if (active is CustomUiSessionEntry.Match && active.session === this) {
            CustomUiFaceMatcherActivity.activeEntry = null
        }
        val currentActivity = activity
        activity = null
        currentActivity?.finish()
    }
}

internal class CustomUiFaceMatcherLivenessSession(
    poses: List<FacePose>
) : FaceMatcherLivenessSession(poses) {

    @Volatile
    var activity: CustomUiFaceMatcherActivity? = null

    fun simulateContinue(portrait: ByteString) {
        if (state.value.status == FaceMatcherLivenessPromptState.Status.IN_PROGRESS) {
            updateState(
                status = FaceMatcherLivenessPromptState.Status.SUCCESS,
                capturedImages = poses.associateWith { portrait }
            )
        }
    }

    fun simulateCancel() {
        if (state.value.status == FaceMatcherLivenessPromptState.Status.IN_PROGRESS) {
            updateState(status = FaceMatcherLivenessPromptState.Status.FAILED)
        }
    }

    override fun cancel() {
        val active = CustomUiFaceMatcherActivity.activeEntry
        if (active is CustomUiSessionEntry.Liveness && active.session === this) {
            CustomUiFaceMatcherActivity.activeEntry = null
        }
        val currentActivity = activity
        activity = null
        currentActivity?.finish()
    }
}

class CustomUiFaceMatcherActivity : FragmentActivity() {

    companion object {
        @Volatile
        internal var activeEntry: CustomUiSessionEntry? = null
    }

    private var boundEntry: CustomUiSessionEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initializeApplication(this.applicationContext)

        val entry = activeEntry
        if (entry == null) {
            finish()
            return
        }
        boundEntry = entry
        entry.activity = this

        setContent {
            val currentBranding = Branding.Current.collectAsState().value
            currentBranding.theme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    when (entry) {
                        is CustomUiSessionEntry.Match -> {
                            val session = entry.session
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Custom Face Matcher UI",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "This is a FaceMatcher implementation with usesCustomUserInterface = true " +
                                        "that launches its own Activity. It shows the reference portrait to match " +
                                        "against and lets you cancel or simulate a successful match.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Image(
                                    bitmap = session.imageBitmap,
                                    contentDescription = "Reference Portrait",
                                    modifier = Modifier
                                        .size(260.dp)
                                        .clip(RoundedCornerShape(16.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(modifier = Modifier.height(32.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            session.simulateCancel()
                                            finish()
                                        }
                                    ) {
                                        Text("Cancel")
                                    }
                                    Button(
                                        onClick = {
                                            session.simulateMatch()
                                            finish()
                                        }
                                    ) {
                                        Text("Simulate Match")
                                    }
                                }
                            }
                        }
                        is CustomUiSessionEntry.Liveness -> {
                            val session = entry.session
                            val defaultPortrait = remember { FaceTestData.allPortraits.first().data }
                            var portraitBytes by remember { mutableStateOf(defaultPortrait) }
                            var portraitBitmap by remember {
                                mutableStateOf(decodeImage(defaultPortrait.toByteArray()))
                            }
                            LaunchedEffect(session) {
                                val stored = getFaceMatcherReferencePortrait()
                                if (stored != null && stored.size > 0) {
                                    try {
                                        portraitBitmap = decodeImage(stored.toByteArray())
                                        portraitBytes = stored
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                            val posesDescription = remember(session.poses) {
                                session.poses.joinToString(", ") { it.name }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Custom Face Liveness UI",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "This is a FaceMatcher implementation with usesCustomUserInterface = true " +
                                        "that launches its own Activity for liveness verification. " +
                                        "Required poses: $posesDescription. " +
                                        "Tapping Continue returns the reference portrait below for each requested pose.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Image(
                                    bitmap = portraitBitmap,
                                    contentDescription = "Reference Portrait",
                                    modifier = Modifier
                                        .size(260.dp)
                                        .clip(RoundedCornerShape(16.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(modifier = Modifier.height(32.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            session.simulateCancel()
                                            finish()
                                        }
                                    ) {
                                        Text("Cancel")
                                    }
                                    Button(
                                        onClick = {
                                            session.simulateContinue(portraitBytes)
                                            finish()
                                        }
                                    ) {
                                        Text("Continue")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val entry = boundEntry
        if (entry != null) {
            if (entry.activity === this) {
                entry.activity = null
            }
            if (isFinishing) {
                entry.simulateCancel()
                if (activeEntry === entry) {
                    activeEntry = null
                }
            }
        }
    }
}
