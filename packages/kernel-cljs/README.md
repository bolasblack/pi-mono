# pi-kernel

A self-evolving coding agent core built in ClojureScript. pi-kernel implements the agent loop that powers pi: session management, LLM streaming, tool execution, and provider dispatch. Built on properties that enable agent self-evolution: homoiconicity (code as data), persistent data structures, and safe runtime eval.

## Architecture

pi-kernel is a modular system where `core.cljs` orchestrates all components through a streaming agent loop with tool-call recursion.

```
                         ┌─────────────────┐
                         │    core.cljs     │
                         │  (agent loop,    │
                         │   CLI, TUI)      │
                         └───────┬──────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                   │
     ┌────────▼───────┐  ┌──────▼───────┐  ┌───────▼────────┐
     │  session.cljs   │  │ provider.cljs │  │  deftool.cljc  │
     │  (DataScript     │  │ (multimethod  │  │  (tool macro + │
     │   session store) │  │  dispatch)    │  │   malli schema)│
     └─────────────────┘  └──────┬────────┘  └────────────────┘
                                 │
                    ┌────────────┼────────────┐
                    │                         │
          ┌────────▼─────────┐  ┌─────────────▼──────────────┐
          │  streaming.cljs   │  │  provider_anthropic.cljs   │
          │  (core.async       │  │  (Anthropic API streaming) │
          │   event bus)       │  └────────────────────────────┘
          └────────┬──────────┘
                   │
          ┌────────▼──────────┐
          │ streaming_full.cljs│
          │ (normalization,    │
          │  response assembly)│
          └───────────────────┘
```

- **core.cljs** -- Entry point. Runs the agent loop (prompt -> stream -> tool calls -> recurse), CLI argument parsing, and interactive readline REPL.
- **session.cljs** -- Session history stored in DataScript, queryable with Datalog. Tracks user messages, assistant responses, tool calls, and tool results.
- **provider.cljs** -- Open provider dispatch via Clojure multimethods. Ships with mock providers (plain text, tool calls, Anthropic-shaped) and a model catalog.
- **provider_anthropic.cljs** -- Real Anthropic API streaming using `@anthropic-ai/sdk`. Translates SDK events to the internal event bus format.
- **streaming.cljs** -- core.async event bus with `mult`/`tap` for consumer isolation. Supports CLJS callbacks, JS interop callbacks, and dropping-buffer subscriptions.
- **streaming_full.cljs** -- Normalizes provider-specific events (Anthropic SSE shape) into a common format (`:text`, `:thinking`, `:tool-call-start`, `:tool-input`, `:stop`). Includes a response assembler for accumulating stream events into complete responses.
- **deftool.cljc** -- Tool definition macro with malli schema validation and automatic JSON Schema generation. Cross-platform (CLJ + CLJS).

## Quick Start

```bash
npm install
npx shadow-cljs compile :app
node out/pi-kernel.js
```

Or with bun:

```bash
node out/pi-kernel.js
# or build a standalone binary:
./scripts/build-binary.sh
```

## Two Modes

**Single prompt** -- send a prompt, stream the response, exit:

```bash
node out/pi-kernel.js -p "Explain closures in JavaScript"
```

**Interactive REPL** -- launched when stdin is a TTY (no `-p` flag):

```bash
node out/pi-kernel.js
# pi-kernel interactive mode (type 'exit' or Ctrl-D to quit)
# you> _
```

**Flags:**

| Flag | Description |
|------|-------------|
| `-p, --prompt <text>` | Single prompt mode |
| `--model <provider/id>` | Override model (default: `anthropic/claude-haiku-4-5-20251001`) |
| `-h, --help` | Show help |

## Configuration

| Variable | Description |
|----------|-------------|
| `ANTHROPIC_API_KEY` | Required for Anthropic models. If unset, falls back to mock provider with a warning. |

Model override example:

```bash
node out/pi-kernel.js --model anthropic/claude-3-5-sonnet-20241022 -p "Hello"
```

The agent loop supports up to 20 consecutive tool-call turns before forcing a stop.

## Module Map

### Active

| Module | Description |
|--------|-------------|
| `core.cljs` | Agent loop, CLI parsing, interactive REPL, tool execution orchestration |
| `session.cljs` | DataScript-backed session store with Datalog queries (errors, turn count, edited files) |
| `provider.cljs` | Multimethod provider dispatch, model catalog, mock providers (plain, tool-call, Anthropic-shaped) |
| `provider_anthropic.cljs` | Real Anthropic API streaming via `@anthropic-ai/sdk`, SSE event translation |
| `streaming.cljs` | core.async event bus with mult/tap fan-out and multiple subscription modes |
| `streaming_full.cljs` | Event normalization across providers, response assembler, stream abort |
| `deftool.cljc` | Tool registry with `deftool` macro, malli schema validation, JSON Schema generation |

### Planned

These dependencies are declared in `deps.edn` but not yet wired into the agent loop:

| Dependency | Intent |
|------------|--------|
| `org.babashka/sci` | Safe runtime eval for self-modifying agent behavior |
| Interceptor chains | Middleware pipeline for request/response processing |
| State/persistence | Durable session state across restarts |
| `modify_tool` | Agent self-modification of tool definitions |
| JS interop layer | Deeper Node.js / bun integration |

## Build Configuration

- **shadow-cljs.edn** -- Two build targets: `:app` (node-script, `out/pi-kernel.js`) and `:test` (node-test, `out/test.js`)
- **deps.edn** -- ClojureScript 1.11.132, DataScript 1.7.3, core.async 1.6.681, SCI 0.12.51, malli 0.16.4
- **package.json** -- shadow-cljs (dev), `@anthropic-ai/sdk` (runtime)
