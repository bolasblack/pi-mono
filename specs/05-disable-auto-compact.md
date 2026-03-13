# Spec: DISABLE_AUTO_COMPACT Environment Variable

## Overview

When using pi-claude, the `DISABLE_AUTO_COMPACT` environment variable prevents
automatic context compaction.

## Behavior

- When `DISABLE_AUTO_COMPACT` is set (any truthy value), pi-claude disables
  automatic compaction of conversation context when it grows large.
- When unset or empty, automatic compaction proceeds as normal.

## Use Case

Useful for debugging, testing, or workflows where the full uncompacted context
is required (e.g., long-running sessions where losing earlier context is unacceptable).

## Verification

1. `DISABLE_AUTO_COMPACT=1 pi-claude -p "write a very long response"` — even if
   context grows large, no compaction occurs.
2. Without the env var, compaction triggers normally when context exceeds the threshold.
