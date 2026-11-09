package org.multipaz.compose.presentment

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.decodeImage
import org.multipaz.compose.getApplicationInfo
import org.multipaz.compose.text.fromMarkdown
import org.multipaz.consent.ClassifySimpleConsentFn
import org.multipaz.consent.SimpleConsent
import org.multipaz.consent.SimpleConsentClaim
import org.multipaz.consent.defaultClassifySimpleConsent
import org.multipaz.document.Document
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_button_cancel
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_card_art_description
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_birth_year_anonymous_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_birth_year_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_birth_year_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_in_years_anonymous_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_in_years_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_in_years_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_over_anonymous_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_over_known_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_age_over_unknown_requester
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_claim_age_birth_year
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_claim_age_birth_year_and_portrait
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_claim_age_in_years
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_claim_age_in_years_and_portrait
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_claim_age_over
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_claim_age_over_and_portrait
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_claim_not_age_over
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_claim_not_age_over_and_portrait
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_simple_using_document
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_verifier_icon_description
import org.multipaz.multipaz_compose.generated.resources.presentment_activity_app_name_default
import org.multipaz.presentment.PresentmentData
import org.multipaz.request.Requester
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.util.Logger

private const val TAG = "SimpleConsentPromptData"
private const val INLINE_APP_ICON_ID = "app_icon"
private const val INLINE_APP_ICON_TOKEN = "[app_icon]"
private const val INLINE_CARD_ART_ID = "card_art"
private const val INLINE_CARD_ART_TOKEN = "[card_art]"
private val INLINE_TOKEN_REGEX = """\[app_icon\]|\[card_art\]""".toRegex()

/**
 * Resolved UI display data for a simple consent prompt that can be combined with biometric
 * authentication.
 *
 * @property simpleConsent the underlying [SimpleConsent] classification result, including the
 *   selected [org.multipaz.credential.Credential], [org.multipaz.presentment.PresentmentSelection],
 *   and [SimpleConsentClaim].
 * @property cardArt the card art [ImageBitmap] for the selected document (`[CardIcon]`).
 * @property requesterIcon the relying party's icon [ImageBitmap] (`[RP Icon]`), or `null` if the
 *   relying party does not have an inline bitmap icon (for example, when unknown or anonymous, or
 *   when [requesterIconUrl] is used instead).
 * @property requesterIconUrl the URL for the relying party's icon, or `null` if none.
 * @property text the localized single-line summary to display (e.g.,
 *   `"Utopia Wine Shop wants to know if you're older than 21"`).
 * @property isWebOrigin `true` if the request originated from a website (`requester.isWebOrigin`).
 */
data class SimpleConsentPromptData(
    val simpleConsent: SimpleConsent,
    val cardArt: ImageBitmap,
    val requesterIcon: ImageBitmap?,
    val requesterIconUrl: String?,
    val text: String,
    val isWebOrigin: Boolean = false,
)

/**
 * Evaluates whether a credential presentment request qualifies as a simple, well-defined non-PII
 * request and, if so, resolves the [SimpleConsentPromptData] to display on top of the biometric
 * prompt.
 *
 * @param requester the relying party which is requesting the data.
 * @param trustedRequesterIdentity trust information for the requester, or `null` if untrusted.
 * @param presentmentData the combinations of credentials and claims that the user can select.
 * @param preselectedDocuments the list of documents the user may have preselected earlier, or
 *   empty if none.
 * @param selections the selected solution indices for each [org.multipaz.presentment.PresentmentUseCase]
 *   in [PresentmentData.useCases]. Defaults to `presentmentData.defaultSelections(preselectedDocuments)`.
 * @param classifySimpleConsentFn the function used to classify whether the request qualifies as a
 *   [SimpleConsent]. Defaults to [defaultClassifySimpleConsent].
 * @return a [SimpleConsentPromptData] if the request qualifies as simple, or `null` if the full
 *   consent prompt should be displayed.
 */
suspend fun resolveSimpleConsentPromptData(
    requester: Requester,
    trustedRequesterIdentity: TrustedRequesterIdentity?,
    presentmentData: PresentmentData,
    preselectedDocuments: List<Document> = emptyList(),
    selections: List<List<Int>> = presentmentData.defaultSelections(preselectedDocuments),
    classifySimpleConsentFn: ClassifySimpleConsentFn = ::defaultClassifySimpleConsent,
): SimpleConsentPromptData? {
    val simpleConsent = classifySimpleConsentFn(
        requester,
        trustedRequesterIdentity,
        presentmentData,
        selections,
    ) ?: return null

    val document = simpleConsent.credential.document
    val cardArt = if (document.cardArt != null) {
        decodeImage(document.cardArt!!.toByteArray())
    } else {
        Branding.Current.value.renderFallbackCardArt(document)
    }

    val trustMetadata = trustedRequesterIdentity?.trustMetadata
    var requesterName: String? = null
    var requesterIcon: ImageBitmap? = null
    var requesterIconUrl: String? = null

    if (trustedRequesterIdentity != null) {
        requesterName = trustMetadata?.displayName
            ?: trustedRequesterIdentity.identity.certChain.certificates.lastOrNull()?.subject?.name
        requesterIcon = trustMetadata?.displayIcon?.let { decodeImage(it.toByteArray()) }
        requesterIconUrl = trustMetadata?.displayIconUrl
    } else if (requester.isWebOrigin) {
        requesterName = requester.origin?.takeIf { it.isNotEmpty() }
    } else if (requester.appId != null) {
        val appInfo = try {
            getApplicationInfo(requester.appId!!)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(TAG, "Error looking up information for appId ${requester.appId}")
            null
        }
        requesterName = appInfo?.name
        requesterIcon = appInfo?.icon
    }

    val text = if (requesterName != null) {
        when (val claim = simpleConsent.claim) {
            is SimpleConsentClaim.AgeOver -> getString(
                Res.string.credential_presentment_simple_age_over_known_requester,
                requesterName,
                claim.age,
            )
            is SimpleConsentClaim.AgeOverAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_over_known_requester,
                requesterName,
                claim.age,
            )
            is SimpleConsentClaim.NotAgeOver -> getString(
                Res.string.credential_presentment_simple_age_over_known_requester,
                requesterName,
                claim.age,
            )
            is SimpleConsentClaim.NotAgeOverAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_over_known_requester,
                requesterName,
                claim.age,
            )
            SimpleConsentClaim.AgeInYears,
            SimpleConsentClaim.AgeInYearsAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_in_years_known_requester,
                requesterName,
            )
            SimpleConsentClaim.AgeBirthYear,
            SimpleConsentClaim.AgeBirthYearAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_birth_year_known_requester,
                requesterName,
            )
        }
    } else if (requester.requesterIdentities.isNotEmpty()) {
        when (val claim = simpleConsent.claim) {
            is SimpleConsentClaim.AgeOver -> getString(
                Res.string.credential_presentment_simple_age_over_unknown_requester,
                claim.age,
            )
            is SimpleConsentClaim.AgeOverAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_over_unknown_requester,
                claim.age,
            )
            is SimpleConsentClaim.NotAgeOver -> getString(
                Res.string.credential_presentment_simple_age_over_unknown_requester,
                claim.age,
            )
            is SimpleConsentClaim.NotAgeOverAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_over_unknown_requester,
                claim.age,
            )
            SimpleConsentClaim.AgeInYears,
            SimpleConsentClaim.AgeInYearsAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_in_years_unknown_requester,
            )
            SimpleConsentClaim.AgeBirthYear,
            SimpleConsentClaim.AgeBirthYearAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_birth_year_unknown_requester,
            )
        }
    } else {
        when (val claim = simpleConsent.claim) {
            is SimpleConsentClaim.AgeOver -> getString(
                Res.string.credential_presentment_simple_age_over_anonymous_requester,
                claim.age,
            )
            is SimpleConsentClaim.AgeOverAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_over_anonymous_requester,
                claim.age,
            )
            is SimpleConsentClaim.NotAgeOver -> getString(
                Res.string.credential_presentment_simple_age_over_anonymous_requester,
                claim.age,
            )
            is SimpleConsentClaim.NotAgeOverAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_over_anonymous_requester,
                claim.age,
            )
            SimpleConsentClaim.AgeInYears,
            SimpleConsentClaim.AgeInYearsAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_in_years_anonymous_requester,
            )
            SimpleConsentClaim.AgeBirthYear,
            SimpleConsentClaim.AgeBirthYearAndPortrait -> getString(
                Res.string.credential_presentment_simple_age_birth_year_anonymous_requester,
            )
        }
    }

    return SimpleConsentPromptData(
        simpleConsent = simpleConsent,
        cardArt = cardArt,
        requesterIcon = requesterIcon,
        requesterIconUrl = requesterIconUrl,
        text = text,
        isWebOrigin = requester.isWebOrigin,
    )
}

/**
 * Compact composable displayed inside an embedded biometric prompt for a [SimpleConsentPromptData]
 * request, showing the relying party icon, heading text, inline document summary with a link
 * to view full consent details, and a top-right close button to cancel.
 */
@Composable
internal fun SimpleConsentPrompt(
    data: SimpleConsentPromptData,
    imageLoader: ImageLoader?,
    onShowDetails: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnShowDetails by rememberUpdatedState(onShowDetails)
    val currentBranding = Branding.Current.collectAsState().value
    val appName = currentBranding.appName
        ?: stringResource(Res.string.presentment_activity_app_name_default)
    val appIconPainter = currentBranding.appIconPainter
    val document = data.simpleConsent.credential.document
    val documentName = document.displayName ?: document.typeDisplayName ?: document.identifier
    val sharedItemsText = data.simpleConsent.claim.toSharedItemsText()
    val subtextTemplate = stringResource(
        Res.string.credential_presentment_simple_using_document,
        appName,
        documentName,
        sharedItemsText,
    )
    val hasAppIcon = appIconPainter != null
    val subtextAnnotated = remember(subtextTemplate, hasAppIcon) {
        val effectiveTemplate = if (hasAppIcon) {
            subtextTemplate
        } else {
            subtextTemplate
                .replace("$INLINE_APP_ICON_TOKEN ", "")
                .replace(INLINE_APP_ICON_TOKEN, "")
        }
        buildAnnotatedString {
            var lastIndex = 0
            for (match in INLINE_TOKEN_REGEX.findAll(effectiveTemplate)) {
                if (match.range.first > lastIndex) {
                    append(
                        AnnotatedString.fromMarkdown(
                            markdownString = effectiveTemplate.substring(lastIndex, match.range.first),
                            linkInteractionListener = { latestOnShowDetails() },
                        )
                    )
                }
                when (match.value) {
                    INLINE_APP_ICON_TOKEN -> appendInlineContent(INLINE_APP_ICON_ID, "[app]")
                    INLINE_CARD_ART_TOKEN -> appendInlineContent(INLINE_CARD_ART_ID, "[card]")
                }
                lastIndex = match.range.last + 1
            }
            if (lastIndex < effectiveTemplate.length) {
                append(
                    AnnotatedString.fromMarkdown(
                        markdownString = effectiveTemplate.substring(lastIndex),
                        linkInteractionListener = { latestOnShowDetails() },
                    )
                )
            }
        }
    }
    val cardArtDescription = stringResource(Res.string.credential_presentment_card_art_description)
    val inlineContent = remember(appIconPainter, data.cardArt, cardArtDescription) {
        buildMap {
            if (appIconPainter != null) {
                put(
                    INLINE_APP_ICON_ID,
                    InlineTextContent(
                        Placeholder(
                            width = 16.sp,
                            height = 16.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        )
                    ) {
                        Image(
                            modifier = Modifier.fillMaxSize(),
                            painter = appIconPainter,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                        )
                    }
                )
            }
            put(
                INLINE_CARD_ART_ID,
                InlineTextContent(
                    Placeholder(
                        width = 24.sp,
                        height = 16.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                    )
                ) {
                    Image(
                        modifier = Modifier.fillMaxSize(),
                        bitmap = data.cardArt,
                        contentDescription = cardArtDescription,
                        contentScale = ContentScale.Fit,
                    )
                }
            )
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        IconButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
            onClick = onCancel,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(
                    Res.string.credential_presentment_button_cancel
                ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (data.requesterIcon != null) {
                Icon(
                    modifier = Modifier.size(80.dp),
                    bitmap = data.requesterIcon,
                    contentDescription = stringResource(
                        Res.string.credential_presentment_verifier_icon_description
                    ),
                    tint = Color.Unspecified,
                )
            } else if (data.requesterIconUrl != null && imageLoader != null) {
                AsyncImage(
                    modifier = Modifier.size(80.dp),
                    model = data.requesterIconUrl,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    contentDescription = stringResource(
                        Res.string.credential_presentment_verifier_icon_description
                    ),
                )
            } else {
                Icon(
                    modifier = Modifier.size(80.dp),
                    imageVector = if (data.isWebOrigin) {
                        Icons.Outlined.Language
                    } else {
                        Icons.Outlined.Business
                    },
                    contentDescription = stringResource(
                        Res.string.credential_presentment_verifier_icon_description
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = data.text,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = subtextAnnotated,
                inlineContent = inlineContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun SimpleConsentClaim.toSharedItemsText(): String = when (this) {
    is SimpleConsentClaim.AgeOver -> stringResource(
        Res.string.credential_presentment_simple_claim_age_over,
        age,
    )
    is SimpleConsentClaim.AgeOverAndPortrait -> stringResource(
        Res.string.credential_presentment_simple_claim_age_over_and_portrait,
        age,
    )
    is SimpleConsentClaim.NotAgeOver -> stringResource(
        Res.string.credential_presentment_simple_claim_not_age_over,
        age,
    )
    is SimpleConsentClaim.NotAgeOverAndPortrait -> stringResource(
        Res.string.credential_presentment_simple_claim_not_age_over_and_portrait,
        age,
    )
    SimpleConsentClaim.AgeInYears -> stringResource(
        Res.string.credential_presentment_simple_claim_age_in_years,
    )
    SimpleConsentClaim.AgeInYearsAndPortrait -> stringResource(
        Res.string.credential_presentment_simple_claim_age_in_years_and_portrait,
    )
    SimpleConsentClaim.AgeBirthYear -> stringResource(
        Res.string.credential_presentment_simple_claim_age_birth_year,
    )
    SimpleConsentClaim.AgeBirthYearAndPortrait -> stringResource(
        Res.string.credential_presentment_simple_claim_age_birth_year_and_portrait,
    )
}
