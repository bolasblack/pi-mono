import type { ExtensionAPI, ExtensionContext } from "@mariozechner/pi-coding-agent";
import { registerClaudeHooks, registerHookContextRenderer } from "./claude-hooks.js";
import { createLogger } from "./logger.js";
import {
	extractSettingsFromArgv,
	hasSettingsHooksInMap,
	parseSettingsHooks,
	runSettingsHooks,
} from "./settings-hooks.js";
import { registerSkillHooks } from "./skill-hooks.js";

export default function (pi: ExtensionAPI) {
	registerHookContextRenderer(pi);

	let currentSessionId: string | null = null;
	const coreLogger = createLogger(() => currentSessionId, "[claude-hooks]");
	const skillLogger = createLogger(() => currentSessionId, "[skill-hooks]");

	const updateSessionId = (_event: unknown, ctx: ExtensionContext) => {
		currentSessionId = (ctx.sessionManager as any).getSessionId?.() || null;
	};
	pi.on("session_start", updateSessionId);
	pi.on("session_switch", updateSessionId);
	pi.on("session_fork", updateSessionId);

	registerSkillHooks(pi, skillLogger);

	// Note: both registerSkillHooks() and the settings-hooks block below call
	// registerClaudeHooks() independently. This means pi events like tool_call
	// and input get two handler sets. This is intentional — pi iterates all
	// handlers within an extension sequentially (see runner.ts emitToolCall),
	// and short-circuits on block/handled, so the behavior is correct.
	// Skill hooks and settings hooks operate on disjoint data sources.

	// Parse settings hooks once at init — no global cache needed.
	const settingsValues = extractSettingsFromArgv(process.argv);
	const hooksMap = parseSettingsHooks(settingsValues);

	if (!hasSettingsHooksInMap(hooksMap)) {
		return;
	}

	const run = async (eventName: string, params: Record<string, any>) => {
		return (await runSettingsHooks(hooksMap, eventName, params, process.env as Record<string, string>)) || undefined;
	};

	registerClaudeHooks(pi, coreLogger, {
		onSessionStart: async (_ctx, params) => run("SessionStart", params),
		onUserPromptSubmit: async (_ctx, params) => run("UserPromptSubmit", params),
		onPreToolUse: async (_ctx, params) => run("PreToolUse", params),
		onPostToolUse: async (_ctx, params) => run("PostToolUse", params),
		onStop: async (_ctx, params) => run("Stop", params),
		onNotification: async (_ctx, params) => {
			await run("Notification", params);
		},
		onPreCompact: async (_ctx, params) => {
			await run("PreCompact", params);
		},
		onSessionEnd: async (_ctx, params) => {
			await run("SessionEnd", params);
		},
	});
}
