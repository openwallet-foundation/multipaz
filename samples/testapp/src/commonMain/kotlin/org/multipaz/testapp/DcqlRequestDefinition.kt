package org.multipaz.testapp

/**
 * A presentation request that is defined in terms of OpenID4VP DCQL and transaction data.
 *
 * @property dcql DCQL query, this gives the list of needed credentials and
 *   which claims are needed from each credential
 * @property transactionData transaction data in OpenID4VP JSON format (before Base64Url
 *   encoding), note that credentialId uses credential ids used in DCQL
 */
class DcqlRequestDefinition(
    val dcql: String,
    val transactionData: List<String>? = null
) {
    companion object
}