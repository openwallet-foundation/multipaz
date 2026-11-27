package org.multipaz.documenttype

import kotlinx.serialization.json.JsonElement

/**
 * What transaction data adds to an SD-JWT presentation.
 *
 * @property claims claims to include in the Key Binding JWT payload.
 * @property disclosures disclosures that follow the Key Binding JWT, which makes it a Key Binding
 *   SD-JWT and the presentation a Delegated SD-JWT (draft-ietf-oauth-delegate-sd-jwt §5.1.1):
 *   `<SD-JWT>~~<KB-JWT>~<Disclosure 1>~...~<Disclosure M>~`. Empty for an ordinary SD-JWT+KB.
 */
data class SdJwtTransactionResponse(
    val claims: Map<String, JsonElement>,
    val disclosures: List<String> = emptyList(),
)
