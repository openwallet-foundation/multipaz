# Upstream Source Provenance

The source paths named throughout this skill (for example,
`samples/testapp/...` and `multipaz-compose/...`) are relative to the
[Multipaz upstream repository at commit c59534574ea9ed5b08e572f61f884e9938bb6300](https://github.com/openwallet-foundation/multipaz/tree/c59534574ea9ed5b08e572f61f884e9938bb6300).
They are not assumed to exist in every project using this skill.

## How to use upstream anchors

1. Inspect the target project first. Its dependencies, source sets, and version are
   the evidence for changes to that project.
2. If the target project is a checkout of Multipaz at the pinned commit, use the
   paths directly.
3. If it is a different project, treat the paths as upstream examples only. Check
   the target's resolved Multipaz version and its available source or
   version-matched samples before copying an API, manifest stanza, or dependency.
4. If no version-matched source is available, state that limitation and do not
   present a pinned-upstream API as verified for the target version. Ask for an
   upstream checkout or version-specific source when it is needed to implement a
   change safely.

The upstream `main` branch can change after this skill is published. Refresh this
file and re-validate every referenced path together when deliberately updating the
source baseline.

## Skill baseline

- Skill version: `1.0.0` (`metadata.version` in `SKILL.md`).
- Pinned commit: `c59534574ea9ed5b08e572f61f884e9938bb6300` (2026-10-08),
  which is 20 commits ahead of release `0.101.0` (2026-09-10) and 138 commits
  ahead of `0.100.0`.
- When the pin moves: update this file and `compatibility` in `SKILL.md`,
  re-check every pinned path, re-check the module map in
  [architecture.md](architecture.md), bump `metadata.version`, and bump the
  catalog `version` if this skill is published over HTTP.
