---
title: "Package Naming and Monorepo Structure"
description: "CLJS packages live in pi-mono alongside TS packages with -cljs suffix"
tags: packaging, architecture
---

## Context

pi-kernel needs a home in the pi-mono monorepo. Multiple CLJS packages are planned alongside existing TS packages. Need clear naming to distinguish CLJS from TS variants.

## Decision

CLJS packages use `-cljs` suffix and live in pi-mono's `packages/` directory:

```
pi-mono/packages/
├── ai/                  # TS — unchanged
├── tui/                 # TS — unchanged
├── agent/               # TS — unchanged, coexists
├── coding-agent/        # TS — unchanged, coexists
├── kernel-cljs/         # CLJS — universal agent core
├── coding-agent-cljs/   # CLJS — coding agent (thin layer on kernel)
├── tg-cljs/             # CLJS — Telegram adapter
├── mom/                 # TS — unchanged (CLJS Slack adapter deferred)
└── web-ui/              # TS — unchanged (CLJS WebSocket adapter deferred)
```

TS packages (pi-agent, coding-agent, mom, web-ui) are not retired. They coexist indefinitely.

CLJS build uses shadow-cljs with JVM installed via mise. Each CLJS package has its own `shadow-cljs.edn` and `deps.edn`.

## Consequences

- Clear distinction between TS and CLJS packages
- No disruption to existing TS consumers
- Incremental adoption: users choose TS or CLJS path
- CLJS packages depend on TS packages (kernel-cljs → pi-ai, pi-tui)
- CI needs JVM step for CLJS builds (mise install)
- Telegram adapter (tg-cljs) is first non-TUI frontend
- Slack (mom) and Web UI CLJS adapters deferred to later
