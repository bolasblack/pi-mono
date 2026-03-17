---
title: "Multi-Frontend via IFrontendAdapter Protocol"
description: "Define adapter protocol so kernel supports TUI, Web, Telegram, Slack, and any future frontend"
tags: frontend, architecture, kernel
---

## Context

pi-kernel must be a universal agent core, not just a coding agent. It should power TUI (coding-agent-cljs), Web UI, Telegram bot (tg-cljs), Slack bot, and any future interface — all sharing the same agent loop, session management, and self-evolution capabilities.

## Decision

Define two CLJS protocols:

**IAgentCore** — what the kernel exposes:
- `prompt!`, `steer!`, `abort!`, `subscribe!`, `set-model!`, `set-tools!`, `set-system-prompt!`

**IFrontendAdapter** — what each frontend implements:
- `on-event` — handle agent events (text tokens, tool calls, errors)
- `get-capabilities` — declare what the frontend supports (streaming, images, interactivity)
- `request-input` — ask user for input (returns core.async channel)
- `request-approval` — ask user to approve tool use

Events flow through core.async `mult` (broadcast). Each adapter taps its own channel with its own buffer policy.

| Adapter | Streaming | Interactive | Transport |
|---------|-----------|-------------|-----------|
| TUI | Token-by-token | Yes | In-process |
| RPC | JSON lines | Yes | stdio |
| WebSocket | JSON frames | Yes | TCP |
| Telegram | No — batched | Limited | HTTP API |
| Slack | No — batched | No — auto-approve | HTTP API |

## Consequences

- Agent loop is completely frontend-agnostic
- Adding a new frontend = implementing IFrontendAdapter, nothing else
- Slow adapters don't block fast ones (independent channels with buffer policies)
- RPC adapter reuses pi coding-agent's existing JSON lines protocol
- Each adapter decides its own approval policy (interactive vs auto-approve)
