# pi-coding-agent-cljs

Thin layer on `kernel-cljs` that adds coding-agent-specific configuration:

- **System prompt**: Coding assistant persona with tool usage guidelines
- **Tool set**: read_file, write_file, edit, bash
- **CLI**: Single-prompt mode (`-p`) and interactive TUI mode

## Usage

```bash
# Single prompt
node out/coding-agent.js -p "Read package.json"

# Interactive mode
node out/coding-agent.js

# With specific model
node out/coding-agent.js --model anthropic/claude-haiku-4-5-20251001 -p "Hello"
```

## Environment

- `ANTHROPIC_API_KEY` — Required for Anthropic models. Falls back to mock provider if unset.

## Build

```bash
npm install
npx shadow-cljs compile app    # dev build
npx shadow-cljs release app    # release build (optimized)
npm run build:binary            # release + bun compile to single binary
```

### Artifact Sizes

| Artifact | Size |
|----------|------|
| `out/coding-agent.js` (release) | ~4.2 MB |
| `out/coding-agent-bin` (bun compiled) | ~102 MB |

## Test

```bash
npx shadow-cljs compile test
```

## Architecture

This package does not duplicate kernel logic. It:

1. Imports `pi.kernel.core` for the agent loop, session, provider, interceptors
2. Overrides `kernel/system-prompt` with a coding-agent-specific prompt
3. Registers coding tools via `kernel/init-tools!`
4. Provides its own CLI entry point (`main`)
