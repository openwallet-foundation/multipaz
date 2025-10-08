package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.z4kn4fein.semver.Version
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import org.multipaz.compose.cards.InfoCard
import org.multipaz.testapp.BuildConfig
import org.multipaz.testapp.TestAppConfiguration
import org.multipaz.util.Logger
import org.multipaz.util.Platform

private const val TAG = "AppUpdateCard"

@Composable
fun AppUpdateCard() {
    // Uncomment below if working on this code from Android Studio.
    //
    //val updateUrl =  "https://apps.multipaz.org/testapp/LATEST-VERSION.txt"
    //val updateWebsiteUrl =  "https://apps.multipaz.org/"
    //val currentVersion = "0.91.0-pre.48.574b479c"
    val updateUrl = BuildConfig.TEST_APP_UPDATE_URL
    val updateWebsiteUrl = BuildConfig.TEST_APP_UPDATE_WEBSITE_URL
    val currentVersion = Platform.version

    if (updateUrl.isEmpty()) {
        return
    }

    val latestVersionString = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(true) {
        try {
            val httpClient = HttpClient(TestAppConfiguration.httpClientEngineFactory.create())
            val response = httpClient.get(updateUrl)
            if (response.status == HttpStatusCode.OK) {
                latestVersionString.value = response.readRawBytes().decodeToString().trim()
                Logger.i(TAG, "Latest available version from $updateWebsiteUrl is ${latestVersionString.value} " +
                        "and our version is $currentVersion")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "Error checking latest version from $updateWebsiteUrl", e)
        }
    }


    latestVersionString.value?.let {
        val currentVersion = Version.parse(
            versionString = currentVersion,
            strict = false
        )
        val availableVersion = Version.parse(
            versionString = it,
            strict = false
        )
        if (currentVersion < availableVersion) {
            InfoCard(
                modifier = Modifier.padding(8.dp)
            ) {
                val str = buildAnnotatedString {
                    append(
                        "You are running version $currentVersion and version " +
                        "${latestVersionString.value} is the latest available. " +
                        "Visit "
                    )
                    withLink(
                        LinkAnnotation.Url(
                            updateWebsiteUrl,
                            TextLinkStyles(
                                style = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline),
                            )
                        )
                    ) {
                        append(updateWebsiteUrl)
                    }
                    append(" to update.")
                }
                Text(text = str)
            }
        }
    }
}