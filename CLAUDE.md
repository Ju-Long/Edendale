# CLAUDE.md

Edendale is a free, open-source video player and personal watch/review tracker.
Every platform lives on an independent branch and owns its complete native
implementation and delivery pipeline. There is no shared executable backend or
generated runtime bridge.

Use [README.md](README.md) for project context, [AGENTS.md](AGENTS.md) for the
complete branch, architecture, privacy, secret-handling, and verification
rules, and [DESIGN.md](DESIGN.md) for the universal design language. Follow
[MODEL.md](MODEL.md) only when the task prompt explicitly asks for it.

## Before working

1. Confirm the active branch.
2. Read that branch's README and inspect its actual toolchain files.
3. Keep changes and verification within the branch's owning platform.
4. Use the platform's documented native build and test commands.

## Non-negotiable boundaries

- Keep `main` documentation-only.
- Keep each platform's source, tests, dependencies, CI, packaging, secrets, and
  deployment isolated on its own branch.
- Never introduce a shared executable runtime or make one platform depend on
  another platform's branch.
- Never commit, log, or publish credentials.
- Keep device-specific library data local and separate from any portable watch
  state.
- Parse filenames locally before starting metadata enrichment.
- Require explicit user action before trailer playback.
- Keep the Web branch static, credential-free, analytics-free, and free of
  application persistence or backend behavior.
- Implement shared product behavior natively and test it independently on every
  affected platform branch.
- Do not add `TASKS.md`.
