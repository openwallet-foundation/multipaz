package org.multipaz.compose.presentment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.datetime.formattedDateTime
import org.multipaz.datetime.FormatStyle
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_text_acceptance_tx_accept_checkbox
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_text_acceptance_tx_also_approved
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_text_acceptance_tx_approved
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_text_acceptance_tx_expires
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_text_acceptance_tx_review_hint
import org.multipaz.multipaz_compose.generated.resources.credential_presentment_text_acceptance_tx_scroll_hint
import org.multipaz.presentment.TransactionDataContext
import org.multipaz.utopia.knowntypes.TextAcceptanceTransaction

/**
 * The consent prompt's renderer for [TextAcceptanceTransaction].
 *
 * The summary shows a short preview and keeps the prompt's continue action disabled until the
 * holder has read the full statement on the details page and checked the acceptance box. Long
 * statements are never truncated on the details page; the acceptance box sits after the full text,
 * so the holder has to scroll through the statement before confirming it.
 */
val textAcceptanceTransactionRenderer = TransactionDataRendererCompose(
    summary = { context -> TextAcceptanceSummary(context) },
    details = { context -> TextAcceptanceDetails(context) },
)

@Composable
private fun TextAcceptanceSummary(context: TransactionDataContext) {
    val payloads = context.transactionData.map {
        it.payload as TextAcceptanceTransaction.Payload
    }
    val accepted = (context.userInput as? TextAcceptanceTransaction.UserInput)?.accepted == true
    LaunchedEffect(accepted) {
        context.onContinueAllowedChanged(accepted)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(
                if (context.hasClaims) {
                    Res.string.credential_presentment_text_acceptance_tx_also_approved
                } else {
                    Res.string.credential_presentment_text_acceptance_tx_approved
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        // Show every statement, but only a preview. The details page always has the full text.
        for (payload in payloads) {
            Text(
                text = payload.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            ExpiryRow(payload = payload)
        }
        if (!accepted) {
            Text(
                text = stringResource(Res.string.credential_presentment_text_acceptance_tx_review_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextAcceptanceDetails(context: TransactionDataContext) {
    val payloads = context.transactionData.map {
        it.payload as TextAcceptanceTransaction.Payload
    }
    val accepted = (context.userInput as? TextAcceptanceTransaction.UserInput)?.accepted == true
    LaunchedEffect(accepted) {
        context.onContinueAllowedChanged(accepted)
    }
    val canAccept = context.isScrolledToBottom || accepted
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (context.hasClaims) {
                    Res.string.credential_presentment_text_acceptance_tx_also_approved
                } else {
                    Res.string.credential_presentment_text_acceptance_tx_approved
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        // Every statement is rendered verbatim as plain text: no markup is interpreted, no
        // truncation, and no mutation after the holder begins confirming.
        for ((index, payload) in payloads.withIndex()) {
            if (index > 0) {
                HorizontalDivider()
            }
            Text(
                text = payload.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            ExpiryRow(payload = payload)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = accepted,
                    enabled = canAccept,
                    role = Role.Checkbox,
                    onValueChange = { checked ->
                        context.onUserInputChanged(TextAcceptanceTransaction.UserInput(checked))
                        context.onContinueAllowedChanged(checked)
                    },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = accepted,
                enabled = canAccept,
                onCheckedChange = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.credential_presentment_text_acceptance_tx_accept_checkbox),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (!context.isScrolledToBottom) {
            Text(
                text = stringResource(Res.string.credential_presentment_text_acceptance_tx_scroll_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExpiryRow(payload: TextAcceptanceTransaction.Payload) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(
                Res.string.credential_presentment_text_acceptance_tx_expires,
                formattedDateTime(
                    instant = payload.expiresAt,
                    timeStyle = FormatStyle.SHORT,
                    includeDurationFromNow = true,
                ).text,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}