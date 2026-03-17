---
title: "Project Named pi-kernel"
description: "The CLJS agent core is named pi-kernel — the foundational runtime layer"
tags: kernel, packaging
---

## Context

The project was initially called pi-cljs-poc. After Phase 0 validation, it transitioned from prototype to production code. Needed a proper name reflecting its role.

Team discussed options: pi-cljs (too broad), pi-engine (vague), pi-kernel (precise). pi-kernel won — it accurately describes the foundational runtime layer: sandboxed eval, tool registry, interceptors, session storage, streaming.

## Decision

- Project name: pi-kernel
- Namespace prefix: pi.kernel.*
- Package directory: packages/kernel-cljs/
- All source files under src/pi/kernel/

The `poc` naming was identified as a code smell and eliminated from all namespaces, directories, and references.

## Consequences

- Clear identity: "kernel" communicates foundational role
- Namespace consistency: all modules under pi.kernel.*
- Distinguishes from pi-agent (TS agent loop) — kernel is the CLJS path
