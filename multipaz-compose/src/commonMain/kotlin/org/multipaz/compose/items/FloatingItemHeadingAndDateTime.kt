package org.multipaz.compose.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.TimeZone
import org.multipaz.datetime.FormatStyle
import kotlin.time.Instant

/**
 * Like [SegmentedItemHeadingAndText], but with a date and time instead of text.
 *
 * @param heading will be shown at the top.
 * @param dateAndTime will be shown below the heading or "Not set" if `null`.
 * @param timeZone the timezone to use for displaying the point in time.
 * @param dateStyle the amount of data to include in the date component.
 * @param timeStyle the amount of data to include in the time component.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Deprecated(
    message = "Use SegmentedItemHeadingAndDateTime instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemHeadingAndDateTime(heading = heading, dateAndTime = dateAndTime, timeZone = timeZone, dateStyle = dateStyle, timeStyle = timeStyle, modifier = modifier, showChevron = showChevron, image = image, trailingContent = trailingContent)",
        imports = ["org.multipaz.compose.items.SegmentedItemHeadingAndDateTime"]
    )
)
@Composable
fun FloatingItemHeadingAndDateTime(
    heading: String,
    dateAndTime: Instant?,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    dateStyle: FormatStyle = FormatStyle.MEDIUM,
    timeStyle: FormatStyle = FormatStyle.MEDIUM,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    SegmentedItemHeadingAndDateTime(
        heading = heading,
        dateAndTime = dateAndTime,
        timeZone = timeZone,
        dateStyle = dateStyle,
        timeStyle = timeStyle,
        modifier = modifier,
        showChevron = showChevron,
        image = image,
        trailingContent = trailingContent
    )
}
