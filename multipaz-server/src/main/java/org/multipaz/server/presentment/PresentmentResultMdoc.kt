package org.multipaz.server.presentment

import org.multipaz.cbor.DataItem
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.trustmanagement.TrustResult

/**
 * Verification result for a single ISO mdoc document within a presentation.
 *
 * @property mdocDocument the verified mdoc document containing namespaces and data elements.
 * @property transactionResults verified transaction data responses keyed by namespace and
 *  data element name, or `null` if no transaction response was sent. Note that transaction
 *  data in the request is always verified when [PresentmentRecord.verify] is called.
 */
data class PresentmentResultMdoc(
    override val id: String?,
    override val trustResult: TrustResult,
    val mdocDocument: MdocDocument,
    val transactionResults: Map<String, Map<String, DataItem>>?
): PresentmentResult()