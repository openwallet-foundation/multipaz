package org.multipaz.utopia.knowntypes

import org.multipaz.doctypes.localization.GeneratedStringKeys
import org.multipaz.doctypes.localization.LocalizedStrings
import org.multipaz.documenttype.MultiDocumentCannedRequest
import org.multipaz.documenttype.knowntypes.wellKnownMultipleDocumentRequests

val wellKnownMultipleDocumentRequests = buildList {
    addAll(wellKnownMultipleDocumentRequests)
    add(MultiDocumentCannedRequest(
        id = "payment-and-age-over-18",
        displayName = LocalizedStrings.getString(GeneratedStringKeys.MULTI_REQUEST_PAYMENT_AND_AGE_OVER_18),
        dcqlString = """
            {
              "credentials": [
                {
                    "id": "over18",
                    "format": "mso_mdoc",
                    "meta": {
                        "doctype_value": "org.iso.18013.5.1.mDL"
                    },
                    "claims": [
                        {
                            "path": [
                                "org.iso.18013.5.1",
                                "age_over_18"
                            ],
                            "intent_to_retain": false
                        },
                        {
                            "path": [
                                "org.iso.18013.5.1",
                                "portrait"
                            ],
                            "intent_to_retain": false
                        }
                    ]
                },
                {
                    "id": "payment",
                    "format": "mso_mdoc",
                    "meta": {
                        "doctype_value": "org.multipaz.payment.sca.1"
                    },
                    "claims": [
                        {
                            "path": [
                                "org.multipaz.payment.sca.1",
                                "issuer_name"
                            ],
                            "intent_to_retain": false
                        },
                        {
                            "path": [
                                "org.multipaz.payment.sca.1",
                                "payment_instrument_id"
                            ],
                            "intent_to_retain": false
                        },
                        {
                            "path": [
                                "org.multipaz.payment.sca.1",
                                "masked_account_reference"
                            ],
                            "intent_to_retain": false
                        },
                        {
                            "path": [
                                "org.multipaz.payment.sca.1",
                                "holder_name"
                            ],
                            "intent_to_retain": false
                        },
                        {
                            "path": [
                                "org.multipaz.payment.sca.1",
                                "issue_date"
                            ],
                            "intent_to_retain": false
                        },
                        {
                            "path": [
                                "org.multipaz.payment.sca.1",
                                "expiry_date"
                            ],
                            "intent_to_retain": false
                        }
                    ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "over18", "payment" ]
                  ]
                }
              ]
            }
        """.trimIndent(),
        transactionData = """
            [
                {
                    "type": "urn:eudi:sca:payment:1",
                    "credential_ids": [
                        "payment"
                    ],
                    "payload": {
                        "transaction_id": "3AD99006-6E0D-4D07-AE75-5DAEF0FE21D9",
                        "amount": 123.25,
                        "currency": "USD",
                        "payee": {
                            "id": "01234",
                            "name": "Linux Foundation"
                        }
                    }
                }
            ]
        """.trimIndent()
    ))
    add(MultiDocumentCannedRequest(
        id = "transaction-and-age-over-18",
        displayName = LocalizedStrings.getString(GeneratedStringKeys.MULTI_REQUEST_AGE_OVER_18_MDL_OR_PID),
        dcqlString = """
            {
              "credentials": [
                {
                    "id": "mdl",
                    "format": "mso_mdoc",
                    "meta": {
                        "doctype_value": "org.iso.18013.5.1.mDL"
                    },
                    "claims": [
                        {
                            "path": [
                                "org.iso.18013.5.1",
                                "age_over_18"
                            ],
                            "intent_to_retain": false,
                            "id": "over18"
                        }
                    ]
                },
                {
                    "id": "pid",
                    "format": "mso_mdoc",
                    "meta": {
                        "doctype_value": "org.iso.23220.photoid.1"
                    },
                    "claims": [
                        {
                            "path": [
                                "org.iso.23220.1",
                                "age_over_18"
                            ],
                            "intent_to_retain": false,
                            "id": "over18"
                        }
                    ]
                }
              ],
              "credential_sets": [
                {
                  "options": [
                    [ "mdl" ],
                    [ "pid" ]
                  ]
                }
              ]
            }
        """.trimIndent(),
        transactionData = """
            [
                {
                    "type": "org.multipaz.transaction.ping",
                    "credential_ids": [
                        "mdl"
                    ],
                    "string": "mdl text",
                    "blob": "AQID"
                },
                {
                    "type": "org.multipaz.transaction.ping",
                    "credential_ids": [
                        "pid"
                    ],
                    "string": "pid text",
                    "blob": "AQJD"
                }
            ]
        """.trimIndent()
    ))
}