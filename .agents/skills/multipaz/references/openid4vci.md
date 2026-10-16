# OpenID4VCI

## Use this when

- adding credential offer handling
- implementing redirect handling and wallet provisioning
- wiring backend assertions, attestations, or local test stubs

## Pinned upstream anchors

- Wallet-side sample support: `samples/SwiftTestApp/SwiftTestApp/ProvisioningSupport.swift`
- Compose provisioning UI: `multipaz-compose/src/commonMain/kotlin/org/multipaz/compose/provisioning/ProvisioningBottomSheet.kt`
- Backend implementation example: `multipaz-backend-server/src/main/java/org/multipaz/backend/openid4vci/OpenID4VCIBackendImpl.kt`
- KMP sample app structure: [`MultipazGettingStartedSample`](https://github.com/openwallet-foundation/multipaz-samples/tree/main/MultipazGettingStartedSample) in [`openwallet-foundation/multipaz-samples`](https://github.com/openwallet-foundation/multipaz-samples)
- Holder codelab flow: Multipaz Utopia Wholesale holder "obtaining a credential" — https://developer.multipaz.org/codelabs/utopia-wholesale-codelab/holder/obtaining-a-credential
- Issuer codelab flow: Multipaz Utopia Wholesale issuer setup — https://developer.multipaz.org/codelabs/utopia-wholesale-codelab/issuer
- Holder module: `multipaz`
- Issuer/server module: `multipaz-openid4vci`

## Guidance

- Inspect the current Multipaz version and the target project's redirect/deep-link setup before changing code.
- For holder apps, prefer the wallet-side provisioning APIs under `org.multipaz.provisioning.openid4vci` from the core `multipaz` artifact. Do not add the server-oriented `multipaz-openid4vci` artifact to a mobile shared module unless verified by the selected version's samples.
- When constructing a Ktor `HttpClient`, declare platform engines and bind them explicitly: Android uses `ktor-client-android`, iOS uses `ktor-client-darwin`. A build may compile with only `ktor-client-core` and then crash at runtime with `Failed to find HTTP client engine implementation`.
- On Android, call `org.multipaz.context.initializeApplication(applicationContext)` before using `Platform.storage`, `Platform.nonBackedUpStorage`, or `Platform.getSecureArea()`.
- Add `android.permission.INTERNET` before testing any real issuer.
- Authorization-code offers must open the OAuth authorization URL and feed the custom-scheme/app-link redirect back through `ProvisioningModel.provideAuthorizationResponse(AuthorizationResponse.OAuth(...))`.
- On iOS, browser-launched offer links require both `CFBundleURLTypes` registration for the offer scheme and a SwiftUI/UIKit URL callback that forwards the full URL string into the shared holder flow. Authorization-code challenges also require opening the OAuth URL and routing the redirect URL back to the suspended provisioning model by matching `state`. Android intent filters do not imply any iOS routing.
- Public Multipaz issuers reject arbitrary generated wallet attestations. Use a registered wallet backend, the version-matched sample test keys, or a locally configured issuer that trusts your test keys.
- Treat local or embedded backend keys as sample-only.
- Separate backend responsibilities from app code unless the task explicitly targets a local test stub.
- Do not treat the public sample and codelab code as a drop-in for every app. Use it to identify the required moving parts, then adapt source sets, manifest entries, client IDs, redirect URI, and trusted attestation material to the target project.

## Android holder checklist

Before declaring an Android OpenID4VCI holder flow complete, verify:

- `initializeApplication(applicationContext)` runs before wallet or document-store initialization.
- `android.permission.INTERNET` is present for issuer traffic.
- The activity can receive the credential-offer URI scheme, commonly `openid-credential-offer`.
- The app has a registered OAuth redirect scheme or app link matching the client metadata sent to the issuer.
- `onNewIntent` or equivalent dispatch passes both initial and subsequent intents to the offer/redirect handler.
- Authorization challenges open the browser and then call `provideAuthorizationResponse(AuthorizationResponse.OAuth(...))` with the returned redirect URL.
- Wallet attestation keys are trusted by the issuer. Public demo issuers require registered/sample trust material; random local keys are expected to fail.

## Validation

- Confirm the app accepts the expected offer URI schemes or HTTPS redirects.
- Verify platform Ktor engine dependencies are present before calling the flow complete. If possible, launch the Android app or iOS target enough to instantiate the OpenID4VCI holder path.
- For Android real-issuer tests, verify all of these on device: wallet storage initializes, the offer link fills the app, `Issue from offer` opens the browser, the issuer redirects back to the app, and a document appears in `DocumentStore`.
- For iOS real-issuer tests, verify the issuer website opens the app from Safari, the full `openid-credential-offer://...` URL reaches shared code, `Issue from offer` opens the OAuth authorization URL when required, and the OAuth redirect scheme is registered and handled.
- Verify secure-area and storage setup before debugging provisioning failures.
