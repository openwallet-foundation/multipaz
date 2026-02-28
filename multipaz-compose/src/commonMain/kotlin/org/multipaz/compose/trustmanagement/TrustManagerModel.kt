package org.multipaz.compose.trustmanagement

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.multipaz.asn1.OID
import org.multipaz.mdoc.rical.SignedRical
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.trust_entry_details_certificate
import org.multipaz.multipaz_compose.generated.resources.trust_entry_details_rical
import org.multipaz.multipaz_compose.generated.resources.trust_entry_details_vical
import org.multipaz.multipaz_compose.generated.resources.trust_entry_rical_fallback_name
import org.multipaz.multipaz_compose.generated.resources.trust_entry_vical_fallback_name
import org.multipaz.trustmanagement.TrustEntry
import org.multipaz.trustmanagement.TrustEntryRical
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustManager

/**
 * A presentation model that bridges a [TrustManager] with UI components.
 *
 * This class observes the underlying [TrustManager] for changes and exposes the
 * current list of trust entries as a reactive [StateFlow] of [TrustEntryInfo] objects.
 * It handles the parsing of raw trust entries into UI-friendly data structures.
 *
 * @property trustManager The underlying [TrustManager] being observed.
 */
class TrustManagerModel private constructor(
    val trustManager: TrustManager
) {
    /**
     * The coroutine scope used for observing [TrustManager] events.
     * Uses a [SupervisorJob] to ensure that an exception during a single flow update
     * does not cancel the entire observation scope.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _trustManagerInfos: MutableStateFlow<List<TrustEntryInfo>> = MutableStateFlow(emptyList())

    /**
     * A [StateFlow] containing the current, parsed list of trust entries.
     * UI components should collect this flow to stay up-to-date with the [TrustManager] state.
     */
    val trustManagerInfos: StateFlow<List<TrustEntryInfo>> = _trustManagerInfos.asStateFlow()

    /**
     * Fetches the latest entries from the [TrustManager], parses their contents
     * (such as VICALs and RICALs), and updates the [_trustManagerInfos] state.
     */
    private suspend fun updateTrustManagerInfos() {
        val infos = mutableListOf<TrustEntryInfo>()
        trustManager.getEntries().forEach { entry ->
            val signedVical = if (entry is TrustEntryVical) {
                SignedVical.parse(
                    encodedSignedVical = entry.encodedSignedVical.toByteArray(),
                    disableSignatureVerification = true
                )
            } else {
                null
            }
            val signedRical = if (entry is TrustEntryRical) {
                SignedRical.parse(
                    encodedSignedRical = entry.encodedSignedRical.toByteArray(),
                    disableSignatureVerification = true
                )
            } else {
                null
            }
            infos.add(TrustEntryInfo(
                entry = entry,
                manager = trustManager,
                signedVical = signedVical,
                signedRical = signedRical
            ))
        }
        _trustManagerInfos.value = infos
    }

    /**
     * Initializes the model by performing an initial state fetch and subscribing
     * to future updates from the [TrustManager]'s event flow.
     */
    private suspend fun initialize() {
        updateTrustManagerInfos()
        trustManager.eventFlow
            .onEach { updateTrustManagerInfos() }
            .launchIn(scope)
    }

    companion object {

        /**
         * Creates and initializes a [TrustManagerModel].
         *
         * This method suspends until the initial state is fully loaded from the
         * [trustManager] and the observation flow is established.
         *
         * @param trustManager The [TrustManager] to bind to this model.
         * @return A fully initialized [TrustManagerModel].
         */
        suspend fun create(
            trustManager: TrustManager
        ): TrustManagerModel {
            val trustManagerModel = TrustManagerModel(trustManager)
            trustManagerModel.initialize()
            return trustManagerModel
        }
    }
}

/**
 * Generates a descriptive fallback name for a [TrustEntry] based on its specific type
 * and underlying data (e.g., Common Name for an X.509 cert, Provider for a VICAL/RICAL).
 */
@Composable
fun TrustEntry.getFallbackName(
    signedVical: SignedVical?,
    signedRical: SignedRical?
): String {
    when (this) {
        is TrustEntryX509Cert -> {
            val subject = certificate.subject
            val commonName = subject.components[OID.COMMON_NAME.oid]
            if (commonName != null) {
                return commonName.value
            }
            return subject.name
        }
        is TrustEntryVical -> {
            return stringResource(
                Res.string.trust_entry_vical_fallback_name,
                signedVical!!.vical.vicalProvider
            )
        }
        is TrustEntryRical -> {
            return stringResource(
                Res.string.trust_entry_rical_fallback_name,
                signedRical!!.rical.provider
            )
        }
    }
}

/**
 * Generates a brief detail string describing the contents of a [TrustEntry],
 * such as the number of certificates contained within a VICAL or RICAL.
 */
@Composable
fun TrustEntry.getDetails(
    signedVical: SignedVical?,
    signedRical: SignedRical?
): String {
    when (this) {
        is TrustEntryX509Cert -> {
            return stringResource(Res.string.trust_entry_details_certificate)
        }
        is TrustEntryVical -> {
            val size = signedVical!!.vical.certificateInfos.size
            return pluralStringResource(Res.plurals.trust_entry_details_vical, size, size)
        }
        is TrustEntryRical -> {
            val size = signedRical!!.rical.certificateInfos.size
            return pluralStringResource(Res.plurals.trust_entry_details_rical, size, size)
        }
    }
}