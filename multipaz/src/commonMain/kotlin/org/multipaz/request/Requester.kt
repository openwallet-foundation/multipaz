package org.multipaz.request

import org.multipaz.crypto.X509CertChain

/**
 * Details about the entity requesting data.
 *
 * @property certChain if the requester signed the request and provided a certificate chain.
 * @property appId if this is a request from a local application, this contains the app identifier
 *   for example `com.example.app` on Android or `<teamId>.<bundleId>` on iOS.
 * @property origin the origin of the requester, if known. If this calling application is a trusted web browser
 *   this may be a website origin such as https://www.example.com. Otherwise this is set to the origin
 *   for the native application, for example on Android this will be of the form
 *   "android:apk-key-hash:<sha256_hash-of-apk-signing-cert>".
 */
data class Requester(
    val certChain: X509CertChain? = null,
    val appId: String? = null,
    val origin: String? = null,
)