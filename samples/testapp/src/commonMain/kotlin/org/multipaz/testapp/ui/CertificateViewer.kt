package org.multipaz.testapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain

private val PAGER_INDICATOR_HEIGHT = 30.dp
private val PAGER_INDICATOR_PADDING = 8.dp

/**
 * Shows a X.509 certificate chain.
 *
 * @param modifier a [Modifier].
 * @param x509CertChain the [X509CertChain] to show.
 */
@Composable
fun CertificateViewer(
    modifier: Modifier = Modifier,
    x509CertChain: X509CertChain,
) {
    CertificateViewerInternal(modifier, x509CertChain.certificates)
}

/**
 * Shows a X.509 certificate.
 *
 * @param modifier a [Modifier].
 * @param x509Cert the [X509Cert] to show.
 */
@Composable
fun CertificateViewer(
    modifier: Modifier = Modifier,
    x509Cert: X509Cert,
) {
    CertificateViewerInternal(modifier, listOf(x509Cert))
}

@Composable
private fun CertificateViewerInternal(
    modifier: Modifier,
    certificates: List<X509Cert>
) {
    check(certificates.isNotEmpty())
    Box(
        modifier = modifier.fillMaxHeight().padding(10.dp)
    ) {
        val listSize = certificates.size
        val pagerState = rememberPagerState(pageCount = { listSize })

        Column(
            modifier = Modifier.then(
                if (listSize > 1)
                    Modifier.padding(bottom = PAGER_INDICATOR_HEIGHT + PAGER_INDICATOR_PADDING)
                else // No pager, no padding.
                    Modifier
            )
        ) {
            HorizontalPager(
                state = pagerState,
            ) { page ->
                val scrollState = rememberScrollState()
                X509CertViewer(
                    modifier = Modifier.verticalScroll(scrollState),
                    certificate = certificates[page]
                )
            }
        }

        if (listSize > 1) { // Don't show pager for single cert on the list.
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .wrapContentHeight()
                    .fillMaxWidth()
                    .height(PAGER_INDICATOR_HEIGHT)
                    .padding(PAGER_INDICATOR_PADDING),
            ) {
                repeat(pagerState.pageCount) { iteration ->
                    val color =
                        if (pagerState.currentPage == iteration) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = .2f)
                        }
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(8.dp)
                    )
                }
            }
        }
    }
}

