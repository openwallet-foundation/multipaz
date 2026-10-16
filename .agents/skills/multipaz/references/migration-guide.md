# Migration Guide

## Use this when

- updating an older Multipaz integration
- explaining breakage between versions
- separating feature work from upgrade work

## Rules

- Inspect the currently used Multipaz version first.
- Prefer version-matched source and samples.
- Do not upgrade automatically just because a newer version exists.
- Keep upgrade work isolated from unrelated dependency churn.
- Re-check platform capability claims after the upgrade, especially around Android-only NFC support.

## Upgrade workflow

1. Inspect current dependencies and target version.
2. Compare current module names to the selected target version's module set or the pinned upstream module map.
3. Update only the necessary Multipaz coordinates or project dependencies.
4. Rebuild Android and iOS targets affected by the change.
5. Re-check Multipaz version alignment and source-set placement in the changed files, then run relevant existing tests. Use resolved dependency reports when declarations alone do not explain a mismatch; report the build and test results with the version evidence.
