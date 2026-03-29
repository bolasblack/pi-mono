/**
 * Claude Code hook emulation — bidirectional translation layer.
 *
 * IN:  pi event → Claude Code hook payload → callback(payload)
 * OUT: callback returns Claude Code hook result → translated to pi event result
 *
 * Has no knowledge of any project-specific daemon implementation.
 *
 * Design: hook additionalContext piggybacks on existing triggers (user message,
 * tool result) instead of injecting steering messages. See AGD-012.
 *
 * This module provides:
 * 1. registerClaudeHooks(pi, logger, callbacks) — bidirectional translator.
 * 2. registerHookContextRenderer() — registers the hook-context message type.
 * 3. Utility functions (hookPayload, injectHookContext, getTranscriptPath).
 * 4. collapsedLine() — shared collapsed-line renderer for custom message types.
 */
import type { ExtensionAPI, ExtensionContext, Theme } from "@mariozechner/pi-coding-agent";
import { Box, getKeybindings, Text } from "@mariozechner/pi-tui";
import { homedir } from "os";
import { join } from "path";
import { createListenerRegistry } from "./listener-registry.js";
// =========================================================================
// Logger
// =========================================================================

export interface Logger {
	log: (...args: any[]) => void;
	logWarn: (...args: any[]) => void;
	logError: (...args: any[]) => void;
}

// =========================================================================
// Types
// =========================================================================

type NotifyFn = (msg: string, type: string) => void;

/**
 * Claude Code hook result format — returned by callbacks.
 * The translator converts these to pi-specific return values.
 */
export interface ClaudeHookResult {
	continue?: boolean;
	stopReason?: string;
	reason?: string;
	decision?: string;
	systemMessage?: string;
	hookSpecificOutput?: {
		additionalContext?: string;
		permissionDecision?: string;
		permissionDecisionReason?: string;
	};
}

// =========================================================================
// Hook parameter types — one per Claude Code hook event
// =========================================================================

/** Common fields present in every hook payload. */
export interface BaseHookParams {
	session_id: string;
	transcript_path: string;
	cwd: string;
	project_dir: string;
	hook_event_name: string;
}

export interface SessionStartParams extends BaseHookParams {
	hook_event_name: "SessionStart";
	message: string;
}

export interface UserPromptSubmitParams extends BaseHookParams {
	hook_event_name: "UserPromptSubmit";
	prompt: string;
}

export interface PreToolUseParams extends BaseHookParams {
	hook_event_name: "PreToolUse";
	tool_name: string;
	tool_input: Record<string, unknown>;
}

export interface PostToolUseParams extends BaseHookParams {
	hook_event_name: "PostToolUse";
	tool_name: string;
	tool_input: Record<string, unknown>;
}

export interface StopParams extends BaseHookParams {
	hook_event_name: "Stop";
	message: string;
}

export interface NotificationParams extends BaseHookParams {
	hook_event_name: "Notification";
	message: string;
}

export interface PreCompactParams extends BaseHookParams {
	hook_event_name: "PreCompact";
	message: string;
}

export interface SessionEndParams extends BaseHookParams {
	hook_event_name: "SessionEnd";
	message: string;
}

/** Union of all hook parameter types. */
export type HookParams =
	| SessionStartParams
	| UserPromptSubmitParams
	| PreToolUseParams
	| PostToolUseParams
	| StopParams
	| NotificationParams
	| PreCompactParams
	| SessionEndParams;

export interface ClaudeHookCallbacks {
	/** SessionStart hook event. */
	onSessionStart?: (
		ctx: ExtensionContext,
		params: SessionStartParams,
	) => ClaudeHookResult | undefined | Promise<ClaudeHookResult | undefined>;
	/** UserPromptSubmit hook event. */
	onUserPromptSubmit?: (
		ctx: ExtensionContext,
		params: UserPromptSubmitParams,
	) => ClaudeHookResult | undefined | Promise<ClaudeHookResult | undefined>;
	/** PreToolUse hook event. */
	onPreToolUse?: (
		ctx: ExtensionContext,
		params: PreToolUseParams,
	) => ClaudeHookResult | undefined | Promise<ClaudeHookResult | undefined>;
	/** PostToolUse hook event. */
	onPostToolUse?: (
		ctx: ExtensionContext,
		params: PostToolUseParams,
	) => ClaudeHookResult | undefined | Promise<ClaudeHookResult | undefined>;
	/** Stop hook event. */
	onStop?: (
		ctx: ExtensionContext,
		params: StopParams,
	) => ClaudeHookResult | undefined | Promise<ClaudeHookResult | undefined>;
	/** Notification hook event (agent_streaming / agent_idle). */
	onNotification?: (ctx: ExtensionContext, params: NotificationParams) => void | Promise<void>;
	/** PreCompact hook event. */
	onPreCompact?: (ctx: ExtensionContext, params: PreCompactParams) => void | Promise<void>;
	/** SessionEnd hook event. */
	onSessionEnd?: (ctx: ExtensionContext, params: SessionEndParams) => void | Promise<void>;
}

// =========================================================================
// Transcript path
// =========================================================================

export function getTranscriptPath(sessionId: string): string {
	const piCacheDir = join(homedir(), ".pi", "claude-code-compatible-transcripts");
	return join(piCacheDir, `${sessionId}.jsonl`);
}

// =========================================================================
// Hook payload builder
// =========================================================================

export function hookPayload(
	sessionId: string,
	eventName: string,
	extra?: Record<string, any>,
	cwd?: string,
	projectDir?: string,
): BaseHookParams & Record<string, any> {
	const resolvedCwd = cwd ?? process.cwd();
	return {
		session_id: sessionId,
		transcript_path: getTranscriptPath(sessionId),
		cwd: resolvedCwd,
		project_dir: projectDir ?? resolvedCwd,
		hook_event_name: eventName,
		...extra,
	};
}

// =========================================================================
// Context injection — distinct message type for UI + LLM separation
// =========================================================================

export function injectHookContext(pi: ExtensionAPI, additionalContext: string, hookEvent: string, display = true) {
	pi.sendMessage(
		{
			customType: "hook-context",
			content: `[Hook: ${hookEvent}]\n${additionalContext}`,
			display,
			details: { source: "pi-claude", hookEvent },
		},
		{ deliverAs: "steer" },
	);
}

// =========================================================================
// Shared collapsed-line renderer
// =========================================================================

/** Collapsed-line renderer: prefix + up to 80 visible chars + expand hint. */
export function collapsedLine(
	prefix: string,
	content: string,
	theme: Theme,
	options?: { alwaysShowHint?: boolean },
): string {
	const MAX_CHARS = 80;
	const stripAnsi = (s: string) => s.replace(/\x1b\[[0-9;]*m/g, "");
	const flat = content.replace(/\n/g, " ");
	const flatPlain = stripAnsi(flat);
	const prefixPlain = stripAnsi(prefix);
	const budget = MAX_CHARS - prefixPlain.length;
	const truncated = budget > 0 && flatPlain.length > budget;
	const needsHint = options?.alwaysShowHint || truncated || content.includes("\n");
	// Slice by visible character count, preserving ANSI escape sequences
	let preview = "";
	if (budget > 0) {
		let visible = 0;
		const ansiRe = /\x1b\[[0-9;]*m/g;
		let lastIndex = 0;
		let match: RegExpExecArray | null = ansiRe.exec(flat);
		while (match !== null) {
			const textBefore = flat.slice(lastIndex, match.index);
			const take = Math.min(textBefore.length, budget - visible);
			preview += textBefore.slice(0, take);
			visible += take;
			if (visible >= budget) break;
			preview += match[0]; // keep ANSI code
			lastIndex = match.index + match[0].length;
			match = ansiRe.exec(flat);
		}
		if (visible < budget) {
			const remaining = flat.slice(lastIndex);
			preview += remaining.slice(0, budget - visible);
		}
	}

	let line = `${prefix} ${theme.fg("customMessageText", preview)}`;
	if (needsHint) {
		const kb = getKeybindings();
		const key = kb.getKeys("app.tools.expand" as never)[0] || "ctrl+o";
		line += theme.fg("muted", " … (") + theme.fg("dim", key) + theme.fg("muted", " to expand)");
	}
	return line;
}

// =========================================================================
// registerHookContextRenderer — register once at plugin init
// =========================================================================

/**
 * Summarize hook status lines for collapsed view.
 * Input lines like:
 *   [agent-centric/hooks/PostToolUse] ✓ `full command...`
 *   [hooks/PreToolUse] ✗ `full command...` — error
 * Output: "✓ agent-centric[1], ✗ agent-centric[2]"
 */
function summarizeHookLines(content: string): string {
	const bracketRe = /^\[([^\]]+)\]\s+([✓✗⚠])/;
	const lines = content.split("\n").filter((l) => l.trim());
	const items: string[] = [];
	const counts = new Map<string, number>();

	for (const line of lines) {
		const m = bracketRe.exec(line);
		if (m) {
			const [, label, status] = m;
			// Extract the first path component as the short name
			const name = label!.split("/")[0]!;
			const idx = (counts.get(name) ?? 0) + 1;
			counts.set(name, idx);
			items.push(`${status} ${name}[${idx}]`);
		}
	}

	return items.length > 0 ? items.join(", ") : content;
}

export function registerHookContextRenderer(pi: ExtensionAPI): void {
	pi.registerMessageRenderer("hook-context", (message, { expanded }, theme) => {
		const hookEvent = (message.details as any)?.hookEvent || "Hook";
		const label = theme.fg("dim", `⚙ [${hookEvent}]`);
		const content = typeof message.content === "string" ? message.content.replace(/^\[Hook: [^\]]+\]\n/, "") : "";

		const box = new Box(1, 1, (t: string) => theme.bg("customMessageBg", t));

		if (expanded) {
			box.addChild(new Text(label, 0, 0));
			box.addChild(new Text(theme.fg("customMessageText", `\n${content}`), 0, 0));
		} else {
			const summary = summarizeHookLines(content);
			box.addChild(new Text(collapsedLine(label, summary, theme, { alwaysShowHint: true }), 0, 0));
		}

		return box;
	});
}

// =========================================================================
// Result translation helpers — Claude Code hook result → pi event result
// =========================================================================

// =========================================================================
// registerClaudeHooks — bidirectional pi ↔ Claude Code hook translator
// =========================================================================

export function registerClaudeHooks(
	pi: ExtensionAPI,
	logger: Logger,
	callbacks?: ClaudeHookCallbacks,
): { dispose: () => void } {
	let cb: ClaudeHookCallbacks | null = callbacks ? { ...callbacks } : null;
	let active = true;
	const listeners = createListenerRegistry(pi as any);

	// Stop hook loop guard — prevents infinite stop→continue→stop cycles
	let stopHookActive = false;

	// -- Helpers -----------------------------------------------------------

	function getSessionId(ctx: ExtensionContext): string | null {
		return (ctx.sessionManager as any).getSessionId?.() || null;
	}

	function getNotify(ctx: ExtensionContext): NotifyFn | null {
		return ctx.hasUI ? (m: string, t: string) => ctx.ui.notify(m, t as "info" | "warning" | "error") : null;
	}

	function getHookCwd(ctx: ExtensionContext): string {
		return ctx.cwd;
	}

	// -- Context -----------------------------------------------------------

	// -- Session start / switch --------------------------------------------

	const sessionHandler = async (ctx: ExtensionContext, message: string) => {
		if (!active || !cb) return;
		const sid = getSessionId(ctx);
		if (!sid) return;

		// onSessionStart — Claude Code hook event
		const params = hookPayload(sid, "SessionStart", { message }, getHookCwd(ctx)) as SessionStartParams;
		try {
			const result = await cb.onSessionStart?.(ctx, params);
			const additional = result?.hookSpecificOutput?.additionalContext;
			if (additional && additional.trim() !== "") {
				// AGD-012: appendMessage — no deliverAs, no triggerTurn.
				// Context is seen by LLM when the user's first message triggers a turn.
				pi.sendMessage({
					customType: "hook-context",
					content: `[Hook: SessionStart]\n${additional}`,
					display: false,
					details: { source: "pi-claude", hookEvent: "SessionStart" },
				});
			}
		} catch (err) {
			logger.logError("SessionStart callback error:", err);
		}
	};

	listeners.add("session_start", async (_event, ctx) => sessionHandler(ctx, "Session started"));
	listeners.add("session_switch", async (_event, ctx) => sessionHandler(ctx, "Session switched"));
	listeners.add("session_fork", async (_event, ctx) => sessionHandler(ctx, "Session forked"));

	// -- UserPromptSubmit (pi: "input") ------------------------------------
	//
	// Claude Code result → pi result:
	//   { continue: false } → { action: "handled" } + notify
	//   { additionalContext } → injectHookContext
	//

	listeners.add("input", async (event, ctx) => {
		if (!active) return;
		const sid = getSessionId(ctx);
		if (!sid) return;
		const notify = getNotify(ctx);

		const payload = hookPayload(
			sid,
			"UserPromptSubmit",
			{
				prompt: event.text,
			},
			getHookCwd(ctx),
		) as UserPromptSubmitParams;
		try {
			const result = await cb?.onUserPromptSubmit?.(ctx, payload);
			if (result) {
				if (result.continue === false) {
					notify?.(result.stopReason || "Blocked by hook", "error");
					return { action: "handled" as const };
				}
				const additional = result.hookSpecificOutput?.additionalContext;
				if (additional && additional.trim() !== "") {
					// AGD-012: appendMessage — no deliverAs, no triggerTurn.
					// Context appears before the user message that triggers the LLM turn.
					pi.sendMessage({
						customType: "hook-context",
						content: `[Hook: UserPromptSubmit]\n${additional}`,
						display: true,
						details: { source: "pi-claude", hookEvent: "UserPromptSubmit" },
					});
				}
			}
		} catch (err) {
			logger.logError("UserPromptSubmit callback error:", err);
		}
	});

	// -- PreToolUse (pi: "tool_call") --------------------------------------
	//
	// Claude Code result → pi result:
	//   { continue: false } → { block: true, reason }
	//   { permissionDecision: "deny" } → { block: true, reason }
	//   { additionalContext } → buffered, flushed with PostToolUse into tool_result content
	//

	// AGD-012: Buffer PreToolUse additionalContext keyed by toolCallId.
	// Flushed during the corresponding PostToolUse handler.
	const pendingPreToolContext = new Map<string, string>();

	listeners.add("tool_call", async (event, ctx) => {
		if (!active) return;
		const sid = getSessionId(ctx);
		if (!sid) return;

		const payload = hookPayload(
			sid,
			"PreToolUse",
			{
				tool_name: event.toolName,
				tool_input: event.input,
			},
			getHookCwd(ctx),
		) as PreToolUseParams;
		try {
			const result = await cb?.onPreToolUse?.(ctx, payload);
			if (result) {
				if (result.continue === false) {
					return {
						block: true,
						reason: result.stopReason || result.reason || "Blocked by hook",
					};
				}
				const permDecision = result.hookSpecificOutput?.permissionDecision;
				if (permDecision === "deny") {
					const reason = result.hookSpecificOutput?.permissionDecisionReason || result.reason || "Denied by hook";
					return { block: true, reason };
				}
				// Buffer additionalContext for the corresponding tool_result
				const additional = result.hookSpecificOutput?.additionalContext;
				if (additional && additional.trim() !== "") {
					pendingPreToolContext.set(event.toolCallId, additional);
				}
			}
		} catch (err) {
			logger.logError("PreToolUse callback error:", err);
		}
	});

	// -- PostToolUse (pi: "tool_result") -----------------------------------
	//
	// AGD-012: Flushes buffered PreToolUse context + own additionalContext
	// by appending to the tool result content. No steering, no extra LLM turn.
	//

	listeners.add("tool_result", async (event, ctx) => {
		if (!active) return;
		const sid = getSessionId(ctx);
		if (!sid) return;

		const payload = hookPayload(
			sid,
			"PostToolUse",
			{
				tool_name: event.toolName,
				tool_input: event.input,
			},
			getHookCwd(ctx),
		) as PostToolUseParams;

		const parts: string[] = [];

		// Collect buffered PreToolUse context
		const pre = pendingPreToolContext.get(event.toolCallId);
		if (pre) {
			parts.push(pre);
			pendingPreToolContext.delete(event.toolCallId);
		}

		// Run PostToolUse hook
		try {
			const result = await cb?.onPostToolUse?.(ctx, payload);
			const post = result?.hookSpecificOutput?.additionalContext;
			if (post && post.trim() !== "") {
				parts.push(post);
			}
		} catch (err) {
			logger.logError("PostToolUse callback error:", err);
		}

		// Append to tool result content — LLM sees context in the same turn
		if (parts.length > 0) {
			return {
				content: [...event.content, { type: "text" as const, text: `\n${parts.join("\n")}` }],
			};
		}
	});

	// -- Stop + Notification:idle (pi: "agent_end") ------------------------
	//
	// Claude Code result → pi action:
	//   { continue: false } or { decision: "block" } → sendUserMessage(reason) to continue agent
	//   { systemMessage } → notify
	//

	listeners.add("agent_end", async (_event, ctx) => {
		if (!active) return;

		const sid = getSessionId(ctx);
		if (!sid) return;
		const notify = getNotify(ctx);

		// Stop hook
		if (!stopHookActive) {
			const stopPayload = hookPayload(
				sid,
				"Stop",
				{
					message: "Agent loop ended",
				},
				getHookCwd(ctx),
			) as StopParams;
			try {
				const result = await cb?.onStop?.(ctx, stopPayload);
				if (result) {
					const shouldBlock = result.decision === "block" || result.continue === false;

					if (shouldBlock) {
						const reason = result.reason || result.stopReason || "Stop hook requested continuation";

						stopHookActive = true;

						if (result.systemMessage) {
							notify?.(result.systemMessage, "warning");
						}

						pi.sendUserMessage(reason, { deliverAs: "followUp" });
						return;
					}

					if (result.systemMessage) {
						notify?.(result.systemMessage, "info");
					}
				}
			} catch (err) {
				logger.logError("Stop callback error:", err);
			}
		} else {
			stopHookActive = false;
		}

		// Notification (idle)
		const notifPayload = hookPayload(
			sid,
			"Notification",
			{
				message: "agent_idle",
			},
			getHookCwd(ctx),
		) as NotificationParams;
		try {
			await cb?.onNotification?.(ctx, notifPayload);
		} catch (err) {
			logger.logError("Notification (idle) callback error:", err);
		}
	});

	// -- Notification:streaming (pi: "agent_start") ------------------------

	listeners.add("agent_start", async (_event, ctx) => {
		if (!active) return;

		const sid = getSessionId(ctx);
		if (!sid) return;

		const payload = hookPayload(
			sid,
			"Notification",
			{
				message: "agent_streaming",
			},
			getHookCwd(ctx),
		) as NotificationParams;
		try {
			await cb?.onNotification?.(ctx, payload);
		} catch (err) {
			logger.logError("Notification (streaming) callback error:", err);
		}
	});

	// -- PreCompact (pi: "session_before_compact") -------------------------

	listeners.add("session_before_compact", async (_event, ctx) => {
		if (!active) return;
		const sid = getSessionId(ctx);
		if (!sid) return;

		const payload = hookPayload(
			sid,
			"PreCompact",
			{
				message: "Context compaction starting",
			},
			getHookCwd(ctx),
		) as PreCompactParams;
		try {
			await cb?.onPreCompact?.(ctx, payload);
		} catch (err) {
			logger.logError("PreCompact callback error:", err);
		}
	});

	// -- SessionEnd (pi: "session_shutdown") -------------------------------

	listeners.add("session_shutdown", async (_event, ctx) => {
		if (!active) return;
		const sid = getSessionId(ctx);
		if (!sid) return;

		const payload = hookPayload(
			sid,
			"SessionEnd",
			{
				message: "Session ending",
			},
			getHookCwd(ctx),
		) as SessionEndParams;
		try {
			await cb?.onSessionEnd?.(ctx, payload);
		} catch (err) {
			logger.logError("SessionEnd callback error:", err);
		}
	});

	return {
		dispose: () => {
			cb = null;
			active = false;
			listeners.dispose();
		},
	};
}
