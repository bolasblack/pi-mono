# Spec: Refactor cli-claude-compat to Use Main CLI Flow

## Overview

cli-claude-compat.ts was an independent CLI entry point that duplicated the
resource loading, extension handling, model resolution, and session creation
flow from main.ts. Extension features (registerFlag, registerCliArgsTransformer)
did not work in the compat CLI.

Refactored into a thin flag translation layer that delegates to main()'s flow
after pre-processing.

## Problem

- Extensions' `registerFlag` did not work in the compat CLI
- Extensions' `registerCliArgsTransformer` did not work in the compat CLI
- Two copies of the resource loading / session creation orchestration logic
- Claude Code model names like `claude-opus-4-6[1m]` were not recognized

## Solution

### main.ts: Add `--settings` support

Add `settings?: string[]` to `Args` and handle it in main.ts after
SettingsManager creation. Parses each entry as JSON string or file path,
applies via `settingsManager.applyOverrides()` in order (later overrides win).
Supports multiple `--settings` flags.

### cli-claude-compat.ts: Thin translation layer

1. Parses Claude Code flags
2. Pre-processes compat-only features:
   - `--system-prompt-file` / `--append-system-prompt-file` → read file to string
   - `--session-id UUID` → `--session UUID --session-mode auto`
   - `--resume target` → `--session <target>`
   - `--fork-session` combinations → resolve session, `--fork <path>`
   - `ANTHROPIC_MODEL` env → `--model` fallback
   - `DISABLE_AUTO_COMPACT` env → append `--settings` with compaction disabled
   - `--settings` → pass through (supports multiple)
3. Rewrites `claude-` model names to `anthropic/` provider format:
   - Strip thinking budget suffix: `claude-opus-4-6[1m]` → `claude-opus-4-6`
   - Add provider prefix: `claude-opus-4-6` → `anthropic/claude-opus-4-6`
   - Already prefixed or non-claude models: no change
4. Translates to pi CLI argv
5. Passes unknown flags through (extension flags)
6. Calls `main(argv)`

## Files Changed

- `packages/coding-agent/src/cli/args.ts` — add `settings` field + parsing + help
- `packages/coding-agent/src/main.ts` — handle `parsed.settings` (~10 lines)
- `packages/coding-agent/src/cli-claude-compat.ts` — replaced with thin wrapper

## Verification

1. `pi-claude --model anthropic/claude-sonnet-4-20250514` → same as `pi --model ...`
2. `pi-claude --model claude-opus-4-6[1m]` → resolves `anthropic/claude-opus-4-6`
3. Extension with `registerFlag("plan", ...)` → `pi-claude --plan` works
4. Extension with `registerCliArgsTransformer(...)` → transformer applied
5. `pi-claude --settings '{"compaction":{"enabled":false}}'` → compaction disabled
6. `DISABLE_AUTO_COMPACT=1 pi-claude` → compaction disabled
7. `pi --settings a.json --settings '{"theme":"dark"}'` → both applied, later wins
