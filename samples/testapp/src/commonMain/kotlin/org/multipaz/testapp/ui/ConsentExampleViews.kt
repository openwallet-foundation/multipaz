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
import org.multipaz.presentment.TransactionDataContext
import kotlin.math.abs
import kotlin.math.round

// Renderers for the consent prompt examples: each transaction type gets a short summary in the
// prompt and the full detail on its own page.

internal val transitFareRenderer = TransactionDataRendererCompose(
    summary = { context -> TransitFareSummary(context) },
    details = { context -> TransitFareDetails(context) },
)

internal val prescriptionPickupRenderer = TransactionDataRendererCompose(
    summary = { context -> PrescriptionPickupSummary(context) },
    details = { context -> PrescriptionPickupDetails(context) },
)

private fun formatExampleAmount(amount: Double): String {
    val roundedCents = round(amount * 100.0).toLong()
    return "${roundedCents / 100}.${abs(roundedCents % 100).toString().padStart(2, '0')}"
}

private fun selectedFare(context: TransactionDataContext): TransitFareTransaction.Fare {
    val payload = context.transactionData.first().payload as TransitFareTransaction.Payload
    val fareId = (context.userInput as? TransitFareTransaction.UserInput)?.fareId
    return payload.fares.find { it.id == fareId } ?: TransitFareTransaction.defaultFare(payload)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransitFareSummary(context: TransactionDataContext) {
    val payload = context.transactionData.first().payload as TransitFareTransaction.Payload
    val fare = selectedFare(context)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Pay ${formatExampleAmount(fare.amount)} ${payload.currency}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${payload.operator} · ${payload.zones} · ${fare.validity}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            payload.fares.forEach { option ->
                FilterChip(
                    selected = option.id == fare.id,
                    onClick = { context.onUserInputChanged(TransitFareTransaction.UserInput(option.id)) },
                    label = { Text(option.name) },
                )
            }
        }
    }
}

@Composable
private fun TransitFareDetails(context: TransactionDataContext) {
    val payload = context.transactionData.first().payload as TransitFareTransaction.Payload
    val fare = selectedFare(context)
    val rows = buildList {
        add("Operator" to payload.operator)
        add("Zones" to payload.zones)
        add("Selected" to "${fare.name}, ${formatExampleAmount(fare.amount)} ${payload.currency}")
        add("Valid" to fare.validity)
        payload.fares.forEach { add(it.name to "${formatExampleAmount(it.amount)} ${payload.currency}") }
        add("Requested by" to (context.requester.origin ?: context.requester.appId ?: "Unknown"))
    }
    ExampleDetailRows(title = "TICKET DETAILS", rows = rows)
}

@Composable
private fun PrescriptionPickupSummary(context: TransactionDataContext) {
    val payload = context.transactionData.first().payload as PrescriptionPickupTransaction.Payload
    val count = payload.items.size
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (count == 1) "Pick up 1 prescription" else "Pick up $count prescriptions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "at ${payload.pharmacy}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The medication stays off the main page, which anyone near the screen can read.
        Text(
            text = "Medication names are on the details page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrescriptionPickupDetails(context: TransactionDataContext) {
    val payload = context.transactionData.first().payload as PrescriptionPickupTransaction.Payload
    val rows = buildList {
        payload.items.forEach { item ->
            add("Medication" to "${item.medication}, ${item.strength}")
            add("Quantity" to item.quantity)
            add("Directions" to item.directions)
            add("Prescriber" to item.prescriber)
            add("Rx number" to item.rxNumber)
            add("Refills left" to item.refillsLeft.toString())
        }
        add("Pharmacy" to payload.pharmacy)
        add("Address" to payload.pharmacyAddress)
    }
    ExampleDetailRows(title = "PRESCRIPTION DETAILS", rows = rows)
}

@Composable
private fun ExampleDetailRows(title: String, rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
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
