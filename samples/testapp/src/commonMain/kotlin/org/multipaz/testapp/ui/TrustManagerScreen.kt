package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.compose.datetime.formattedDate
import org.multipaz.trustmanagement.CompositeTrustManager
import org.multipaz.trustmanagement.RicalTrustManager
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.trustmanagement.VicalTrustManager

@Composable
fun TrustManagerScreen(
    compositeTrustManager: CompositeTrustManager,
    onViewTrustPoint: (trustPoint: TrustPoint) -> Unit,
    showToast: (message: String) -> Unit,
) {
    val trustManagerIdToTrustPoints = remember { mutableStateOf<Map<String, List<TrustPoint>>?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(true) {
        coroutineScope.launch {
            val map = mutableMapOf<String, List<TrustPoint>>()
            for (tm in compositeTrustManager.trustManagers) {
                map[tm.identifier] = tm.getTrustPoints()
            }
            trustManagerIdToTrustPoints.value = map
        }
    }

    LazyColumn(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        trustManagerIdToTrustPoints.value?.let {
            for (tm in compositeTrustManager.trustManagers) {
                item {
                    when (tm) {
                        is VicalTrustManager -> {
                            Text(text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("VICAL")
                                }
                                append(" by ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(tm.signedVical.vical.vicalProvider)
                                }
                                append(" at ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(formattedDate(tm.signedVical.vical.date))
                                }
                            })
                        }
                        is RicalTrustManager -> {
                            Text(text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("RICAL")
                                }
                                append(" by ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(tm.signedRical.rical.provider)
                                }
                                append(" at ")
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(formattedDate(tm.signedRical.rical.date))
                                }
                            })
                        }
                        is TrustManagerLocal -> {
                            Text(text = buildAnnotatedString {
                                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("Local Storage (${tm.identifier})")
                                }
                            })
                        }
                    }
                }
                it[tm.identifier]!!.forEach { trustPoint ->
                    item {
                        TextButton(
                            onClick = { onViewTrustPoint(trustPoint) }
                        ) {
                            Text(text = trustPoint.metadata.displayName ?: trustPoint.certificate.subject.name)
                        }
                    }
                }
            }
        }

    }
}