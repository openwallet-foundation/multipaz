package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.compose.presentment.TransactionDataRendererCompose
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.presentment.TransactionDataContext
import org.multipaz.prompt.TransactionDataRendererRepository
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * The test app's renderers for transaction data in the consent prompt.
 *
 * A [PaymentTransaction], a [TransitFareTransaction] or a [PrescriptionPickupTransaction] is drawn by
 * the app: the essentials in the prompt, with every field on the details page. Other transaction
 * types keep the built-in rendering.
 */
val testAppTransactionDataRenderers = TransactionDataRendererRepository().apply {
    addRenderer(
        PaymentTransaction.identifier,
        TransactionDataRendererCompose(
            summary = { context -> PaymentSummary(context) },
            details = { context -> PaymentDetails(context) },
        )
    )
    addRenderer(TransitFareTransaction.identifier, transitFareRenderer)
    addRenderer(PrescriptionPickupTransaction.identifier, prescriptionPickupRenderer)
}

private val tipOptions = listOf(0.0 to "No tip", 10.0 to "10%", 15.0 to "15%", 20.0 to "20%")

private fun formatAmount(amount: Double): String {
    val roundedCents = round(amount * 100.0).toLong()
    return "${roundedCents / 100}.${abs(roundedCents % 100).toString().padStart(2, '0')}"
}

private fun tipAmount(payload: PaymentTransaction.Payload, tipPercent: Double): Double =
    if (payload.tipRequested == true) ceil(payload.amount * tipPercent) / 100.0 else 0.0

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentSummary(context: TransactionDataContext) {
    val payload = context.transactionData.first().payload as PaymentTransaction.Payload
    val tipPercent = (context.userInput as? PaymentTransaction.UserInput)?.tipPercent ?: 0.0
    val total = payload.amount + tipAmount(payload, tipPercent)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Pay ${formatAmount(total)} ${payload.currency}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "to ${payload.payee.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (payload.tipRequested == true) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tipOptions.forEach { (percent, label) ->
                    FilterChip(
                        selected = tipPercent == percent,
                        onClick = { context.onUserInputChanged(PaymentTransaction.UserInput(percent)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentDetails(context: TransactionDataContext) {
    val payload = context.transactionData.first().payload as PaymentTransaction.Payload
    val tipPercent = (context.userInput as? PaymentTransaction.UserInput)?.tipPercent ?: 0.0
    val tip = tipAmount(payload, tipPercent)
    val rows = buildList {
        add("Payee" to payload.payee.name)
        add("Payee ID" to payload.payee.id)
        payload.payee.website?.let { add("Website" to it) }
        add("Amount" to "${formatAmount(payload.amount)} ${payload.currency}")
        if (payload.tipRequested == true) {
            add("Tip" to "${formatAmount(tip)} ${payload.currency}")
            add("Total" to "${formatAmount(payload.amount + tip)} ${payload.currency}")
        }
        add("Transaction ID" to payload.transactionId)
        payload.dateTime?.let { add("Date" to it.toString()) }
        payload.executionDate?.let { add("Execution date" to it.toString()) }
        payload.pisp?.let { add("Payment initiator" to "${it.brandName} (${it.domainName})") }
        add("Requested by" to (context.requester.origin ?: context.requester.appId ?: "Unknown"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "TRANSACTION DETAILS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    modifier = Modifier.weight(0.4f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = value, modifier = Modifier.weight(0.6f))
            }
            HorizontalDivider()
        }
    }
}
