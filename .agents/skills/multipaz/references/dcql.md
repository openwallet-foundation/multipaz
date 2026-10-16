# DCQL

## Use this when

- creating verifier requests
- converting canned requests into DCQL
- debugging request mismatch issues

## Pinned upstream anchors

- DCQL examples in the test app: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/ConsentPromptScreen.kt`
- Request construction screen: `samples/testapp/src/commonMain/kotlin/org/multipaz/testapp/ui/DcRequestScreen.kt`
- Android matcher implementation: `multipaz-dcapi/src/androidMain/matcher`
- Request template: [assets/templates/verifier/dcql-request.json](../assets/templates/verifier/dcql-request.json),
  a valid DCQL skeleton. Fill `id`, `format`, and `meta` from a verified document
  type before use; the template intentionally contains no comment keys because
  `_comment` is not a DCQL member.

## Guidance

- Treat DCQL as a query language layered into verifier or presentment protocols.
- Verify the requested credential format and doc type metadata before generating a query.
- Keep examples version-matched to the current request model.
