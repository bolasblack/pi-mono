---
title: "Kernel Provides Primitives, Not Policy"
description: "Self-modification persistence and distribution is plugin responsibility, not kernel scope"
tags: kernel, self-evolution, persistence
---

## Context

The self-evolving agent can modify its tools at runtime. The question arose: how should these modifications be persisted and distributed across sessions, machines, and team members?

An initial proposal included four-tier storage (session → project → user-global → shared), git-based distribution, auto-promotion based on success rates, trust tiers, and conflict resolution — all inside the kernel.

## Decision

Kernel provides only primitives:
- Tool modification API (`modify-tool`)
- Modifications recorded as DataScript events
- EDN serialization/deserialization of modifications
- Interceptor hooks for plugins to observe and act on modification events

Everything above is plugin responsibility:
- Where to store modifications (file system, database, cloud)
- How to sync across machines (git, API, volume mount)
- When to promote (success rate thresholds, manual approval)
- How to handle conflicts (evidence-based, user choice)
- Trust tiers for imported modifications

## Consequences

- Kernel stays small and focused
- Different deployment scenarios (personal, team, Docker, CI) use different plugins
- No mandatory infrastructure dependency in kernel
- Plugin authors have full flexibility via interceptor chain
- The four-tier storage design (from architect analysis at /tmp/994d9580/analysis-cross-session.md) serves as reference for the first plugin implementation
