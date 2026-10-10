# Multipaz Skill Evals

Use `scenarios.md` as a manual behavioral regression checklist for the skill.
These are not executable tests or proof that an implementation compiles.

## Fixtures

Run each scenario against a project that carries real Multipaz version evidence.
Pick the fixture that matches the scenario:

1. **Upstream checkout at the pin.** Clone
   https://github.com/openwallet-foundation/multipaz and check out the commit
   recorded in [../references/upstream-source.md](../references/upstream-source.md).
   Use for scenarios that inspect samples, module maps, NFC services, or other
   pinned upstream paths.
2. **Public consumer sample.** Clone
   https://github.com/openwallet-foundation/multipaz-samples and use
   `MultipazGettingStartedSample` (released `org.multipaz:*` dependencies on
   Android and iOS) or `MultipazWholesalePOS`. Use for scenarios that inspect a
   third-party project's version catalog, dependency style, source sets, and
   platform wiring.
3. **Your own target project.** Any Kotlin Multiplatform, Android, or iOS project
   with a discoverable Multipaz version in a version catalog, Gradle file, or
   lockfile.

Do not run a scenario against a project with no Multipaz dependency and then
record "nothing is configured" as a pass, except for the scenarios that test
exactly that (scenario 1 tests inspection of an unconfigured or partially
configured project).

## Rules

- Every scenario must stay grounded in the current repository and samples.
- NFC scenarios must preserve the Android-only support boundary.
- Treat any response that generates iOS NFC credential presentation code as a failure.
- If the project has no local or version-matched upstream source, a correct
  response must report that limitation rather than claim a sample path is present.
