import type { ExtensionAPI, SessionEntry } from "@mariozechner/pi-coding-agent";
import { parseSkillBlock, SkillInvocationMessageComponent } from "@mariozechner/pi-coding-agent";
import { Box } from "@mariozechner/pi-tui";
import { Type } from "@sinclair/typebox";
import { spawn } from "child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "fs";
import { homedir } from "os";
import { join } from "path";
import { parse as parseYaml } from "yaml";
import type { Logger } from "./claude-hooks.js";
import { type BaseHookParams, type ClaudeHookResult, registerClaudeHooks } from "./claude-hooks.js";
import { runInParallelStable } from "./ordered-parallel.js";

interface SkillHookDefinition {
	skillName: string;
	skillDir: string;
	event: string;
	matcher?: string;
	command: string;
}

// =========================================================================
// Skill activation tracking
// =========================================================================

interface SkillActivationState {
	activatedSkills: Set<string>;
	lastScannedMessageIndex: number;
}

function createSkillActivationState(): SkillActivationState {
	return {
		activatedSkills: new Set<string>(),
		lastScannedMessageIndex: 0,
	};
}

const ACTIVATED_SKILLS_ENTRY_TYPE = "pi-claude-activated-skills";

interface ActivatedSkillsData {
	skills: string[];
}

function resetSkillActivation(state: SkillActivationState): void {
	state.activatedSkills = new Set<string>();
	state.lastScannedMessageIndex = 0;
}

/**
 * Persist the current set of activated skills as a custom session entry.
 * Only call when the set actually changed (new skill added).
 */
function persistActivatedSkills(pi: ExtensionAPI, state: SkillActivationState): void {
	pi.appendEntry<ActivatedSkillsData>(ACTIVATED_SKILLS_ENTRY_TYPE, {
		skills: [...state.activatedSkills],
	});
}

/**
 * Restore activated skills from session branch entries.
 * The last matching entry wins (latest snapshot).
 */
function restoreActivatedSkills(state: SkillActivationState, entries: SessionEntry[]): void {
	state.activatedSkills = new Set<string>();
	state.lastScannedMessageIndex = 0;

	for (const entry of entries) {
		if (
			entry.type === "custom" &&
			"customType" in entry &&
			(entry as { customType: string }).customType === ACTIVATED_SKILLS_ENTRY_TYPE
		) {
			const data = (entry as { data?: ActivatedSkillsData }).data;
			if (data?.skills) {
				state.activatedSkills = new Set(data.skills);
			}
		}
	}
}

/**
 * Extract all skill names from a message's text content.
 * parseSkillBlock only matches if the entire text is a single skill block,
 * so we also scan for the pattern used by pi's _expandSkillCommand.
 */
function extractSkillsFromContent(content: string): string[] {
	const names: string[] = [];

	// Fast path: entire content is a single skill block
	const parsed = parseSkillBlock(content);
	if (parsed) {
		names.push(parsed.name);
		return names;
	}

	// Fallback: scan for multiple <skill name="..."> blocks
	const re = /<skill\s+name="([a-zA-Z0-9_-]+)"/g;
	let match: RegExpExecArray | null = re.exec(content);
	while (match !== null) {
		if (!names.includes(match[1]!)) {
			names.push(match[1]!);
		}
		match = re.exec(content);
	}
	return names;
}

/**
 * Scan skill directories, parse SKILL.md frontmatter, return hook definitions.
 */
function loadSkillHooks(logger: Logger, cwd: string): SkillHookDefinition[] {
	const results: SkillHookDefinition[] = [];

	const dirs: string[] = [];

	// User-level skills
	const userDir = join(homedir(), ".pi", "agent", "skills");
	if (existsSync(userDir)) {
		dirs.push(userDir);
	}

	// Project-level skills (mirrors user-level ~/.pi/agent/skills structure)
	const projectDir = join(cwd, ".pi", "agent", "skills");
	if (existsSync(projectDir)) {
		dirs.push(projectDir);
	}

	for (const baseDir of dirs) {
		let entries: string[];
		try {
			entries = readdirSync(baseDir);
		} catch (err) {
			logger.logError(`Failed to read directory ${baseDir}:`, err);
			continue;
		}

		for (const entry of entries) {
			const skillDir = join(baseDir, entry);
			try {
				if (!statSync(skillDir).isDirectory()) continue;
			} catch {
				continue;
			}

			const skillMd = join(skillDir, "SKILL.md");
			if (!existsSync(skillMd)) continue;

			try {
				const content = readFileSync(skillMd, "utf-8");
				const hooks = parseFrontmatterHooks(content, entry, skillDir);
				results.push(...hooks);
			} catch (err) {
				logger.logError(`Failed to parse ${skillMd}:`, err);
			}
		}
	}

	return results;
}

/**
 * Extract YAML frontmatter string from markdown content (between --- markers).
 */
function extractFrontmatter(content: string): string | null {
	const lines = content.split("\n");
	if (lines[0]?.trim() !== "---") return null;

	for (let i = 1; i < lines.length; i++) {
		if (lines[i]?.trim() === "---") {
			return lines.slice(1, i).join("\n");
		}
	}
	return null;
}

/**
 * Parse YAML frontmatter from SKILL.md content and extract hook definitions.
 *
 * Expected frontmatter structure:
 *   hooks:
 *     PostToolUse:
 *       - matcher: "Bash"
 *         hooks:
 *           - type: command
 *             command: "./my-script.sh"
 */
function parseFrontmatterHooks(content: string, skillName: string, skillDir: string): SkillHookDefinition[] {
	const results: SkillHookDefinition[] = [];

	const fmText = extractFrontmatter(content);
	if (!fmText) return results;

	let frontmatter: any;
	try {
		frontmatter = parseYaml(fmText);
	} catch {
		return results;
	}

	if (!frontmatter || typeof frontmatter !== "object" || !frontmatter.hooks) {
		return results;
	}

	const hooks = frontmatter.hooks;
	if (typeof hooks !== "object") return results;

	for (const [eventName, entries] of Object.entries(hooks)) {
		if (!Array.isArray(entries)) continue;

		for (const entry of entries) {
			if (!entry || typeof entry !== "object") continue;

			const matcher = typeof entry.matcher === "string" ? entry.matcher : undefined;
			const entryHooks = Array.isArray(entry.hooks) ? entry.hooks : [];

			for (const hook of entryHooks) {
				if (!hook || typeof hook !== "object") continue;
				if (hook.type === "command" && typeof hook.command === "string") {
					results.push({
						skillName,
						skillDir,
						event: eventName,
						matcher,
						command: hook.command,
					});
				}
			}
		}
	}

	return results;
}

/**
 * Run matching skill hooks for an event. Returns array of additionalContext strings.
 */
/**
 * Test whether a hook matcher pattern matches a tool name.
 * Supports regex patterns (including alternation like "Bash|Write|Edit"),
 * wildcard "*", and exact matches. Case-insensitive.
 */
export function matchesToolName(matcher: string, toolName: string): boolean {
	try {
		const pattern = matcher === "*" ? ".*" : matcher;
		return new RegExp(`^(?:${pattern})$`, "i").test(toolName);
	} catch {
		return matcher.toLowerCase() === toolName.toLowerCase();
	}
}

async function runSkillHooks(
	hooks: SkillHookDefinition[],
	event: string,
	toolName: string | undefined,
	input: any,
	activatedSkills: Set<string>,
	logger: Logger,
	baseEnv: Record<string, string | undefined>,
	timeoutMs: number,
): Promise<string[]> {
	const isToolEvent = event === "PreToolUse" || event === "PostToolUse";

	const matching = hooks.filter((h) => {
		if (h.event !== event) return false;
		// Only run hooks for activated skills
		if (!activatedSkills.has(h.skillName)) return false;
		if (isToolEvent && h.matcher && toolName) {
			return matchesToolName(h.matcher, toolName);
		}
		// If no matcher, matches all tools for that event
		return true;
	});

	return runInParallelStable(matching, async (hook) => {
		try {
			return await executeHookCommand(hook, input, logger, baseEnv, timeoutMs);
		} catch (err) {
			logger.logError(`Error running hook ${hook.skillName}/${hook.event}:`, err);
			return null;
		}
	});
}

/**
 * Execute a single hook command, returning additionalContext if any.
 */
function executeHookCommand(
	hook: SkillHookDefinition,
	input: any,
	logger: Logger,
	baseEnv: Record<string, string | undefined>,
	timeoutMs: number,
): Promise<string | null> {
	return new Promise((resolvePromise) => {
		const childEnv: Record<string, string | undefined> = { ...baseEnv };
		if (input?.project_dir) {
			childEnv.CLAUDE_PROJECT_DIR = input.project_dir;
		}

		const child = spawn(hook.command, {
			shell: true,
			env: childEnv,
			timeout: timeoutMs,
			cwd: hook.skillDir,
		});

		let outputData = "";
		let stderrData = "";

		child.stdout.on("data", (chunk: Buffer) => {
			outputData += chunk.toString();
		});

		child.stderr.on("data", (chunk: Buffer) => {
			stderrData += chunk.toString();
		});

		child.on("close", (code) => {
			if (code === null) {
				// Killed by signal (e.g., timeout SIGTERM)
				logger.logWarn(`Command killed by signal for ${hook.skillName}/${hook.event}: ${stderrData}`);
				resolvePromise(null);
				return;
			}
			if (code !== 0) {
				logger.logError(`Command failed (code ${code}) for ${hook.skillName}/${hook.event}: ${stderrData}`);
				resolvePromise(null);
				return;
			}

			try {
				const json = JSON.parse(outputData);
				const ctx = json?.hookSpecificOutput?.additionalContext;
				if (ctx && typeof ctx === "string" && ctx.trim() !== "") {
					resolvePromise(ctx);
				} else {
					resolvePromise(null);
				}
			} catch {
				// Output isn't valid JSON — not an error, just no context
				resolvePromise(null);
			}
		});

		child.on("error", (err) => {
			logger.logError(`Spawn error for ${hook.skillName}/${hook.event}:`, err);
			resolvePromise(null);
		});

		child.stdin.on("error", (err: NodeJS.ErrnoException) => {
			if (err.code === "EPIPE" || err.code === "ERR_STREAM_DESTROYED") return;
			logger.logError(`stdin error for ${hook.skillName}/${hook.event}:`, err);
		});

		child.stdin.write(JSON.stringify(input));
		child.stdin.end();
	});
}

// =========================================================================
// registerSkillHooks — internally uses registerClaudeHooks
// =========================================================================

/**
 * Register skill hook handlers via registerClaudeHooks callbacks.
 * Manages skill activation state, hook loading, and context injection internally.
 * Returns { dispose() } for cleanup.
 */
const DEFAULT_SKILL_HOOK_TIMEOUT_MS = 10_000;

function resolveSkillHookTimeoutMs(env: Record<string, string | undefined>): number {
	const raw = Number(env.PI_CLAUDE_SKILL_HOOK_TIMEOUT_MS ?? DEFAULT_SKILL_HOOK_TIMEOUT_MS);
	return Number.isFinite(raw) && raw > 0 ? Math.floor(raw) : DEFAULT_SKILL_HOOK_TIMEOUT_MS;
}

export function registerSkillHooks(
	pi: ExtensionAPI,
	logger: Logger,
	env: Record<string, string | undefined> = process.env,
	cwd: string = process.cwd(),
): { dispose: () => void } {
	let skillHookDefs: SkillHookDefinition[] = [];
	const skillState = createSkillActivationState();
	const timeoutMs = resolveSkillHookTimeoutMs(env);

	/**
	 * Run matching skill hooks and return a ClaudeHookResult with merged additionalContext.
	 */
	async function runAndResult(
		event: string,
		toolName: string | undefined,
		payload: BaseHookParams,
	): Promise<ClaudeHookResult | undefined> {
		try {
			const contexts = await runSkillHooks(
				skillHookDefs,
				event,
				toolName,
				payload,
				skillState.activatedSkills,
				logger,
				env,
				timeoutMs,
			);
			if (contexts.length > 0) {
				return {
					hookSpecificOutput: { additionalContext: contexts.join("\n\n") },
				};
			}
		} catch (err) {
			logger.logError(`${event} skill hooks error:`, err);
		}
	}

	// -- load_skill tool: explicit skill activation signal for the model --
	//
	// Pi's system prompt tells the model "Use the read tool to load a skill's file",
	// but read gives no activation signal. This tool replaces that workflow:
	// the model calls load_skill(name) → we read SKILL.md, activate hooks, return content.

	pi.registerTool({
		name: "load_skill",
		label: "Load Skill",
		description:
			"Load a skill by name. Reads the skill's SKILL.md file, activates its hooks, " +
			"and returns the skill content. Use this instead of reading SKILL.md directly " +
			"when you want to activate a skill from <available_skills>.",
		promptSnippet: "Load and activate a skill by name from <available_skills>",
		promptGuidelines: [
			"When a task matches a skill's description in <available_skills>, use load_skill instead of read to load it",
		],
		parameters: Type.Object({
			name: Type.String({
				description: "Skill name from <available_skills> (e.g. 'my-skill')",
			}),
		}),
		renderCall() {
			return new Box(0, 0);
		},
		renderResult() {
			return new Box(0, 0);
		},
		async execute(_toolCallId, params, _signal, _onUpdate, _ctx) {
			const { name } = params;

			// Validate skill name to prevent path traversal
			if (!/^[a-zA-Z0-9_-]+$/.test(name)) {
				return {
					content: [
						{
							type: "text" as const,
							text: `Invalid skill name "${name}". Names may only contain a-z, 0-9, hyphens, and underscores.`,
						},
					],
					details: undefined,
				};
			}

			// Find skill in loaded hook definitions
			const skillDef = skillHookDefs.find((h) => h.skillName === name);
			let skillDir = skillDef?.skillDir;

			// Fallback: scan known skill directories
			if (!skillDir) {
				const userDir = join(homedir(), ".pi", "agent", "skills", name);
				const projectDir = join(cwd, ".pi", "agent", "skills", name);
				if (existsSync(join(userDir, "SKILL.md"))) {
					skillDir = userDir;
				} else if (existsSync(join(projectDir, "SKILL.md"))) {
					skillDir = projectDir;
				}
			}

			if (!skillDir || !existsSync(join(skillDir, "SKILL.md"))) {
				return {
					content: [
						{
							type: "text" as const,
							text: `Skill "${name}" not found. Check <available_skills> for valid names.`,
						},
					],
					details: undefined,
				};
			}

			const skillMdPath = join(skillDir, "SKILL.md");

			// Read and strip frontmatter (same as pi's _expandSkillCommand)
			const raw = readFileSync(skillMdPath, "utf-8");
			const body = raw.replace(/^---\n[\s\S]*?\n---\n*/, "").trim();

			// Build <skill> block — same format as pi's _expandSkillCommand
			const skillBlock = `<skill name="${name}" location="${skillMdPath}">\nReferences are relative to ${skillDir}.\n\n${body}\n</skill>`;

			// Inject as custom message — rendered by SkillInvocationMessageComponent,
			// delivered as steer so model sees it in context.
			// Skill activation is handled by the context event scanner when it
			// detects the <skill> block in messages — single source of truth.
			pi.sendMessage(
				{
					customType: "skill-loaded",
					content: skillBlock,
					display: true,
					details: { skillName: name, skillDir },
				},
				{ deliverAs: "steer" },
			);

			// Tool result is minimal — the real content is in the custom message
			return {
				content: [{ type: "text" as const, text: `Skill "${name}" loaded.` }],
				details: undefined,
			};
		},
	});

	// skill-loaded message renderer: reuses pi's native SkillInvocationMessageComponent --
	pi.registerMessageRenderer("skill-loaded", (message, { expanded }) => {
		const content = typeof message.content === "string" ? message.content : "";
		const parsed = parseSkillBlock(content);
		if (!parsed) return undefined;
		const component = new SkillInvocationMessageComponent(parsed);
		component.setExpanded(expanded);
		return component;
	});

	// -- Session restore handlers (direct pi events for ctx.sessionManager access) --

	const restoreFromSession = (_event: unknown, ctx: { sessionManager: { getBranch(): SessionEntry[] } }) => {
		try {
			const branch = ctx.sessionManager.getBranch();
			restoreActivatedSkills(skillState, branch);
			skillHookDefs = loadSkillHooks(logger, cwd);
		} catch (err) {
			logger.logError("Failed to restore activated skills:", err);
		}
	};

	pi.on("session_start", restoreFromSession);
	pi.on("session_switch", restoreFromSession);
	pi.on("session_fork", restoreFromSession);

	// Scan context messages for expanded <skill> blocks using pi's parseSkillBlock.
	// This catches skills activated via any path (steer, followUp, direct prompt)
	// and is the authoritative signal — only matches blocks generated by pi's
	// _expandSkillCommand, not arbitrary SKILL.md reads.
	// Registered directly on pi since "context" is a pi-native event.
	pi.on("context", async (event) => {
		let changed = false;
		for (let i = skillState.lastScannedMessageIndex; i < event.messages.length; i++) {
			const msg = event.messages[i] as any;
			if (!msg) continue;
			const rawContent = msg.content;
			const content =
				typeof rawContent === "string"
					? rawContent
					: Array.isArray(rawContent)
						? rawContent
								.filter((b: any) => typeof b === "string" || (b && typeof b.text === "string"))
								.map((b: any) => (typeof b === "string" ? b : b.text))
								.join("")
						: "";
			if (!content) continue;

			for (const skillName of extractSkillsFromContent(content)) {
				if (!skillState.activatedSkills.has(skillName)) {
					skillState.activatedSkills.add(skillName);
					changed = true;
				}
			}
		}
		skillState.lastScannedMessageIndex = event.messages.length;
		if (changed) {
			persistActivatedSkills(pi, skillState);
		}
	});

	const handle = registerClaudeHooks(pi, logger, {
		onSessionStart: async (_ctx, params) => {
			return runAndResult("SessionStart", undefined, params);
		},

		onUserPromptSubmit: async (_ctx, params) => {
			return runAndResult("UserPromptSubmit", undefined, params);
		},

		onPreToolUse: async (_ctx, params) => {
			return runAndResult("PreToolUse", params.tool_name, params);
		},

		onPostToolUse: async (_ctx, params) => {
			return runAndResult("PostToolUse", params.tool_name, params);
		},
	});

	return {
		dispose: () => {
			handle.dispose();
			skillHookDefs = [];
			resetSkillActivation(skillState);
		},
	};
}
