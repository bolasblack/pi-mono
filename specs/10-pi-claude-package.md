# Spec: pi-claude Package

## Overview

Extracted Claude Code CLI compatibility into a standalone package at
`packages/pi-claude`. Replaces the inline `cli-claude-compat.ts` that
previously lived in `packages/coding-agent/src/`.

## Motivation

The old `cli-claude-compat.ts` was tightly coupled to the coding-agent
package and duplicated resource loading logic. Moving to a separate package
gives it its own test suite, build pipeline, and clear API boundary.

## Package Structure

```
packages/pi-claude/
  src/
    index.ts              CLI entry point (#!/usr/bin/env node)
    extension.ts          pi extension entry (re-exports extension/index.ts)
    claude-helpers.ts     Claude-specific utilities
    ui-helpers.ts         UI utilities
    cli/
      types.ts            CompatArgs, flag type definitions
      flags.ts            Supported/unsupported flag tables
      parse.ts            parseCompatArgs() — argv to CompatArgs
      translate.ts        translateToPiArgv() — CompatArgs to pi argv
      validate.ts         validateCompat() — conflict/constraint checks
    extension/
      index.ts            Extension implementation
      claude-hooks.ts     Claude Code hook compatibility
      skill-hooks.ts      Skill hook integration
      hook-runner.ts      Hook execution engine
      ...
  dist/
    pi-claude             Bundled CLI binary (bun build --target=node)
```

## Build

- Bundled with `bun build --target=node` — runs under Node, not Bun.
- `import.meta.main` is not used (Bun-only, inconsistent across platforms).
- The entry point calls `run()` unconditionally.

## CLI

`pi-claude` accepts Claude Code flags, translates them to pi equivalents,
injects the pi-claude extension, and spawns `pi`.

### Supported flags

See `printCompatHelp()` in `src/index.ts` for the full list with behavioral
details (multi-use, mutual exclusivity, defaults, env fallbacks).

### Extension discovery

`resolveCompatExtensionFile()` searches for `extension.ts` relative to the
running module. Candidate paths cover both source (`src/cli/`) and bundled
(`dist/`) layouts.

### Environment variables

| Variable | Purpose |
|----------|---------|
| `ANTHROPIC_MODEL` | Default model when `--model` is not specified |
| `DISABLE_AUTO_COMPACT` | Disable automatic context compaction |
| `PI_CLAUDE_PI_BIN` | Path to pi binary (default: `pi`) |

## Obsoleted Files

Moved to `specs/obsoleted/`:
- `specs/02-claude-code-cli-compat.md` — original CLI compat spec
- `specs/05-disable-auto-compact.md` — DISABLE_AUTO_COMPACT spec
- `specs/09-cli-claude-compat-refactor.md` — refactor spec
- `packages/coding-agent/src/cli-claude-compat.ts` — old inline implementation

## Verification

1. `node dist/pi-claude -h` prints detailed help and exits.
2. `node dist/pi-claude -v` prints version and exits.
3. `node dist/pi-claude -p "hello"` spawns pi in print mode.
4. `bun test` in `packages/pi-claude/` passes all tests.
5. Extension is discovered correctly from both source and dist layouts.
