package org.multipaz.provisioning

import kotlinx.io.bytestring.ByteString

/**
 * Provisioned credentials and optional credential metadata.
 *
 * @property serializedCredentials serialized credential data.
 * @property display updated credential name and card art if any.
 */
data class Credentials(
    val serializedCredentials: List<ByteString>,
    val display: Display?
)