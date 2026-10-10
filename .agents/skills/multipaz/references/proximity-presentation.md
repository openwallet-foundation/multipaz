# Proximity Presentation

## Separation of concerns

- Engagement method: QR or NFC tap
- Transport: BLE, NFC data transfer, or other negotiated transport
- Session establishment: creation of the presentment session and handover
- Credential retrieval and presentation: document selection, consent, trust checks, and response generation

Do not collapse these into one concept.

## Pinned upstream evidence

- Android QR and consent flow logic: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/ConsentPromptScreen.kt`
- Android NFC service wiring: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/TestAppCombinedNfcService.kt`
- Android NDEF and NFC v2 services: `TestAppMdocNdefService.kt`, `TestAppMdocNfcV2Service.kt`
- iOS QR presentment sample: `samples/SwiftTestApp/SwiftTestApp/Iso18013ProximityPresentmentScreen.swift`

## Platform support

| Capability | Android | iOS |
| --- | --- | --- |
| Multipaz NFC credential presentation | Supported | Not currently supported |
| QR-based proximity presentment | Verify in selected app/version | Verify in selected app/version |
| BLE-based proximity transport | Verify in selected app/version | Verify in selected app/version |

## Required rules

- NFC presentation is currently Android-only.
- Do not generate iOS NFC presentation code.
- BLE and QR support must be verified separately for the selected version and app architecture.
- NFC and BLE are not interchangeable.
- Protocol and session logic may be shared, but transport and engagement code stays platform specific.

## Implementation sequence

1. Choose the protocol and request model.
2. Choose the engagement mechanism.
3. Verify transport support in the current module set.
4. Keep shared document and consent logic in common code.
5. Keep Android NFC services, manifest, and lifecycle integration in Android code.
6. Keep iOS to supported alternatives such as QR, BLE-backed flows, browser, or Identity Document services.

## QR over BLE implementation notes

- `MdocProximityQrPresentment` creates the ephemeral device key, advertises the configured transports, encodes the `mdoc:` QR engagement, waits for the reader connection, and calls `Iso18013Presentment`.
- Gate QR generation on BLE readiness. In Compose apps using `multipaz-compose`, call `rememberBluetoothPermissionState()` and `rememberBluetoothEnabledState()` and show explicit actions to request permissions and enable Bluetooth before rendering the `MdocProximityQrPresentment` start button.
- Wire presentment consent using `Platform.promptModel` and `PromptDialogs(promptModel)`. On Android, use a `FragmentActivity`; otherwise `PresentmentActivity` may open and immediately cancel after the verifier request arrives.
- A typical holder QR setup advertises BLE using `MdocConnectionMethodBle(supportsPeripheralServerMode = true, supportsCentralClientMode = false, peripheralServerModeUuid = UUID.randomUUID(), centralClientModeUuid = null)`.
- For verifier compatibility, consider advertising both BLE roles using the same fresh UUID: one `MdocConnectionMethodBle` with central-client mode enabled and one with peripheral-server mode enabled.
- Enable L2CAP when both sides support it with `MdocTransportOptions(bleUseL2CAP = true, bleUseL2CAPInEngagement = true)`, but keep GATT fallback expectations in mind for verifier compatibility.
- Each generated QR should represent a fresh engagement/session. Avoid reusing old QR payloads after cancellation, completion, or timeout.
- QR display success is not end-to-end success. Evidence must include reader connection, holder consent, response transmission, and verifier-side validation.
- If the verifier connects but the holder reports `Error satisfying the request`, inspect whether the `DocumentStore` contains a matching `MdocCredential` for the requested `docType` and requested claims. A credential can match the docType but still lack requested claims. Compare against the Getting Started sample pattern: seed a local sample mDL and do not over-constrain QR presentment with incompatible preselected documents.
