# OpenID4VCI Integration Checklist

Reference: [references/openid4vci.md](../../../references/openid4vci.md)
Source provenance: [references/upstream-source.md](../../../references/upstream-source.md)

- Confirm whether the task is wallet-side provisioning, a local test backend, or a real issuer integration.
- If you need a sample anchor, inspect `samples/SwiftTestApp/SwiftTestApp/ProvisioningSupport.swift`.
- If you need server-side logic, inspect `multipaz-backend-server/src/main/java/org/multipaz/backend/openid4vci/OpenID4VCIBackendImpl.kt`.
- Do not embed production assertion or attestation keys in app code.
- Keep redirect handling, deep links, and app links version-matched to the target project.
- Re-run dependency inspection after edits.
