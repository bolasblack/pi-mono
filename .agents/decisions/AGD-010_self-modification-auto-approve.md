---
title: "Tool Self-Modification Auto-Approved by Default"
description: "Agent tool modifications are automatically applied, with strict mode for manual approval"
tags: self-evolution, kernel
---

## Context

When the agent modifies its own tools at runtime (e.g., adding fuzzy matching to the edit tool after repeated whitespace failures), should the modification require explicit user approval?

The SCI sandbox provides structural safety: no fs/network/process access, AST walk for dangerous operations, malli fuzz testing. Auto-rollback disables modifications with >50% failure rate after 5+ uses.

## Decision

- Default mode: auto-approve. Modifications are applied immediately after sandbox safety validation.
- Strict mode: modifications shown in conversation, user must confirm before application.
- Mode configurable per-session or globally.

## Consequences

- Default experience is seamless — agent improves itself without interrupting flow
- Safety guaranteed by sandbox + structural analysis + auto-rollback, not human review
- Strict mode available for high-stakes environments or debugging
- All modifications recorded as DataScript events regardless of mode (full audit trail)
