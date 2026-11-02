package org.multipaz.compose.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.TimeZone
import org.multipaz.datetime.FormatStyle
import kotlin.time.Instant

/**
 * Like [SegmentedItemHeadingAndText], but with a date instead of text.
 *
 * @param heading will be shown at the top.
 * @param date will be shown below the heading or "Not set" if `null`.
 * @param timeZone the timezone to use for displaying the point in time.
 * @param dateStyle the amount of data to include in the date component.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Deprecated(
    message = "Use SegmentedItemHeadingAndDate instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemHeadingAndDate(heading = heading, date = date, timeZone = timeZone, dateStyle = dateStyle, modifier = modifier, showChevron = showChevron, image = image, trailingContent = trailingContent)",
        imports = ["org.multipaz.compose.items.SegmentedItemHeadingAndDate"]
    )
)
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
    SegmentedItemHeadingAndDate(
        heading = heading,
        date = date,
        timeZone = timeZone,
        dateStyle = dateStyle,
        modifier = modifier,
        showChevron = showChevron,
        image = image,
        trailingContent = trailingContent
    )
}
