---
title: "Interceptor Chain as Agent Loop"
description: "Replace monolithic agent loop with data-driven interceptor chain"
tags: architecture, kernel
---

## Context

pi-agent's agent loop is a monolithic function handling context building, LLM calls, tool execution, steering, and hooks. Extending it requires modifying the function or using callback hooks with limited control over ordering and composition.

## Decision

The agent loop is an interceptor chain — an ordered list of middleware, each with `:enter` and `:leave` phases. The chain is data (a vector of maps), not code.

Default chain:
```
logging → context-mgmt → self-mod-analyze → tool-approval → token-counting → provider → tool-execution
```

A context map flows through the chain carrying session, messages, model config, tool registry, agent state, response, and tool results.

Extensions insert/remove/replace interceptors in the chain. This replaces pi-agent's hook callbacks (`beforeToolCall`, `afterToolCall`, `getSteeringMessages`) — each becomes a specific interceptor.

pi-kernel calls pi-ai's `streamSimple()` directly from the `:provider` interceptor, not through pi-agent's `agentLoop()`. pi-agent remains available for pure-TS consumers.

## Consequences

- Extensions have full control: ordering, removal, replacement — not just before/after hooks
- The agent can inspect and modify its own processing pipeline (code-as-data)
- Async interceptors supported via core.async channels
- pi-agent coexists for TS-only consumers but is not required by pi-kernel
- Each concern is independently testable
- Interceptor chain is the natural integration point for self-modification analysis
