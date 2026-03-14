# Spec: Extension CLI Args Transformer Hook

## Status

Abandoned. Reason: this capability can be implemented outside pi by spawning the pi CLI from a wrapper script and preprocessing CLI args in that script before invoking pi.

## Overview

Extensions cannot modify CLI arguments because args are parsed before extensions
load. This adds a `registerCliArgsTransformer` method to `ExtensionAPI` so
extensions can rewrite parsed CLI args (e.g. model names) before model resolution.

## Problem

1. `parseArgs(process.argv)` produces an `Args` object.
2. `resourceLoader.reload()` loads extensions, which may call `registerProvider()`.
3. Model resolution runs — but extensions had no chance to transform `args.model`
   or any other CLI arg.
4. Use case: a plugin needs to rewrite `claude-opus-4-6[1m]` →
   `anthropic/claude-opus-4-6` before model resolution.

## Solution

### ExtensionAPI addition

```ts
registerCliArgsTransformer(fn: CliArgsTransformer): void;
```

Where `CliArgsTransformer = (args: Args) => Args`, using the existing `Args`
type from `cli/args.ts`.

### ExtensionRuntimeState addition

```ts
cliArgsTransformers: CliArgsTransformer[];
```

### Execution flow change in `main.ts`

```
1. First pass: parseArgs(args)           → get extension paths
2. resourceLoader.reload()               → extensions load, register transformers
3. Second pass: parseArgs(args, flags)   → full parse
4. Apply CLI args transformers           ← NEW
5. Model resolution, session creation
```

Transformers are applied in registration order (pipeline):
`args → transformerA(args) → transformerB(result) → finalArgs`

Only effective via the main CLI entry point. No effect in RPC mode or
cli-claude-compat.

## Files Changed

- `packages/coding-agent/src/core/extensions/types.ts` — add `CliArgsTransformer`
  type, `registerCliArgsTransformer` to `ExtensionAPI`, `cliArgsTransformers` to
  `ExtensionRuntimeState`
- `packages/coding-agent/src/core/extensions/loader.ts` — initialize
  `cliArgsTransformers` array in `createExtensionRuntime()`, wire up
  `registerCliArgsTransformer` in `createExtensionAPI()`
- `packages/coding-agent/src/core/extensions/index.ts` — export
  `CliArgsTransformer`
- `packages/coding-agent/src/main.ts` — apply transformers after second parse,
  before model resolution
- `packages/coding-agent/src/index.ts` — export `Args` and `CliArgsTransformer`

## Verification

1. Create an extension that rewrites `--model foo` →
   `--model anthropic/claude-sonnet-4-20250514`.
2. Run `pi --model foo` with the extension loaded.
3. Without fix: "Unknown model: foo" error.
4. With fix: model resolves to `anthropic/claude-sonnet-4-20250514`.
