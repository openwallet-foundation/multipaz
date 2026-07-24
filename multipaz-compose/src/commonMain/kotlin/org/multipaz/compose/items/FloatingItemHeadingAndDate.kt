package org.multipaz.compose.items

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.datetime.formattedDate
import org.multipaz.datetime.FormatStyle
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.floating_item_heading_and_date_not_set
import kotlin.time.Instant

/**
 * Like [FloatingItemHeadingAndText], but with a date instead of text.
 *
 * @param heading will be shown in bold at the top.
 * @param date will be shown below the heading or "Not set" if `null`.
 * @param timeZone the timezone to use for displaying the point in time.
 * @param dateStyle the amount of data to include in the date component.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Composable
fun FloatingItemHeadingAndDate(
    heading: String,
    date: Instant?,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    dateStyle: FormatStyle = FormatStyle.MEDIUM,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    val text = date?.let { formattedDate(
        instant = it,
        timeZone = timeZone,
        dateStyle = dateStyle,
    ) }
        ?: AnnotatedString(stringResource(Res.string.floating_item_heading_and_date_not_set))
    FloatingItemHeadingAndText(
        heading = heading,
        text = text,
        modifier = modifier,
        showChevron = showChevron,
        image = image,
        trailingContent = trailingContent
    )
}
