# Trust And Certificates

## Use this when

- building verifier trust decisions
- generating sample trust chains
- debugging issuer, reader, or document signer certificate problems

## Pinned upstream anchors

- Trust UI and models: `multipaz-compose/src/commonMain/kotlin/org/multipaz/compose/trustmanagement`
- Trust manager sample usage: `samples/SwiftTestApp/IdentityDocumentProviderExtension/DocumentProviderExtension.swift`
- IACA and document signer sample generation: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/ConsentPromptScreen.kt`
- CLI tool for certificate utilities: `multipazctl`

## Distinctions to preserve

- issuer authority
- document signer
- reader or verifier identity
- trust anchor and certificate chain
- IACA certificate and document signer certificate
- signature verification and credential validity
- user consent and session freshness

## Hard rules

- Never commit private keys.
- Never disable TLS or trust verification just to make a sample work.
- Never treat successful parsing as proof of trust.
- Do not log complete credentials or sensitive claims unless the task explicitly requires controlled debugging and the user accepts the risk.
