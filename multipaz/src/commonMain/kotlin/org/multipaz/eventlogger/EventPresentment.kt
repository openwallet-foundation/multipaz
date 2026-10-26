package org.multipaz.eventlogger

import kotlin.time.Instant
import org.multipaz.cbor.DataItem
import org.multipaz.presentment.PresentmentTelemetry

/**
 * Base class for events recorded in [EventLogger] related to credential presentment.
 *
 * @property presentmentData a [EventPresentmentData] with details about the presentment, or `null` if presentment failed before response generation.
 * @property telemetry anonymized telemetry collected during the presentment session.
 */
sealed class EventPresentment(
    override val identifier: String = "",
    override val timestamp: Instant = Instant.DISTANT_PAST,
    override val appData: Map<String, DataItem> = emptyMap(),
    open val presentmentData: EventPresentmentData? = null,
    open val telemetry: PresentmentTelemetry? = null,
): Event(identifier, timestamp, appData)