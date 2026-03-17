---
title: "Speculative Execution Deferred to Future Roadmap"
description: "Strategy racing via O(1) forking is architecturally supported but not implemented now"
tags: architecture, kernel
---

## Context

Persistent data structures enable O(1) context forking, which makes it feasible to race multiple strategies in parallel (e.g., exact edit vs fuzzy edit vs diff-based edit). MCTS over agent strategies was identified as a powerful capability.

However, each parallel strategy requires a separate LLM call, making it expensive. The cost/benefit ratio depends on failure patterns that we don't yet have data on.

## Decision

Defer speculative execution to future roadmap. The architecture supports it (persistent state with fork/merge, interceptor chain), but implementation waits until:
1. We have real-world failure pattern data from production use
2. We can define clear trigger conditions (e.g., 3+ consecutive failures on same tool)
3. Cost of parallel LLM calls is justified by measured time savings

## Consequences

- state.cljs already implements fork/merge — no architectural debt
- Future implementation is a new interceptor + strategy generator, not a refactor
- No premature optimization of a feature we can't yet measure
