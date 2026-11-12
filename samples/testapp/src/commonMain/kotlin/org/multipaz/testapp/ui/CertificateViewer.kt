package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain

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
    X509CertViewer(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        certChain = x509CertChain,
    )
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
    CertificateViewer(
        modifier = modifier,
        x509CertChain = X509CertChain(listOf(x509Cert)),
    )
}

