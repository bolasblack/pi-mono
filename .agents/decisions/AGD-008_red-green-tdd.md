---
title: "Red/Green TDD as Development Method"
description: "All code must follow red/green test-driven development"
tags: testing, kernel
---

## Context

pi-kernel is a new codebase with novel capabilities (self-modification, runtime eval). High test coverage is essential to prevent regressions, especially when the agent modifies its own tools.

## Decision

All development follows red/green TDD:
1. Write a failing test first (red) — the test expresses expected behavior
2. Write minimal code to make it pass (green)
3. Refactor if needed
4. Repeat

Tests verify behavior, not implementation details. Write what you expect the system to do, run the test, fix the implementation to match.

No code without a test. No test that starts green.

## Consequences

- High confidence in refactoring (which happens frequently in early architecture)
- Tests serve as executable documentation of intended behavior
- Self-modification features are testable: test that a modified tool behaves correctly, not how the modification was applied
- Prevents "tests that mirror implementation" — tests express user-facing expectations
- Current state: 89 tests, 203 assertions, 0 failures across all modules
