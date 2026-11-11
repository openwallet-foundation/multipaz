package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.multipaz.compose.cards.InfoCard
import org.multipaz.compose.cards.WarningCard
import org.multipaz.compose.items.SegmentedItemCenteredText
import org.multipaz.compose.items.SegmentedItemHeadingAndText
import org.multipaz.compose.items.SegmentedItemList
import org.multipaz.compose.scrollbar.LazyColumnWithScrollbar
import org.multipaz.datetime.formatLocalized
import org.multipaz.testapp.NfcObserveModeHelper
import org.multipaz.testapp.NfcPollingFrame
import org.multipaz.testapp.NfcPollingFrameType
import org.multipaz.util.toHex

@Composable
fun NfcObserveModeScreen(
    showToast: (message: String) -> Unit
) {
    var forceObserveMode by remember { mutableStateOf(true) }

    DisposableEffect(forceObserveMode) {
        NfcObserveModeHelper.isForceEnabledAndCapturingAllFrames = forceObserveMode
        onDispose {
            NfcObserveModeHelper.isForceEnabledAndCapturingAllFrames = false
        }
    }

    val observeModeState by NfcObserveModeHelper.state.collectAsState()
    val pollingFrames by NfcObserveModeHelper.pollingFrames.collectAsState()
    val timeZone = remember { TimeZone.currentSystemDefault() }

    LazyColumnWithScrollbar(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            if (!observeModeState.isSupported) {
                WarningCard {
                    Text(
                        "NFC Observe Mode is not currently supported or enabled on this device/controller."
                    )
                }
            } else {
                InfoCard {
                    Text(
                        "While on this screen, NFC Observe Mode is enabled from the tag's perspective " +
                            "and auto-transact inhibition is suppressed so all polling frames in the RF field " +
                            "are continuously captured and displayed below."
                    )
                }
            }
        }

        item {
            SegmentedItemList(title = "Observe Mode State") {
                SegmentedItemHeadingAndText(
                    heading = "Supported by Controller",
                    text = if (observeModeState.isSupported) "Yes" else "No"
                )
                SegmentedItemHeadingAndText(
                    heading = "Active on NFC Adapter",
                    text = if (observeModeState.isEnabledOnAdapter) "Yes (Observing RF Field)" else "No (Transacting / Disabled)"
                )
                SegmentedItemHeadingAndText(
                    heading = "Enabled in Settings",
                    text = if (observeModeState.isEnabledInSettings) "Yes" else "No"
                )
                SegmentedItemHeadingAndText(
                    heading = "Inhibited",
                    text = when {
                        observeModeState.isExplicitlyInhibited -> "Yes (Explicitly inhibited)"
                        observeModeState.isInhibitedForTransaction -> "Yes (Temporarily inhibited for transaction)"
                        else -> "No"
                    }
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "Enable Observe Mode & capture all frames",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Switch(
                    checked = forceObserveMode,
                    onCheckedChange = { forceObserveMode = it }
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Observed Polling Frames (${pollingFrames.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    enabled = pollingFrames.isNotEmpty(),
                    onClick = {
                        NfcObserveModeHelper.clearPollingFrames()
                        showToast("Cleared observed polling frames")
                    }
                ) {
                    Text("Clear")
                }
            }
        }

        if (pollingFrames.isEmpty()) {
            item {
                SegmentedItemList {
                    SegmentedItemCenteredText(
                        text = "No polling frames observed in the field yet. " +
                            "Hold the device near an NFC reader to inspect its polling loop."
                    )
                }
            }
        } else {
            val reversedFrames = pollingFrames.asReversed()
            items(
                count = reversedFrames.size,
                key = { index -> reversedFrames[index].id }
            ) { index ->
                val frame = reversedFrames[index]
                PollingFrameCard(
                    frame = frame,
                    timeZone = timeZone
                )
            }
        }
    }
}

@Composable
private fun PollingFrameCard(
    frame: NfcPollingFrame,
    timeZone: TimeZone,
) {
    val typeLabel = when (frame.type) {
        NfcPollingFrameType.A -> "NFC-A (Type A)"
        NfcPollingFrameType.B -> "NFC-B (Type B)"
        NfcPollingFrameType.F -> "NFC-F (FeliCa)"
        NfcPollingFrameType.V -> "NFC-V (ISO 15693)"
        NfcPollingFrameType.REMOTE_FIELD_ON -> "Remote Field ON"
        NfcPollingFrameType.REMOTE_FIELD_OFF -> "Remote Field OFF"
        NfcPollingFrameType.UNKNOWN -> "Unknown"
    }
    val hexData = if (frame.data.size == 0) "(empty)" else frame.data.toHex()
    val parsedEcp = remember(frame.data) { frame.parseEcp() }
    val observedTimeStr = remember(frame.observedAt, timeZone) {
        frame.observedAt.toLocalDateTime(timeZone).formatLocalized()
    }

    SegmentedItemList(
        title = "Frame #${frame.id} • $typeLabel"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Data: $hexData",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold
            )
            if (parsedEcp != null) {
                Text(
                    text = parsedEcp.terminalDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (parsedEcp.isIdentityReader) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ECP TCI: ${parsedEcp.tciHex}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Gain: ${frame.vendorSpecificGain} • " +
                    "Controller Timestamp: ${frame.timestampMicros} µs" +
                    if (frame.triggeredAutoTransact) " • Auto-Transact Triggered" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Observed at: $observedTimeStr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
