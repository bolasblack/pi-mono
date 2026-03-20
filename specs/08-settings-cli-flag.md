# Spec: Add --settings CLI Flag

## Overview

Add a `--settings <path|json>` CLI flag that accepts JSON strings or file paths
to override settings at startup. Repeatable, applied left-to-right, later
entries win. Overrides must persist across `/reload` and correctly handle
resource fields (packages, extensions, skills, prompts, themes).

## Behavior

### CLI flag

Add `settings?: string[]` to `Args`. Each entry is parsed as inline JSON or
file path, applied via `settingsManager.applyOverrides()` in order.

### Reload persistence

`SettingsManager` stores overrides in a `cliOverrides` field. A
`recomputeMergedSettings()` method centralizes the three-layer merge (global +
project + CLI overrides) and is called from the constructor, `reload()`,
`save()`, `saveProjectSettings()`, and `applyOverrides()` — ensuring CLI
overrides are never silently dropped.

### Resource field resolution

`PackageManager.resolve()` reads `getGlobalSettings()` / `getProjectSettings()`
separately for scope tracking, so resource fields from CLI overrides are
invisible to it. Fix: expose `getCliOverrideSettings()` on `SettingsManager` and
read it in `resolve()` as a third layer with `"temporary"` scope.

Additional fixes:
- `dedupePackages()`: priority changed to `temporary > project > user`
- `addAutoDiscoveredResources()`: CLI override patterns (e.g. `!pattern`) apply
  to auto-discovered resources
- `checkForAvailableUpdates()`: intentionally unchanged — temporary CLI packages
  don't need update checks

## Files Changed

- `packages/coding-agent/src/cli/args.ts` — add `settings` field + parsing + help
- `packages/coding-agent/src/main.ts` — handle `parsed.settings` after SettingsManager creation
- `packages/coding-agent/src/core/settings-manager.ts` — add `cliOverrides`, `applyOverrides()`, `getCliOverrideSettings()`, `recomputeMergedSettings()`
- `packages/coding-agent/src/core/package-manager.ts` — read CLI overrides in `resolve()`, fix `dedupePackages()` priority, pass CLI patterns to `addAutoDiscoveredResources()`

## Verification

1. `pi --settings '{"packages": ["/path/to/plugin"]}'` → plugin loads and appears in extensions
2. `pi --settings a.json --settings '{"defaultProvider":"openai"}'` → both applied, later wins
3. After `/reload`, all `--settings` overrides (resource and non-resource) are still active
4. Changing settings via UI (e.g. switching model) does not drop CLI overrides
5. `--settings '{"skills": ["!some-skill"]}'` → skill excluded from auto-discovery
