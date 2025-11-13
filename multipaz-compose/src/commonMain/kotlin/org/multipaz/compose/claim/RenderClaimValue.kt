package org.multipaz.compose.claim

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.multipaz.claim.Claim
import org.multipaz.claim.MdocClaim
import org.multipaz.claim.JsonClaim
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.util.fromBase64Url
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.compose.decodeImage


/**
 * A composable for displaying a claim.
 *
 * This will be shown as textual information (See [Claim.render] for details) except for claims
 * with [Claim.attribute] set to [DocumentAttributeType.Picture] which will be rendered as images.
 *
 * @param claim the claim to render.
 * @param timeZone the time zone to use for rendering dates and times.
 * @param imageSize size to use for images.
 * @param modifier a [Modifier]
 */
@Composable
fun RenderClaimValue(
    claim: Claim,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    imageSize: Dp = 150.dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth()
    ) {
        if (claim.attribute?.type == DocumentAttributeType.Picture) {
            val img = try {
                val bytes = when (claim) {
                    is MdocClaim -> claim.value.asBstr
                    is JsonClaim -> claim.value.jsonPrimitive.content.fromBase64Url()
                }
                decodeImage(bytes)
            } catch (err: Exception) {
                Text(
                    text = "Image decoding: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                null
            }
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    alignment = Alignment.TopStart,
                    modifier = Modifier.fillMaxWidth().size(imageSize),
                )
            }
        } else {
            val str = claim.render(timeZone)
            Text(
                text = str,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
