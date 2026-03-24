/**
 * Flag definition tables for Claude Code CLI compatibility.
 *
 * Supported flags are parsed and translated to pi equivalents.
 * Unsupported flags are recognized and warned about (not silently dropped).
 */

import type { SupportedFlagDefinition, UnsupportedFlagDefinition } from "./types.js";

export const SUPPORTED_FLAG_DEFINITIONS: readonly SupportedFlagDefinition[] = [
	{ name: "--append-system-prompt", valueMode: "required" },
	{ name: "--append-system-prompt-file", valueMode: "required" },
	{ name: "--continue", short: "-c", valueMode: "none" },
	{ name: "--model", valueMode: "required" },
	{ name: "--fork-session", valueMode: "none" },
	// -p has valueMode "none": in `claude -p "prompt"`, the prompt becomes a
	// positional arg (message), which is the intended behavior matching Claude Code.
	{ name: "--print", short: "-p", valueMode: "none" },
	{ name: "--resume", short: "-r", valueMode: "optional" },
	{ name: "--session-id", valueMode: "required" },
	{ name: "--settings", valueMode: "required" },
	{ name: "--system-prompt", valueMode: "required" },
	{ name: "--system-prompt-file", valueMode: "required" },
	{ name: "--help", short: "-h", valueMode: "none" },
	{ name: "--version", short: "-v", valueMode: "none" },
];

export const UNSUPPORTED_FLAG_DEFINITIONS: readonly UnsupportedFlagDefinition[] = [
	{ name: "--allowedTools", takesValue: true },
	{ name: "--add-dir", takesValue: true },
	{ name: "--agent", takesValue: true },
	{ name: "--agents", takesValue: false },
	{ name: "--allow-dangerously-skip-permissions", takesValue: false },
	{ name: "--betas", takesValue: true },
	{ name: "--chrome", takesValue: false },
	{ name: "--dangerously-skip-permissions", takesValue: false },
	{ name: "--debug", takesValue: false },
	{ name: "--debug-file", takesValue: true },
	{ name: "--disable-slash-commands", takesValue: false },
	{ name: "--disallowedTools", takesValue: true },
	{ name: "--effort", takesValue: true },
	{ name: "--email", takesValue: true },
	{ name: "--fallback-model", takesValue: true },
	{ name: "--file", takesValue: true },
	{ name: "--from-pr", takesValue: true },
	{ name: "--ide", takesValue: false },
	{ name: "--include-partial-messages", takesValue: false },
	{ name: "--init", takesValue: false },
	{ name: "--init-only", takesValue: false },
	{ name: "--input-format", takesValue: true },
	{ name: "--json-schema", takesValue: true },
	{ name: "--maintenance", takesValue: false },
	{ name: "--max-budget-usd", takesValue: true },
	{ name: "--max-turns", takesValue: true },
	{ name: "--mcp-config", takesValue: true },
	{ name: "--mcp-debug", takesValue: false },
	{ name: "--name", takesValue: true },
	{ name: "--no-chrome", takesValue: false },
	{ name: "--no-session-persistence", takesValue: false },
	{ name: "--output-format", takesValue: true },
	{ name: "--permission-mode", takesValue: true },
	{ name: "--permission-prompt-tool", takesValue: true },
	{ name: "--plugin-dir", takesValue: true },
	{ name: "--remote", takesValue: true },
	{ name: "--replay-user-messages", takesValue: false },
	{ name: "--setting-sources", takesValue: true },
	{ name: "--sso", takesValue: false },
	{ name: "--strict-mcp-config", takesValue: false },
	{ name: "--teammate-mode", takesValue: true },
	{ name: "--teleport", takesValue: false },
	{ name: "--text", takesValue: false },
	{ name: "--tmux", takesValue: false },
	{ name: "--tools", takesValue: true },
	{ name: "--verbose", takesValue: false },
	{ name: "--worktree", takesValue: false },
];

export const SUPPORTED_FLAG_BY_TOKEN = new Map<string, SupportedFlagDefinition>();
for (const def of SUPPORTED_FLAG_DEFINITIONS) {
	SUPPORTED_FLAG_BY_TOKEN.set(def.name, def);
	if (def.short) SUPPORTED_FLAG_BY_TOKEN.set(def.short, def);
}

export const UNSUPPORTED_FLAG_BY_NAME = new Map(UNSUPPORTED_FLAG_DEFINITIONS.map((d) => [d.name, d]));
