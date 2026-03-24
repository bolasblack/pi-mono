# pi-claude

Claude Code-compatible wrapper for `pi`.

## What it does

- Parses Claude-style CLI flags (`--resume`, `--fork-session`, `--session-id`, `-p`, etc.)
- Translates them to `pi` arguments
- Automatically injects `pi-claude` runtime extension via `--extension <file>`
- Delegates execution to `pi`

## Usage

```bash
pi-claude --resume <session-id> --fork-session -p "your prompt"
```

## Environment variables

- `PI_CLAUDE_PI_BIN`: override pi binary (default: `pi`)
- `PI_CLAUDE_EXTENSION_FILE`: override extension entry file (default: auto-discover `pi-claude/src/extension.ts`)

## Reusable helpers

```ts
import { collapsedLine } from "pi-claude/ui-helpers";
import { registerClaudeHooks } from "pi-claude/claude-helpers";
```

## Build

```bash
cd pi-claude
npm run build
```
