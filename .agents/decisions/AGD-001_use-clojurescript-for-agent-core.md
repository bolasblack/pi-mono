---
title: "Use ClojureScript for Agent Core"
description: "Choose ClojureScript over TypeScript for the agent core to enable self-evolving agent capabilities"
tags: architecture, language, kernel
---

## Context

pi-mono is a TypeScript monorepo implementing a coding agent platform. We want to build a **self-evolving agent** — one that can inspect its own tools, diagnose failures, modify its own behavior at runtime, and persist improvements across sessions.

Five domain experts (Clojure, Agent Architecture, PL Design, DevX, Distributed Systems) analyzed the possibility space across 6+ rounds of discussion. Their finding: self-evolving agent capabilities require three co-requisite language properties that are **multiplicative, not additive**:

1. **Homoiconicity** (code = data): inspect, transform, compose, transmit code as structured data
2. **Persistent data structures** (structural sharing): O(1) branching, cheap snapshots, zero-cost forking
3. **Safe runtime eval** (SCI): execute dynamically generated code with structural safety analysis

TypeScript has zero of the three natively. Having two of three yields ~25% of capability, not ~67%. ClojureScript has all three.

## Decision

Use ClojureScript (via shadow-cljs) for the agent core (`pi-kernel`). Reuse existing TypeScript packages (pi-ai, pi-tui) via JS interop for LLM streaming and terminal rendering.

## Consequences

- Agent can inspect its own tool implementations (code-as-data)
- Agent can modify tools at runtime with structural safety analysis (SCI sandbox)
- Session forking is O(1) via persistent data structures (enables future strategy racing)
- DataScript provides Datalog queries over session history
- Interceptor chain replaces monolithic agent loop with composable middleware
- Requires JVM for shadow-cljs compilation (installed via mise)
- ClojureScript developer pool is ~200x smaller than TypeScript
- LLM code generation accuracy for Clojure (~60-70%) is lower than TypeScript (~85-90%)
