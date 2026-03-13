#!/usr/bin/env node
/**
 * Claude Code CLI compatibility wrapper for pi.
 *
 * Accepts Claude Code CLI arguments, translates them into pi SDK calls,
 * and launches pi's TUI (InteractiveMode) or print mode.
 *
 * ── Architecture ──
 *
 * Uses the SDK approach (createAgentSession + InteractiveMode / runPrintMode),
 * not the main(args) wrapper, because main() cannot cleanly inject --settings
 * overrides or create sessions with specific UUIDs.
 *
 * Reuses pi's internal session/path creation logic via SessionManager static
 * methods (create, open, continueRecent, list, listAll)
 * rather than reimplementing session directory layout.
 *
 * ── Flag handling ──
 *
 * Three tiers:
 *   1. Supported flags    — parsed and applied (see SUPPORTED_FLAG_DEFINITIONS)
 *   2. Known unsupported  — Claude Code flags that pi does not implement yet;
 *                           emit a warning to stderr and skip (see UNSUPPORTED_FLAG_DEFINITIONS)
 *   3. Unknown flags      — neither Claude Code nor pi recognizes them; error and exit
 *
 * Flag syntax: both --flag value and --flag=value are accepted.
 * Short aliases (e.g. -c, -p, -r, -h, -v) are defined per flag definition.
 *
 * ── --settings merge semantics ──
 *
 * --settings accepts a JSON string or a path to a JSON file.
 * The override is split into two categories:
 *
 *   1. Scalar settings (defaultModel, theme, compaction, etc.)
 *      Applied via SettingsManager.applyOverrides(), which updates
 *      the merged settings view used by scalar getters.
 *
 *   2. Resource arrays (packages, extensions, skills, prompts, themes)
 *      Injected via DefaultResourceLoader's additional*Paths options
 *      (additionalExtensionPaths, additionalSkillPaths, etc.) instead
 *      of applyOverrides(). This is necessary because
 *      PackageManager.resolve() reads from getGlobalSettings() and
 *      getProjectSettings() separately, bypassing the merged view
 *      that applyOverrides() updates. The additional*Paths mechanism
 *      ensures override resources are discovered and loaded alongside
 *      settings-defined resources.
 *
 * ── --model resolution ──
 *
 * Strict matching only:
 *   - "provider/modelId" syntax → exact lookup via ModelRegistry.find()
 *   - bare "modelId" → must match exactly one model across all providers;
 *     ambiguous matches (same id from multiple providers) error
 *   - no prefix or fuzzy matching
 *
 * ── --resume resolution ──
 *
 * Exact matching only:
 *   - first tries exact session id match
 *   - then tries exact session name match (must be unique; duplicates error)
 *   - no prefix matching, to avoid ambiguity
 *   - without an argument, opens the interactive session picker
 *
 * ── --session-id ──
 *
 * Accepts any 8-4-4-4-12 hex UUID (not restricted to RFC 4122 v1-v5).
 * If a session with that id already exists, it is reopened; otherwise a new
 * session is created via SessionManager.create() + newSession({ id }).
 *
 * ── Prompt overrides ──
 *
 * --system-prompt / --system-prompt-file set the system prompt.
 * --append-system-prompt / --append-system-prompt-file append to it.
 * The --*-file variants are restricted to --print mode only.
 * --system-prompt and --system-prompt-file are mutually exclusive.
 *
 * ── Piped stdin ──
 *
 * If stdin is not a TTY, its content is read and prepended to the message list.
 *
 * ── Conflict rules ──
 *
 * - --session-id cannot combine with --continue or --resume
 * - --continue cannot combine with --resume
 * - --system-prompt and --system-prompt-file are mutually exclusive
 * - --print requires at least one message (from args or stdin)
 */

process.title = "pi";

import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { type Api, type Model, setBedrockProviderModule } from "@mariozechner/pi-ai";
import { bedrockProviderModule } from "@mariozechner/pi-ai/bedrock-provider";
import {
	AuthStorage,
	createAgentSession,
	DefaultResourceLoader,
	getAgentDir,
	InteractiveMode,
	initTheme,
	ModelRegistry,
	runPrintMode,
	type SessionInfo,
	SessionManager,
	SessionSelectorComponent,
	SettingsManager,
	VERSION,
} from "@mariozechner/pi-coding-agent";
import { ProcessTerminal, TUI } from "@mariozechner/pi-tui";
import { EnvHttpProxyAgent, setGlobalDispatcher } from "undici";

type Settings = ReturnType<SettingsManager["getGlobalSettings"]>;

interface CompatArgs {
	appendSystemPrompt?: string;
	appendSystemPromptFile?: string;
	continueLast: boolean;
	model?: string;
	print: boolean;
	resumeTarget?: string;
	resumePicker: boolean;
	sessionId?: string;
	settings?: string;
	systemPrompt?: string;
	systemPromptFile?: string;
	forkSession: boolean;
	messages: string[];
	warnings: string[];
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
	helpLabel: string;
}

interface UnsupportedFlagDefinition {
	name: string;
	takesValue: boolean;
}

interface ParsedFlag {
	name: string;
	value?: string;
	nextIndex: number;
}

const SUPPORTED_FLAG_DEFINITIONS: readonly SupportedFlagDefinition[] = [
	{
		name: "--append-system-prompt",
		valueMode: "required",
		helpLabel: "--append-system-prompt <text>",
	},
	{
		name: "--append-system-prompt-file",
		valueMode: "required",
		helpLabel: "--append-system-prompt-file <file>",
	},
	{ name: "--continue", short: "-c", valueMode: "none", helpLabel: "--continue, -c" },
	{ name: "--model", valueMode: "required", helpLabel: "--model <id>" },
	{ name: "--fork-session", valueMode: "none", helpLabel: "--fork-session" },
	{ name: "--print", short: "-p", valueMode: "none", helpLabel: "--print" },
	{ name: "--resume", short: "-r", valueMode: "optional", helpLabel: "--resume [id|name]" },
	{ name: "--session-id", valueMode: "required", helpLabel: "--session-id <uuid>" },
	{ name: "--settings", valueMode: "required", helpLabel: "--settings <path|json>" },
	{ name: "--system-prompt", valueMode: "required", helpLabel: "--system-prompt <text>" },
	{ name: "--system-prompt-file", valueMode: "required", helpLabel: "--system-prompt-file <file>" },
	{ name: "--help", short: "-h", valueMode: "none", helpLabel: "--help, -h" },
	{ name: "--version", short: "-v", valueMode: "none", helpLabel: "--version, -v" },
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
	{ name: "--disable-slash-commands", takesValue: false },
	{ name: "--disallowedTools", takesValue: true },
	{ name: "--email", takesValue: true },
	{ name: "--fallback-model", takesValue: true },
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
	{ name: "--no-chrome", takesValue: false },
	{ name: "--no-session-persistence", takesValue: false },
	{ name: "--output-format", takesValue: true },
	{ name: "--permission-mode", takesValue: true },
	{ name: "--permission-prompt-tool", takesValue: true },
	{ name: "--plugin-dir", takesValue: true },
	{ name: "--remote", takesValue: true },
	{ name: "--setting-sources", takesValue: true },
	{ name: "--sso", takesValue: false },
	{ name: "--strict-mcp-config", takesValue: false },
	{ name: "--teammate-mode", takesValue: false },
	{ name: "--teleport", takesValue: false },
	{ name: "--text", takesValue: false },
	{ name: "--tools", takesValue: true },
	{ name: "--verbose", takesValue: false },
	{ name: "--worktree", takesValue: false },
];

const SUPPORTED_FLAG_BY_TOKEN = new Map<string, SupportedFlagDefinition>();
for (const definition of SUPPORTED_FLAG_DEFINITIONS) {
	SUPPORTED_FLAG_BY_TOKEN.set(definition.name, definition);
	if (definition.short) {
		SUPPORTED_FLAG_BY_TOKEN.set(definition.short, definition);
	}
}

const UNSUPPORTED_FLAG_BY_NAME = new Map(
	UNSUPPORTED_FLAG_DEFINITIONS.map((definition) => [definition.name, definition]),
);

function createCompatArgs(): CompatArgs {
	return {
		continueLast: false,
		forkSession: false,
		print: false,
		resumePicker: false,
		messages: [],
		warnings: [],
	};
}

function printHelp(command: string): void {
	const supportedFlags = SUPPORTED_FLAG_DEFINITIONS.map((definition) => `  ${definition.helpLabel}`).join("\n");
	console.log(`Usage: ${command} [options] [message...]

Supported Claude-compatible flags:
${supportedFlags}

Notes:
  - --system-prompt-file and --append-system-prompt-file are restricted to --print
  - --session-id must be a UUID
  - --resume without an argument opens the interactive session picker`);
}

function normalizeFlag(arg: string): string {
	const eqIndex = arg.indexOf("=");
	return eqIndex >= 0 ? arg.slice(0, eqIndex) : arg;
}

function getInlineFlagValue(arg: string): string | undefined {
	const eqIndex = arg.indexOf("=");
	return eqIndex >= 0 ? arg.slice(eqIndex + 1) : undefined;
}

function isFlagToken(value: string | undefined): boolean {
	return typeof value === "string" && value.startsWith("-");
}

function parseFlagValue(arg: string, argv: string[], index: number, definition: SupportedFlagDefinition): ParsedFlag {
	const inlineValue = getInlineFlagValue(arg);

	if (definition.valueMode === "none") {
		if (inlineValue !== undefined) {
			throw new Error(`${definition.name} does not take a value`);
		}
		return { name: definition.name, nextIndex: index };
	}

	if (inlineValue !== undefined) {
		return { name: definition.name, value: inlineValue, nextIndex: index };
	}

	const nextValue = argv[index + 1];
	if (definition.valueMode === "optional") {
		if (isFlagToken(nextValue) || nextValue === undefined) {
			return { name: definition.name, nextIndex: index };
		}
		return { name: definition.name, value: nextValue, nextIndex: index + 1 };
	}

	if (nextValue === undefined) {
		throw new Error(`${definition.name} requires a value`);
	}
	return { name: definition.name, value: nextValue, nextIndex: index + 1 };
}

function applySupportedFlag(args: CompatArgs, parsedFlag: ParsedFlag): void {
	switch (parsedFlag.name as SupportedFlagName) {
		case "--append-system-prompt":
			args.appendSystemPrompt = parsedFlag.value;
			return;
		case "--append-system-prompt-file":
			args.appendSystemPromptFile = parsedFlag.value;
			return;
		case "--continue":
			args.continueLast = true;
			return;
		case "--model":
			args.model = parsedFlag.value;
			return;
		case "--fork-session":
			args.forkSession = true;
			return;
		case "--print":
			args.print = true;
			return;
		case "--resume":
			if (parsedFlag.value) {
				args.resumeTarget = parsedFlag.value;
			} else {
				args.resumePicker = true;
			}
			return;
		case "--session-id":
			args.sessionId = parsedFlag.value;
			return;
		case "--settings":
			args.settings = parsedFlag.value;
			return;
		case "--system-prompt":
			args.systemPrompt = parsedFlag.value;
			return;
		case "--system-prompt-file":
			args.systemPromptFile = parsedFlag.value;
			return;
		case "--help":
			printHelp("pi-claude-compat");
			process.exit(0);
			break;
		case "--version":
			console.log(VERSION);
			process.exit(0);
			break;
	}
}

function handleUnsupportedFlag(args: CompatArgs, arg: string, argv: string[], index: number): number | undefined {
	const unsupportedFlag = UNSUPPORTED_FLAG_BY_NAME.get(normalizeFlag(arg));
	if (!unsupportedFlag) {
		return undefined;
	}

	args.warnings.push(`Ignoring unsupported Claude flag: ${arg}`);
	if (!arg.includes("=") && unsupportedFlag.takesValue && !isFlagToken(argv[index + 1])) {
		return index + 1;
	}
	return index;
}

function parseArgs(argv: string[]): CompatArgs {
	const args = createCompatArgs();

	for (let index = 0; index < argv.length; index++) {
		const arg = argv[index]!;
		const definition = SUPPORTED_FLAG_BY_TOKEN.get(normalizeFlag(arg));
		if (definition) {
			const parsedFlag = parseFlagValue(arg, argv, index, definition);
			applySupportedFlag(args, parsedFlag);
			index = parsedFlag.nextIndex;
			continue;
		}

		if (arg.startsWith("-")) {
			const nextIndex = handleUnsupportedFlag(args, arg, argv, index);
			if (nextIndex !== undefined) {
				index = nextIndex;
				continue;
			}
			throw new Error(`Unsupported flag: ${arg}`);
		}

		args.messages.push(arg);
	}

	return args;
}

function validateArgs(args: CompatArgs): void {
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
	if (args.systemPromptFile && !args.print) {
		throw new Error("--system-prompt-file is only supported with --print");
	}
	if (args.appendSystemPromptFile && !args.print) {
		throw new Error("--append-system-prompt-file is only supported with --print");
	}
	if (args.print && args.messages.length === 0) {
		throw new Error("--print requires a prompt argument or piped stdin");
	}
}

function printWarnings(warnings: string[]): void {
	for (const warning of warnings) {
		console.error(`[pi-wrapper] Warning: ${warning}`);
	}
}

function isUuid(value: string): boolean {
	return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

async function readPipedStdin(): Promise<string | undefined> {
	if (process.stdin.isTTY) {
		return undefined;
	}

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

async function prependStdinMessage(args: CompatArgs): Promise<void> {
	const stdinContent = await readPipedStdin();
	if (stdinContent !== undefined) {
		args.messages.unshift(stdinContent);
	}
}

async function loadFileText(path: string, description: string): Promise<string> {
	try {
		return await readFile(path, "utf-8");
	} catch {
		throw new Error(`Failed to read ${description}: ${path}`);
	}
}

function resolveModel(modelRegistry: ModelRegistry, modelSpec: string | undefined): Model<Api> | undefined {
	if (!modelSpec) return undefined;

	const providerPrefixed = modelSpec.match(/^([^/]+)\/(.+)$/);
	if (providerPrefixed) {
		const [, provider, modelId] = providerPrefixed;
		const model = modelRegistry.find(provider, modelId);
		if (!model) {
			throw new Error(`Unknown model: ${modelSpec}`);
		}
		return model;
	}

	const matches = modelRegistry.getAll().filter((model) => model.id === modelSpec);
	if (matches.length === 0) {
		throw new Error(`Unknown model: ${modelSpec}`);
	}
	if (matches.length > 1) {
		throw new Error(`Ambiguous model id: ${modelSpec}. Use provider/model instead.`);
	}
	return matches[0];
}

function findResumeMatch(sessions: SessionInfo[], target: string): SessionInfo | undefined {
	const exactId = sessions.find((session) => session.id === target);
	if (exactId) return exactId;

	const exactNameMatches = sessions.filter((session) => session.name === target);
	if (exactNameMatches.length === 1) {
		return exactNameMatches[0];
	}
	if (exactNameMatches.length > 1) {
		throw new Error(`Multiple sessions share the name: ${target}`);
	}

	const prefixMatches = sessions.filter((session) => session.id.startsWith(target));
	if (prefixMatches.length === 1) {
		return prefixMatches[0];
	}
	if (prefixMatches.length > 1) {
		const ids = prefixMatches.map((s) => s.id).join(", ");
		throw new Error(`Multiple sessions match prefix "${target}": ${ids}`);
	}

	return undefined;
}

async function parseSettingsOverride(input: string): Promise<Partial<Settings>> {
	let content = input;
	if (existsSync(input)) {
		content = await loadFileText(input, "settings file");
	}

	let parsed: unknown;
	try {
		parsed = JSON.parse(content);
	} catch {
		throw new Error("--settings must be a JSON string or a path to a JSON file");
	}

	if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
		throw new Error("--settings must resolve to a JSON object");
	}

	return parsed as Partial<Settings>;
}

/**
 * Resource array fields that PackageManager.resolve() reads from
 * getGlobalSettings()/getProjectSettings() directly, bypassing
 * the merged settings view. These must be injected via
 * DefaultResourceLoader's additional*Paths options instead of
 * applyOverrides(), which only updates the merged view.
 */
const RESOURCE_ARRAY_KEYS = new Set(["packages", "extensions", "skills", "prompts", "themes"]);

interface ResourceOverrides {
	additionalPackages: string[];
	additionalExtensions: string[];
	additionalSkills: string[];
	additionalPrompts: string[];
	additionalThemes: string[];
}

function emptyResourceOverrides(): ResourceOverrides {
	return {
		additionalPackages: [],
		additionalExtensions: [],
		additionalSkills: [],
		additionalPrompts: [],
		additionalThemes: [],
	};
}

function extractResourceOverrides(overrides: Partial<Settings>): {
	scalarOverrides: Partial<Settings>;
	resourceOverrides: ResourceOverrides;
} {
	const scalarOverrides: Partial<Settings> = {};
	const resourceOverrides = emptyResourceOverrides();

	for (const [key, value] of Object.entries(overrides)) {
		if (value === undefined) continue;

		if (!RESOURCE_ARRAY_KEYS.has(key) || !Array.isArray(value)) {
			(scalarOverrides as Record<string, unknown>)[key] = value;
			continue;
		}

		switch (key) {
			case "packages":
				resourceOverrides.additionalPackages = (value as unknown[]).filter(
					(item): item is string => typeof item === "string",
				);
				break;
			case "extensions":
				resourceOverrides.additionalExtensions = value as string[];
				break;
			case "skills":
				resourceOverrides.additionalSkills = value as string[];
				break;
			case "prompts":
				resourceOverrides.additionalPrompts = value as string[];
				break;
			case "themes":
				resourceOverrides.additionalThemes = value as string[];
				break;
		}
	}

	return { scalarOverrides, resourceOverrides };
}

async function applySettingsOverrides(
	settingsManager: SettingsManager,
	settingsOverride: string | undefined,
): Promise<ResourceOverrides> {
	if (!settingsOverride) {
		return emptyResourceOverrides();
	}

	const overrides = await parseSettingsOverride(settingsOverride);
	const { scalarOverrides, resourceOverrides } = extractResourceOverrides(overrides);
	settingsManager.applyOverrides(scalarOverrides);
	return resourceOverrides;
}

async function resolvePromptOptions(args: CompatArgs): Promise<{
	systemPrompt: string | undefined;
	appendSystemPrompt: string | undefined;
}> {
	const systemPrompt = args.systemPromptFile
		? await loadFileText(args.systemPromptFile, "system prompt file")
		: args.systemPrompt;

	const appendParts: string[] = [];
	if (args.appendSystemPrompt) {
		appendParts.push(args.appendSystemPrompt);
	}
	if (args.appendSystemPromptFile) {
		appendParts.push(await loadFileText(args.appendSystemPromptFile, "append system prompt file"));
	}

	return {
		systemPrompt,
		appendSystemPrompt: appendParts.length > 0 ? appendParts.join("\n\n") : undefined,
	};
}

function showSessionPicker(cwd: string): Promise<string | null> {
	return new Promise((resolve) => {
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

async function resolveSessionManager(
	args: CompatArgs,
	cwd: string,
	settingsManager: SettingsManager,
): Promise<SessionManager> {
	if (args.sessionId) {
		if (!isUuid(args.sessionId)) {
			throw new Error("--session-id must be a valid UUID");
		}
		const sessions = await SessionManager.listAll();
		const existing = sessions.find((session) => session.id === args.sessionId);
		if (existing) return SessionManager.open(existing.path);
		const sm = SessionManager.create(cwd);
		sm.newSession({ id: args.sessionId });
		return sm;
	}

	if (args.resumeTarget) {
		const sessions = await SessionManager.listAll();
		const match = findResumeMatch(sessions, args.resumeTarget);
		if (!match) {
			throw new Error(`No session found for --resume ${args.resumeTarget}`);
		}
		if (args.forkSession) {
			return SessionManager.forkFrom(match.path, cwd);
		}
		return SessionManager.open(match.path);
	}

	if (args.resumePicker) {
		initTheme(settingsManager.getTheme(), false);
		const selectedPath = await showSessionPicker(cwd);
		if (!selectedPath) {
			process.exit(0);
		}
		if (args.forkSession) {
			return SessionManager.forkFrom(selectedPath, cwd);
		}
		return SessionManager.open(selectedPath);
	}

	if (args.continueLast) {
		if (args.forkSession) {
			const sessions = await SessionManager.list(cwd);
			if (sessions.length === 0) {
				throw new Error("No sessions found to fork");
			}
			return SessionManager.forkFrom(sessions[0].path, cwd);
		}
		return SessionManager.continueRecent(cwd);
	}

	return SessionManager.create(cwd);
}

async function runCompatCli(args: CompatArgs): Promise<void> {
	const cwd = process.cwd();
	const agentDir = getAgentDir();
	const settingsManager = SettingsManager.create(cwd, agentDir);
	const resourceOverrides = await applySettingsOverrides(settingsManager, args.settings);

	const authStorage = AuthStorage.create();
	const modelRegistry = new ModelRegistry(authStorage);
	const { systemPrompt, appendSystemPrompt } = await resolvePromptOptions(args);
	const resourceLoader = new DefaultResourceLoader({
		cwd,
		agentDir,
		settingsManager,
		systemPrompt,
		appendSystemPrompt,
		additionalExtensionPaths: [...resourceOverrides.additionalPackages, ...resourceOverrides.additionalExtensions],
		additionalSkillPaths: resourceOverrides.additionalSkills,
		additionalPromptTemplatePaths: resourceOverrides.additionalPrompts,
		additionalThemePaths: resourceOverrides.additionalThemes,
	});
	await resourceLoader.reload();

	const sessionManager = await resolveSessionManager(args, cwd, settingsManager);
	const { session, modelFallbackMessage } = await createAgentSession({
		cwd,
		agentDir,
		model: resolveModel(modelRegistry, args.model),
		authStorage,
		modelRegistry,
		resourceLoader,
		sessionManager,
		settingsManager,
	});

	if (args.print) {
		await runPrintMode(session, {
			mode: "text",
			messages: [],
			initialMessage: args.messages.join(" "),
		});
		process.exit(0);
	}

	const mode = new InteractiveMode(session, {
		modelFallbackMessage,
		initialMessage: args.messages[0],
		initialMessages: args.messages.slice(1),
	});
	await mode.run();
}

async function main(): Promise<void> {
	setGlobalDispatcher(new EnvHttpProxyAgent());
	setBedrockProviderModule(bedrockProviderModule);

	const args = parseArgs(process.argv.slice(2));
	printWarnings(args.warnings);
	await prependStdinMessage(args);
	validateArgs(args);
	await runCompatCli(args);
}

void main().catch((error: unknown) => {
	const message = error instanceof Error ? error.message : String(error);
	console.error(`[pi-wrapper] ${message}`);
	process.exit(1);
});
