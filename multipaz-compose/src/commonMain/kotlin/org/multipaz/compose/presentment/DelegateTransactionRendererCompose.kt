package org.multipaz.compose.presentment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionDataContext
import org.multipaz.utopia.knowntypes.DelegateMandateText
import org.multipaz.utopia.knowntypes.DelegateTransaction

/**
 * The SDK's renderer for AP2 [DelegateTransaction] items, drawn as a spending permission.
 *
 * The consent prompt shows the spending limits set large, then where the agent may buy, who is
 * paid, what, until when and for which agent, one row each. The details page lists every mandate
 * in full, as it will be signed, since AP2 requires the Mandate Content to be shown, together with
 * the origin or app that asked. The words come from [DelegateMandateText].
 *
 * `addUtopiaTypes()` does not register [DelegateTransaction]; an application that does registers
 * this renderer with it:
 *
 * ```
 * documentTypeRepository.addDelegateTransactionType()
 * transactionDataRendererRepository.addRenderer(
 *     DelegateTransaction.identifier,
 *     DelegateTransactionRendererCompose
 * )
 * ```
 *
 * An application that wants its own look registers its own [TransactionDataRendererCompose]
 * instead, built on [DelegateMandateText].
 */
object DelegateTransactionRendererCompose : TransactionDataRendererCompose(
    summary = { context -> SpendingPermissionSummary(context) },
    details = { context -> SpendingPermissionDetails(context) },
)

/**
 * The mandates as a person reads them, with their selectively disclosed terms filled in: `null`
 * while they are being worked out (that takes a hash per disclosure), a failure when they can't be.
 */
@Composable
private fun rememberShownMandates(transactionData: List<TransactionData<*>>): Result<List<JsonObject>>? {
    val shown by produceState<Result<List<JsonObject>>?>(initialValue = null, transactionData) {
        value = try {
            Result.success(DelegateTransaction.shownMandates(transactionData))
        } catch (err: IllegalArgumentException) {
            Result.failure(err)
        }
    }
    return shown
}

/**
 * The spending permission in the consent prompt.
 *
 * The limits lead and are set large, because they are what the person has to read correctly.
 * The conditions follow as short rows. Strings come from the relying party, so they are shown
 * as plain text and bounded here; the details page shows them in full.
 */
@Composable
private fun SpendingPermissionSummary(context: TransactionDataContext) {
    val shown = rememberShownMandates(context.transactionData) ?: return
    val mandates = shown.getOrElse {
        CannotShow()
        return
    }
    val summary = remember(mandates) { DelegateMandateText.describePermission(mandates) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (context.hasClaims) 12.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PermissionHeader()
        if (summary.limits.isNotEmpty()) {
            LimitsCard(limits = summary.limits)
        }
        if (summary.rows.isNotEmpty()) {
            ConditionRows(rows = summary.rows)
        }
    }
}

/**
 * Said in place of the permission when its mandates can't be read, rather than drawing nothing:
 * a person must not approve a spending permission they were not shown.
 */
@Composable
private fun CannotShow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = DelegateMandateText.CANNOT_SHOW,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PermissionHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.CreditCard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = DelegateMandateText.TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = DelegateMandateText.SUMMARY_EXPLAINER,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LimitsCard(limits: List<DelegateMandateText.Limit>) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            limits.forEachIndexed { index, limit ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        text = limit.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = limit.amount,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = limit.currency,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConditionRows(rows: List<DelegateMandateText.SummaryLine>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, line ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Icon(
                    imageVector = iconFor(line),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(88.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = line.value,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    line.detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A row's icon, from what the row is about; a row of another kind gets one for its shape. */
private fun iconFor(line: DelegateMandateText.SummaryLine): ImageVector = when (line.kind) {
    DelegateMandateText.Kind.MERCHANT -> Icons.Outlined.Storefront
    DelegateMandateText.Kind.PAYEE -> Icons.Outlined.Payments
    DelegateMandateText.Kind.ITEMS -> Icons.Outlined.ShoppingBag
    DelegateMandateText.Kind.EXPIRY -> Icons.Outlined.Event
    DelegateMandateText.Kind.AGENT -> Icons.Outlined.Key
    DelegateMandateText.Kind.OTHER -> when (line.shape) {
        DelegateMandateText.Shape.INSTANT -> Icons.Outlined.Event
        DelegateMandateText.Shape.PARTY -> Icons.Outlined.Key
        else -> Icons.Outlined.Info
    }
}

/**
 * Everything the spending permission contains, mandate by mandate, as it will be signed.
 */
@Composable
private fun SpendingPermissionDetails(context: TransactionDataContext) {
    val shown = rememberShownMandates(context.transactionData) ?: return
    val mandates = shown.getOrElse {
        CannotShow()
        return
    }
    val sections = remember(mandates) { DelegateMandateText.describeMandates(mandates) }
    val requestedBy = context.requester.origin ?: context.requester.appId
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = DelegateMandateText.TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = DelegateMandateText.DETAILS_EXPLAINER,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            requestedBy?.let {
                DetailLine(label = DelegateMandateText.REQUESTED_BY, value = it)
            }
            sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = section.heading,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (section.mandateType.isNotEmpty()) {
                        Text(
                            text = DelegateMandateText.mandateTypeLine(section.mandateType),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    section.lines.forEach { line ->
                        DetailLine(
                            label = line.label,
                            value = line.value,
                            detail = line.detail,
                            monospace = line.shape == DelegateMandateText.Shape.OPAQUE,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
    detail: String? = null,
    monospace: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
