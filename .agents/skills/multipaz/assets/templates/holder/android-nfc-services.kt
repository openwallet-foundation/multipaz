// This file is deliberately a review checklist, not a paste-ready implementation.
// A CombinedNfcService requires three version-matched service implementations and a
// host-APDU XML resource. Supplying only the combined service produces unusable code.
//
// Before implementing Android NFC:
// 1. Read references/upstream-source.md and identify compatible source evidence.
// 2. Inspect the complete upstream set at the pinned commit:
//    samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/
//      TestAppCombinedNfcService.kt
//      TestAppMdocNdefService.kt
//      TestAppMdocNfcV2Service.kt
//      TestAppMdocNfcDataTransferService.kt
// 3. Adapt its app initialization, PresentmentSource, prompt model, transport
//    options, and consent lifecycle to the target app; do not copy test-app globals.
// 4. Add a matching res/xml/combined_nfc_service.xml and manifest service entry.
// 5. Keep all resulting code in androidMain or the Android application module.
//
// Multipaz NFC credential presentation is currently Android-only.
