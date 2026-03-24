/**
 * Settings hooks — Claude Code --settings hooks support for pi.
 *
 * Pure functions that parse, inspect, and run hooks from settings values.
 * No global state or process.argv/process.env access — all data is injected.
 *
 * ── Settings hooks format (Claude Code) ──
 *
 *   { "hooks": { "SessionStart": [{ "hooks": [{ "type": "command", "command": "..." }] }] } }
 *
 * ── Execution model (matches Claude Code) ──
 *
 * All hooks for an event run in parallel (Promise.all), results merged via
 * mergeHookResults (deny > ask > allow for permissions, additionalContext
 * concatenated, any hook can block).
 */

import { existsSync, readFileSync } from "fs";
import { type HookOutput, mergeHookResults, spawnHookCommand } from "./hook-runner.js";

// =========================================================================
// Types
// =========================================================================

interface CommandHookEntry {
	type: "command";
	command: string;
}

interface HookGroup {
	hooks: CommandHookEntry[];
}

export type SettingsHooksMap = Record<string, HookGroup[]>;

export interface RunSettingsHooksOptions {
	timeoutMs?: number;
}

const DEFAULT_SETTINGS_HOOK_TIMEOUT_MS = 10_000;

// =========================================================================
// extractSettingsFromArgv — pure
// =========================================================================

/**
 * Extract all --settings values from an argv array.
 * Supports both `--settings value` and `--settings=value`.
 */
export function extractSettingsFromArgv(argv: readonly string[]): string[] {
	const results: string[] = [];
	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i]!;
		if (arg === "--settings" && i + 1 < argv.length) {
			results.push(argv[i + 1]!);
			i++; // skip value
		} else if (arg.startsWith("--settings=")) {
			results.push(arg.slice("--settings=".length));
		}
	}
	return results;
}

// =========================================================================
// parseSettingsHooks — pure
// =========================================================================

/**
 * Parse hooks from a single settings value (JSON string or file path).
 */
function parseHooksFromSingleValue(settingsValue: string): SettingsHooksMap | null {
	let content = settingsValue;

	// If it looks like a file path (not JSON), read the file
	if (!content.trimStart().startsWith("{") && !content.trimStart().startsWith("[")) {
		if (existsSync(content)) {
			try {
				content = readFileSync(content, "utf-8");
			} catch {
				return null;
			}
		} else {
			return null;
		}
	}

	try {
		const parsed = JSON.parse(content);
		if (parsed && typeof parsed === "object" && parsed.hooks) {
			const hooks = parsed.hooks;
			if (typeof hooks === "object" && !Array.isArray(hooks) && Object.keys(hooks).length > 0) {
				return hooks as SettingsHooksMap;
			}
		}
	} catch {
		// malformed JSON — ignore
	}
	return null;
}

/**
 * Merge multiple SettingsHooksMaps — concatenate hook groups per event.
 */
function mergeSettingsHooksMaps(maps: SettingsHooksMap[]): SettingsHooksMap {
	const merged: SettingsHooksMap = {};
	for (const map of maps) {
		for (const [event, groups] of Object.entries(map)) {
			if (!merged[event]) merged[event] = [];
			merged[event].push(...groups);
		}
	}
	return merged;
}

/**
 * Parse hooks from settings values (JSON strings or file paths).
 * Returns a merged hooks map, or null if no hooks found.
 */
export function parseSettingsHooks(settingsValues: string[]): SettingsHooksMap | null {
	const maps: SettingsHooksMap[] = [];

	for (const value of settingsValues) {
		const hooks = parseHooksFromSingleValue(value);
		if (hooks) maps.push(hooks);
	}

	return maps.length > 0 ? mergeSettingsHooksMaps(maps) : null;
}

// =========================================================================
// hasSettingsHooksInMap — pure
// =========================================================================

/**
 * Check whether a parsed hooks map contains any runnable command hooks.
 */
export function hasSettingsHooksInMap(hooksMap: SettingsHooksMap | null): boolean {
	if (!hooksMap) return false;
	return Object.values(hooksMap).some(
		(groups) => Array.isArray(groups) && groups.some((g) => Array.isArray(g.hooks) && g.hooks.length > 0),
	);
}

// =========================================================================
// runSettingsHooks — pure (env injected)
// =========================================================================

/**
 * Run all settings hooks for the given event.
 * Hooks run in parallel (matching Claude Code), results merged.
 *
 * @param hooksMap - Parsed hooks map (from parseSettingsHooks)
 * @param eventName - Hook event name (e.g., "PreToolUse")
 * @param payload - Hook payload (passed to subprocess via stdin)
 * @param env - Environment variables for subprocess
 * @param options - Optional timeout override
 */
export async function runSettingsHooks(
	hooksMap: SettingsHooksMap | null,
	eventName: string,
	payload: Record<string, any>,
	env: Record<string, string | undefined>,
	options?: RunSettingsHooksOptions,
): Promise<HookOutput | null> {
	if (!hooksMap) return null;

	const groups = hooksMap[eventName];
	if (!groups || groups.length === 0) return null;

	const timeoutMs = options?.timeoutMs ?? DEFAULT_SETTINGS_HOOK_TIMEOUT_MS;
	const promises: Promise<HookOutput | null>[] = [];

	for (const group of groups) {
		if (!Array.isArray(group.hooks)) continue;
		for (const entry of group.hooks) {
			if (entry.type !== "command" || !entry.command) continue;
			promises.push(spawnHookCommand(entry.command, [], payload, env, `settings-hook ${eventName}`, { timeoutMs }));
		}
	}

	if (promises.length === 0) return null;

	const results = await Promise.all(promises);
	return mergeHookResults(results);
}
