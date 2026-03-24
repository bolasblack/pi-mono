# pi-claude Development Rules

## TDD

All changes must follow red/green TDD: write or update a failing test first (red), then implement the fix or feature to make it pass (green).

## CLI Help Text

Any change to CLI flags, environment variables, stdin handling, or flag validation must include a corresponding update to `printCompatHelp()` in `src/index.ts`.

The help text must document behavioral details, not just flag names. For example:
- Whether a flag can be specified multiple times (e.g. `--settings`)
- Mutual exclusivity constraints (e.g. `--system-prompt` vs `--system-prompt-file`)
- Required combinations (e.g. `--fork-session` requires `--resume <id>`)
- Default values and environment variable fallbacks (e.g. `ANTHROPIC_MODEL`)

After updating help text, rebuild with `bun run build` and verify with `node dist/pi-claude -h`.

## Build

The CLI is bundled with `bun build --target=node`. The dist binary runs under Node, not Bun.

Do not use `import.meta.main` — it is Bun-only and behaves inconsistently across platforms. The entry point (`src/index.ts`) calls `run()` unconditionally.
