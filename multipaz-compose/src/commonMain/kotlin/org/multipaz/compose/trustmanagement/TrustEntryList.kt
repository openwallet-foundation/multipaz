package org.multipaz.compose.trustmanagement

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.multipaz.compose.branding.Branding
import org.multipaz.compose.decodeImage
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.compose.items.FloatingItemTextAndSecondary

/**
 * A Composable that displays a scrollable list of trust entries managed by a [TrustManagerModel].
 *
 * It observes the [TrustManagerModel.trustManagerInfos] state and automatically updates
 * when trust entries are added, removed, or modified. It's using [FloatingItemList] to
 * display items
 *
 * @param trustManagerModel The presentation model observing the underlying TrustManager.
 * @param title The title to display at the top of the list.
 * @param imageLoader a [ImageLoader].
 * @param noItems A Composable to render when the trust manager is empty, normally a [FloatingItemCenteredText].
 * @param onTrustEntryClicked Callback invoked when a specific trust entry in the list is clicked.
 * @param modifier The modifier to apply to the list.
 */
@Composable
fun TrustEntryList(
    trustManagerModel: TrustManagerModel,
    title: String,
    imageLoader: ImageLoader,
    noItems: @Composable () -> Unit = {},
    onTrustEntryClicked: (trustEntryInfo: TrustEntryInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val infos = trustManagerModel.trustManagerInfos.collectAsState().value
    FloatingItemList(
        modifier = modifier,
        title = title,
    ) {
        if (infos.isEmpty()) {
            noItems()
        } else {
            infos.forEach { trustEntryInfo ->
                FloatingItemTextAndSecondary(
                    modifier = Modifier.clickable { onTrustEntryClicked(trustEntryInfo) },
                    image = {
                        trustEntryInfo.RenderImage(
                            size = 40.dp,
                            imageLoader = imageLoader
                        )
                    },
                    text = trustEntryInfo.getDisplayName(),
                    secondary = trustEntryInfo.entry.getDetails(trustEntryInfo.signedVical, trustEntryInfo.signedRical),
                )
            }
        }
    }
}

/**
 * Renders the visual icon for a [TrustEntryInfo].
 *
 * This function first checks if a custom display icon is provided in the entry's metadata.
 * If present, it decodes and renders that image. If missing, it dynamically generates a
 * fallback avatar containing the initials of the entry's display name, set against a
 * deterministically colored circular background based on the name's hash.
 *
 * @param size The physical dimensions (width and height) of the rendered image.
 * @param imageLoader a [ImageLoader].
 * @param modifier The modifier to be applied to the resulting image or avatar box.
 */
@Composable
internal fun TrustEntryInfo.RenderImage(
    size: Dp,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier
) {
    entry.metadata.displayIcon?.let {
        val bitmap = remember { decodeImage(it.toByteArray()) }
        Image(
            modifier = modifier.size(size),
            bitmap = bitmap,
            contentDescription = null
        )
        return
    }

    entry.metadata.displayIconUrl?.let {
        AsyncImage(
            modifier = modifier.size(size),
            model = it,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            contentDescription = null
        )
        return
    }

    Branding.Current.collectAsState().value.AvatarIcon(
        size = size,
        name = getDisplayName(),
        additionalData = entry.identifier.encodeToByteArray()
    )
}