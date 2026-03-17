---
title: "Reuse pi-ai and pi-tui via Interop"
description: "CLJS orchestrates, TS executes — reuse battle-tested TS packages instead of rewriting"
tags: interop, architecture, kernel
---

## Context

pi-ai supports 20+ LLM providers with 33+ integration tests. pi-tui handles terminal rendering (differential rendering, Kitty protocol, IME, Unicode, images). Rewriting these in CLJS has no benefit — they are IO layers that don't need homoiconicity, persistent data, or safe eval.

## Decision

pi-kernel calls pi-ai and pi-tui via JS interop through a thin bridge layer (~200 lines of CLJS). Delete pi-kernel's own provider implementations (~546 lines).

Bridge components:
- `ai-bridge.cljs`: calls pi-ai's `streamSimple()`, converts AsyncIterable → core.async channel
- `tui-bridge.cljs`: wraps pi-tui component constructors
- `tool-bridge.cljs`: converts CLJS deftool format ↔ pi-ai JSON Schema

## Consequences

- Eliminates ~546 lines of redundant provider code
- All 20+ LLM providers available immediately without reimplementation
- pi-tui's terminal capabilities available without reimplementation
- Interop tax is negligible (<10ms/turn vs 200ms-30s LLM latency)
- ~30-80 JS boundary crossings per agent turn — type errors at boundary are runtime, not compile-time
- pi-kernel depends on pi-ai and pi-tui as npm packages
