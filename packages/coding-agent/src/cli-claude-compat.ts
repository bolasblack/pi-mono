#!/usr/bin/env node
/**
 * Claude Code CLI compatibility wrapper for pi (v2).
 *
 * Thin translation layer: parses Claude Code CLI flags, pre-processes
 * compat-only features, translates to pi CLI argv, and delegates to main().
 *
 * Unlike cli-claude-compat.ts (v1) which duplicated the entire resource
 * loading / extension / session creation flow, this version reuses main()'s
 * flow so extension features like registerFlag work automatically.
 */

process.title = "pi";

import { readFile } from "node:fs/promises";
import { setBedrockProviderModule } from "@mariozechner/pi-ai";
import { bedrockProviderModule } from "@mariozechner/pi-ai/bedrock-provider";
import {
	initTheme,
	SessionManager,
	SessionSelectorComponent,
	SettingsManager,
	VERSION,
} from "@mariozechner/pi-coding-agent";
import { ProcessTerminal, TUI } from "@mariozechner/pi-tui";
import { EnvHttpProxyAgent, setGlobalDispatcher } from "undici";
import { main } from "./main.js";

// ============================================================================
// Compat flag parsing (same syntax as Claude Code CLI)
// ============================================================================

interface CompatArgs {
	appendSystemPrompt?: string;
	appendSystemPromptFile?: string;
	continueLast: boolean;
	model?: string;
	print: boolean;
	resumeTarget?: string;
	resumePicker: boolean;
	sessionId?: string;
	settings: string[];
	systemPrompt?: string;
	systemPromptFile?: string;
	forkSession: boolean;
	messages: string[];
	warnings: string[];
	/** Unknown flags to pass through (potential extension flags) */
	unknownFlags: string[];
}

type FlagValueMode = "none" | "required" | "optional";

type SupportedFlagName =
	| "--append-system-prompt"
	| "--append-system-prompt-file"
	| "--continue"
	| "--help"
	| "--model"
	| "--print"
	| "--resume"
	| "--session-id"
	| "--settings"
	| "--system-prompt"
	| "--system-prompt-file"
	| "--fork-session"
	| "--version";

interface SupportedFlagDefinition {
	name: SupportedFlagName;
	short?: string;
	valueMode: FlagValueMode;
}

interface UnsupportedFlagDefinition {
	name: string;
	takesValue: boolean;
}

const SUPPORTED_FLAG_DEFINITIONS: readonly SupportedFlagDefinition[] = [
	{ name: "--append-system-prompt", valueMode: "required" },
	{ name: "--append-system-prompt-file", valueMode: "required" },
	{ name: "--continue", short: "-c", valueMode: "none" },
	{ name: "--model", valueMode: "required" },
	{ name: "--fork-session", valueMode: "none" },
	{ name: "--print", short: "-p", valueMode: "none" },
	{ name: "--resume", short: "-r", valueMode: "optional" },
	{ name: "--session-id", valueMode: "required" },
	{ name: "--settings", valueMode: "required" },
	{ name: "--system-prompt", valueMode: "required" },
	{ name: "--system-prompt-file", valueMode: "required" },
	{ name: "--help", short: "-h", valueMode: "none" },
	{ name: "--version", short: "-v", valueMode: "none" },
];

const UNSUPPORTED_FLAG_DEFINITIONS: readonly UnsupportedFlagDefinition[] = [
	{ name: "--add-dir", takesValue: true },
	{ name: "--agent", takesValue: true },
	{ name: "--agents", takesValue: false },
	{ name: "--allow-dangerously-skip-permissions", takesValue: false },
	{ name: "--allowedTools", takesValue: true },
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
	{ name: "--no-chrome", takesValue: false },
	{ name: "--name", takesValue: true },
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

const SUPPORTED_FLAG_BY_TOKEN = new Map<string, SupportedFlagDefinition>();
for (const def of SUPPORTED_FLAG_DEFINITIONS) {
	SUPPORTED_FLAG_BY_TOKEN.set(def.name, def);
	if (def.short) SUPPORTED_FLAG_BY_TOKEN.set(def.short, def);
}

const UNSUPPORTED_FLAG_BY_NAME = new Map(UNSUPPORTED_FLAG_DEFINITIONS.map((d) => [d.name, d]));

function normalizeFlag(arg: string): string {
	const eq = arg.indexOf("=");
	return eq >= 0 ? arg.slice(0, eq) : arg;
}

function getInlineValue(arg: string): string | undefined {
	const eq = arg.indexOf("=");
	return eq >= 0 ? arg.slice(eq + 1) : undefined;
}

function isFlagToken(v: string | undefined): boolean {
	return typeof v === "string" && v.startsWith("-");
}

function parseCompatArgs(argv: string[]): CompatArgs {
	const args: CompatArgs = {
		continueLast: false,
		forkSession: false,
		print: false,
		resumePicker: false,
		settings: [],
		messages: [],
		warnings: [],
		unknownFlags: [],
	};

	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i]!;
		const def = SUPPORTED_FLAG_BY_TOKEN.get(normalizeFlag(arg));

		if (def) {
			const inline = getInlineValue(arg);

			if (def.valueMode === "none") {
				applyFlag(args, def.name, undefined);
				continue;
			}

			if (inline !== undefined) {
				applyFlag(args, def.name, inline);
				continue;
			}

			const next = argv[i + 1];
			if (def.valueMode === "optional") {
				if (isFlagToken(next) || next === undefined) {
					applyFlag(args, def.name, undefined);
				} else {
					applyFlag(args, def.name, next);
					i++;
				}
				continue;
			}

			// required
			if (next === undefined) throw new Error(`${def.name} requires a value`);
			applyFlag(args, def.name, next);
			i++;
			continue;
		}

		if (arg.startsWith("-")) {
			const unsup = UNSUPPORTED_FLAG_BY_NAME.get(normalizeFlag(arg));
			if (unsup) {
				args.warnings.push(`Ignoring unsupported Claude flag: ${arg}`);
				if (!arg.includes("=") && unsup.takesValue && !isFlagToken(argv[i + 1])) {
					i++;
				}
				continue;
			}
			// Unknown flag: pass through (might be an extension flag)
			args.unknownFlags.push(arg);
			// If next token is not a flag, assume it's the value
			const next = argv[i + 1];
			if (next !== undefined && !isFlagToken(next)) {
				args.unknownFlags.push(next);
				i++;
			}
			continue;
		}

		args.messages.push(arg);
	}

	return args;
}

function applyFlag(args: CompatArgs, name: SupportedFlagName, value: string | undefined): void {
	switch (name) {
		case "--append-system-prompt":
			args.appendSystemPrompt = value;
			return;
		case "--append-system-prompt-file":
			args.appendSystemPromptFile = value;
			return;
		case "--continue":
			args.continueLast = true;
			return;
		case "--model":
			args.model = value;
			return;
		case "--fork-session":
			args.forkSession = true;
			return;
		case "--print":
			args.print = true;
			return;
		case "--resume":
			if (value) {
				args.resumeTarget = value;
			} else {
				args.resumePicker = true;
			}
			return;
		case "--session-id":
			args.sessionId = value;
			return;
		case "--settings":
			if (value) args.settings.push(value);
			return;
		case "--system-prompt":
			args.systemPrompt = value;
			return;
		case "--system-prompt-file":
			args.systemPromptFile = value;
			return;
		case "--help":
		case "--version":
			// Handled during translation
			return;
	}
}

// ============================================================================
// Validation
// ============================================================================

function validateCompat(args: CompatArgs): void {
	if (args.forkSession && !args.continueLast && !args.resumeTarget && !args.resumePicker) {
		throw new Error("--fork-session requires --continue or --resume");
	}
	if (args.sessionId && (args.continueLast || args.resumePicker || args.resumeTarget)) {
		throw new Error("--session-id cannot be combined with --continue or --resume");
	}
	if (args.continueLast && (args.resumePicker || args.resumeTarget)) {
		throw new Error("--continue cannot be combined with --resume");
	}
	if (args.systemPrompt && args.systemPromptFile) {
		throw new Error("Use either --system-prompt or --system-prompt-file, not both");
	}
	if (args.print && args.messages.length === 0 && !args.continueLast && !args.resumeTarget) {
		throw new Error("--print requires a prompt argument or piped stdin");
	}
}

function isUuid(value: string): boolean {
	return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

// ============================================================================
// Pre-processing helpers
// ============================================================================

async function loadFileText(path: string, description: string): Promise<string> {
	try {
		return await readFile(path, "utf-8");
	} catch {
		throw new Error(`Failed to read ${description}: ${path}`);
	}
}

async function readPipedStdin(): Promise<string | undefined> {
	if (process.stdin.isTTY) return undefined;
	return new Promise((resolve) => {
		let data = "";
		process.stdin.setEncoding("utf8");
		process.stdin.on("data", (chunk) => {
			data += chunk;
		});
		process.stdin.on("end", () => {
			const trimmed = data.trim();
			resolve(trimmed.length > 0 ? trimmed : undefined);
		});
		process.stdin.resume();
	});
}

function showSessionPicker(cwd: string): Promise<string | null> {
	return new Promise((resolve) => {
		const settingsManager = SettingsManager.create(cwd);
		initTheme(settingsManager.getTheme(), false);
		const ui = new TUI(new ProcessTerminal());
		let resolved = false;

		const selector = new SessionSelectorComponent(
			(onProgress) => SessionManager.list(cwd, undefined, onProgress),
			SessionManager.listAll,
			(path: string) => {
				if (!resolved) {
					resolved = true;
					ui.stop();
					resolve(path);
				}
			},
			() => {
				if (!resolved) {
					resolved = true;
					ui.stop();
					resolve(null);
				}
			},
			() => {
				ui.stop();
				process.exit(0);
			},
			() => ui.requestRender(),
			{ showRenameHint: false },
		);

		ui.addChild(selector);
		ui.setFocus(selector.getSessionList());
		ui.start();
	});
}

// ============================================================================
// Model name rewriting
// ============================================================================

/**
 * Rewrite Claude Code model names to pi's provider/model format.
 * Only applies to models starting with "claude-".
 *
 * - Strip thinking budget suffix: claude-opus-4-6[1m] → claude-opus-4-6
 * - Add provider prefix: claude-opus-4-6 → anthropic/claude-opus-4-6
 * - Already prefixed: anthropic/claude-opus-4-6 → no change
 */
function rewriteClaudeModel(model: string): string {
	// Already has a provider prefix — don't touch
	if (model.includes("/")) {
		const afterSlash = model.slice(model.indexOf("/") + 1);
		if (!afterSlash.startsWith("claude-")) return model;
		// Provider-prefixed claude model: strip budget suffix if present
		return `${model.slice(0, model.indexOf("/") + 1)}${afterSlash.replace(/\[.*\]$/, "")}`;
	}

	// Not a claude- model — don't touch
	if (!model.startsWith("claude-")) return model;

	// Strip thinking budget suffix and add provider prefix
	return `anthropic/${model.replace(/\[.*\]$/, "")}`;
}

// ============================================================================
// Translation: CompatArgs → pi CLI argv
// ============================================================================

async function translateToMainArgv(compat: CompatArgs): Promise<string[]> {
	const argv: string[] = [];

	// ANTHROPIC_MODEL env fallback
	if (!compat.model && process.env.ANTHROPIC_MODEL) {
		compat.model = process.env.ANTHROPIC_MODEL;
	}

	// Model (rewrite claude- names to anthropic/ provider format)
	if (compat.model) {
		argv.push("--model", rewriteClaudeModel(compat.model));
	}

	// System prompt (resolve file to string)
	if (compat.systemPromptFile) {
		const content = await loadFileText(compat.systemPromptFile, "system prompt file");
		argv.push("--system-prompt", content);
	} else if (compat.systemPrompt) {
		argv.push("--system-prompt", compat.systemPrompt);
	}

	// Append system prompt (resolve file to string)
	if (compat.appendSystemPromptFile) {
		const content = await loadFileText(compat.appendSystemPromptFile, "append system prompt file");
		argv.push("--append-system-prompt", content);
	} else if (compat.appendSystemPrompt) {
		argv.push("--append-system-prompt", compat.appendSystemPrompt);
	}

	// Settings: pass through each entry, append DISABLE_AUTO_COMPACT as extra
	for (const entry of compat.settings) {
		argv.push("--settings", entry);
	}
	if (process.env.DISABLE_AUTO_COMPACT) {
		argv.push("--settings", '{"compaction":{"enabled":false}}');
	}

	// Print mode
	if (compat.print) {
		argv.push("--print");
	}

	// Session handling: translate compat semantics to pi semantics
	const cwd = process.cwd();

	if (compat.sessionId) {
		if (!isUuid(compat.sessionId)) {
			throw new Error("--session-id must be a valid UUID");
		}
		argv.push("--session", compat.sessionId, "--session-mode", "auto");
	} else if (compat.forkSession) {
		// --fork-session + --continue: fork from last session
		if (compat.continueLast) {
			const sessions = await SessionManager.list(cwd);
			if (sessions.length === 0) {
				throw new Error("No sessions found to fork");
			}
			argv.push("--fork", sessions[0].id);
		}
		// --fork-session + --resume target: fork from specific session
		else if (compat.resumeTarget) {
			const sessions = await SessionManager.listAll();
			const match = findSession(sessions, compat.resumeTarget);
			if (!match) {
				throw new Error(`No session found for --resume ${compat.resumeTarget}`);
			}
			argv.push("--fork", match.path);
		}
		// --fork-session + --resume (picker): show picker then fork
		else if (compat.resumePicker) {
			const selectedPath = await showSessionPicker(cwd);
			if (!selectedPath) {
				process.exit(0);
			}
			argv.push("--fork", selectedPath);
		}
	} else if (compat.resumeTarget) {
		// --resume <target>: find session and open it
		argv.push("--session", compat.resumeTarget);
	} else if (compat.resumePicker) {
		// --resume (no arg): open session picker
		argv.push("--resume");
	} else if (compat.continueLast) {
		argv.push("--continue");
	}

	// Pass through unknown flags (potential extension flags)
	argv.push(...compat.unknownFlags);

	// Messages
	argv.push(...compat.messages);

	return argv;
}

interface SessionInfo {
	id: string;
	name?: string;
	path: string;
}

function findSession(sessions: SessionInfo[], target: string): SessionInfo | undefined {
	const exactId = sessions.find((s) => s.id === target);
	if (exactId) return exactId;

	const nameMatches = sessions.filter((s) => s.name === target);
	if (nameMatches.length === 1) return nameMatches[0];
	if (nameMatches.length > 1) throw new Error(`Multiple sessions share the name: ${target}`);

	const prefixMatches = sessions.filter((s) => s.id.startsWith(target));
	if (prefixMatches.length === 1) return prefixMatches[0];
	if (prefixMatches.length > 1) {
		const ids = prefixMatches.map((s) => s.id).join(", ");
		throw new Error(`Multiple sessions match prefix "${target}": ${ids}`);
	}

	return undefined;
}

// ============================================================================
// Entry point
// ============================================================================

async function run(): Promise<void> {
	setGlobalDispatcher(new EnvHttpProxyAgent());
	setBedrockProviderModule(bedrockProviderModule);

	const rawArgv = process.argv.slice(2);

	// Handle --help and --version before full parse
	if (rawArgv.includes("--help") || rawArgv.includes("-h")) {
		printCompatHelp();
		process.exit(0);
	}
	if (rawArgv.includes("--version") || rawArgv.includes("-v")) {
		console.log(VERSION);
		process.exit(0);
	}

	const compat = parseCompatArgs(rawArgv);

	// Print warnings for unsupported Claude flags
	for (const warning of compat.warnings) {
		console.error(`[pi-wrapper] Warning: ${warning}`);
	}

	// Read piped stdin
	const stdinContent = await readPipedStdin();
	if (stdinContent !== undefined) {
		compat.messages.unshift(stdinContent);
	}

	validateCompat(compat);

	// Translate to pi CLI argv and delegate to main
	const mainArgv = await translateToMainArgv(compat);
	await main(mainArgv);
}

function printCompatHelp(): void {
	const flags = [
		"  --model <id>                   Model to use",
		"  --continue, -c                 Continue most recent session",
		"  --resume [id|name], -r         Resume a session (opens picker if no arg)",
		"  --session-id <uuid>            Use or create a session with this UUID",
		"  --fork-session                 Fork session (use with --continue or --resume)",
		"  --print, -p                    Non-interactive mode",
		"  --system-prompt <text>         Set system prompt",
		"  --system-prompt-file <file>    Set system prompt from file",
		"  --append-system-prompt <text>  Append to system prompt",
		"  --append-system-prompt-file <file>  Append to system prompt from file",
		"  --settings <path|json>         Apply settings overrides",
		"  --help, -h                     Show this help",
		"  --version, -v                  Show version",
	];
	console.log(`Usage: pi-claude [options] [message...]

Claude Code compatible flags:
${flags.join("\n")}

Unknown flags are passed through to pi (including extension flags).`);
}

void run().catch((error: unknown) => {
	const message = error instanceof Error ? error.message : String(error);
	console.error(`[pi-wrapper] ${message}`);
	process.exit(1);
});
