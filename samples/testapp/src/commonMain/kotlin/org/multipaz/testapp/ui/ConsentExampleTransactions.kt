package org.multipaz.testapp.ui

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tstr
import org.multipaz.credential.Credential
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionProtocol

// Transaction types used only by the consent prompt examples in this app. They are not
// standardized and exist to show that the consent prompt's extension points work for any
// transaction a wallet supports, not to define a wire format.

/**
 * Buying a transit ticket with a payment credential: the user picks one of several fares.
 */
object TransitFareTransaction : TransactionType<TransitFareTransaction.Payload>(
    displayName = "Transit fare",
    identifier = "org.multipaz.testapp.transit_fare",
) {
    /** One fare the user can pick. */
    data class Fare(
        val id: String,
        val name: String,
        val validity: String,
        val amount: Double,
    )

    /** What the transit operator offers. */
    data class Payload(
        val operator: String,
        val zones: String,
        val currency: String,
        val fares: List<Fare>,
    )

    /** The fare the user picked, returned in the response. */
    data class UserInput(val fareId: String) : TransactionUserInput() {
        override fun generateMdocResponseElements(
            transactionData: TransactionData<*>,
            credential: Credential
        ): Map<String, DataItem> = mapOf("fare" to Tstr(fareId))

        override fun generateSdJwtResponseClaims(
            transactionData: TransactionData<*>,
            credential: Credential
        ): Map<String, JsonElement> = mapOf("fare" to JsonPrimitive(fareId))
    }

    /** The fare shown as selected when the user has not picked one. */
    fun defaultFare(payload: Payload): Fare = payload.fares.first()

    // Without user input the first fare is the one shown as selected, so it is also the one returned.
    override suspend fun generateMdocResponseElements(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, DataItem> = super.generateMdocResponseElements(
        transactionData,
        credential,
        userInput ?: UserInput(defaultFare(transactionData.payload).id),
        docRequestId
    )

    override suspend fun generateSdJwtResponseClaims(
        transactionData: TransactionData<Payload>,
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int?
    ): Map<String, JsonElement> = super.generateSdJwtResponseClaims(
        transactionData,
        credential,
        userInput ?: UserInput(defaultFare(transactionData.payload).id),
        docRequestId
    )

    val sampleData = Payload(
        operator = "Utopia Metro",
        zones = "Zones 1–2",
        currency = "USD",
        fares = listOf(
            Fare(id = "single", name = "Single ride", validity = "Valid for 90 minutes", amount = 2.75),
            Fare(id = "day", name = "Day pass", validity = "Valid until midnight", amount = 7.50),
            Fare(id = "week", name = "7-day pass", validity = "Valid for 7 days", amount = 25.00),
        ),
    )
}

/**
 * Picking up a prescription at a pharmacy, after proving who you are with an mDL.
 */
object PrescriptionPickupTransaction : TransactionType<PrescriptionPickupTransaction.Payload>(
    displayName = "Prescription pickup",
    identifier = "org.multipaz.testapp.prescription_pickup",
) {
    /** One prescribed medication. */
    data class Item(
        val medication: String,
        val strength: String,
        val quantity: String,
        val directions: String,
        val prescriber: String,
        val rxNumber: String,
        val refillsLeft: Int,
    )

    /** What the pharmacy is about to hand over. */
    data class Payload(
        val pharmacy: String,
        val pharmacyAddress: String,
        val items: List<Item>,
    )

    val sampleData = Payload(
        pharmacy = "Utopia Pharmacy",
        pharmacyAddress = "12 Harbor Street, Utopia City",
        items = listOf(
            Item(
                medication = "Amoxicillin",
                strength = "500 mg capsules",
                quantity = "21 capsules",
                directions = "1 capsule 3 times a day for 7 days",
                prescriber = "Dr. Ada Moreno",
                rxNumber = "RX-4471-209",
                refillsLeft = 0,
            ),
        ),
    )
}

/**
 * Transaction data for one of the example types, as if it arrived in an OpenID4VP request.
 *
 * The raw bytes only feed the transaction hash in the response; the examples never parse them.
 */
internal fun <PayloadT : Any> exampleTransactionData(
    type: TransactionType<PayloadT>,
    payload: PayloadT,
): TransactionData<PayloadT> = TransactionData(
    type = type,
    payload = payload,
    protocol = TransactionProtocol.OPENID4VP,
    rawBytes = ByteString("${type.identifier}:$payload".encodeToByteArray()),
)
