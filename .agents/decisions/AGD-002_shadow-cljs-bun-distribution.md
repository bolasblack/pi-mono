---
title: "Distribution via shadow-cljs + Bun Compile"
description: "Use shadow-cljs :simple target to produce JS, then bun build --compile for single binary distribution"
tags: distribution, architecture
---

## Context

pi coding-agent distributes as a single binary via `bun build --compile`. A CLJS rewrite must match this distribution model — CLI tools that require `npm install` have significantly worse adoption.

Initial concern: no CLJS toolchain produces single binaries. Expert analysis (75-90% confidence across 4 experts) concluded the path is feasible.

## Decision

Distribution path: `shadow-cljs :simple` → single self-contained JS file → `bun build --compile` → standalone binary.

Phase 0 PoC validated this works: CLJS core (persistent vectors, atoms, core.async), SCI sandbox, and DataScript all function correctly in a bun-compiled binary. Binary size overhead: ~1MB on ~90MB base.

For development: run directly with `bun run out/pi-kernel.js` without compiling to binary.

## Consequences

- Single binary distribution preserved — identical install experience to pi coding-agent
- shadow-cljs `:simple` output is self-contained (goog.* inlined, no dynamic requires)
- Startup time slightly higher (~80-150ms vs ~50ms for pure TS)
- Must use `:simple` optimization (not `:advanced`) to avoid Bun compatibility risks
- Deno compile available as fallback if Bun path breaks
