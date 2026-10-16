# Verifier Workflows

## Use this when

- building verifier requests
- adding OpenID4VP request generation
- wiring trust and identity information for a requester
- constructing DCQL requests

## Pinned upstream anchors

- Verifier request examples and consent data generation: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/ConsentPromptScreen.kt`
- DC request examples and protocol variants: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/DcRequestScreen.kt`
- Verifier server resources: `multipaz-verifier-server/src/main/resources/resources/www`

## Guidance

- Separate verifier request construction from holder transport integration.
- Verify which request formats are implemented in the current version before offering them.
- Carry requester identity and trust data through the flow instead of treating a parsed request as trusted.
