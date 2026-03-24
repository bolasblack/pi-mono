import { describe, expect, test } from "bun:test";
import { homedir } from "os";
import { join } from "path";
import { collapsedLine, getTranscriptPath, hookPayload } from "./claude-hooks.js";
import { createListenerRegistry } from "./listener-registry.js";

// =========================================================================
// createListenerRegistry
// =========================================================================

describe("createListenerRegistry", () => {
	test("unbinds all listeners on dispose", () => {
		type Handler = (...args: never[]) => unknown;
		const handlers = new Map<string, Set<Handler>>();

		const api = {
			on(event: string, handler: Handler) {
				if (!handlers.has(event)) handlers.set(event, new Set());
				handlers.get(event)!.add(handler);
				return () => {
					handlers.get(event)?.delete(handler);
				};
			},
		};

		const registry = createListenerRegistry(api as any);
		const handlerA = () => undefined;
		const handlerB = () => undefined;

		registry.add("event-a", handlerA);
		registry.add("event-b", handlerB);

		expect(handlers.get("event-a")?.size).toBe(1);
		expect(handlers.get("event-b")?.size).toBe(1);

		registry.dispose();

		expect(handlers.get("event-a")?.size ?? 0).toBe(0);
		expect(handlers.get("event-b")?.size ?? 0).toBe(0);
	});

	test("supports api.off fallback when on() does not return disposer", () => {
		type Handler = (...args: never[]) => unknown;
		const handlers = new Map<string, Set<Handler>>();

		const api = {
			on(event: string, handler: Handler) {
				if (!handlers.has(event)) handlers.set(event, new Set());
				handlers.get(event)!.add(handler);
				// Return non-function (no disposer)
				return undefined;
			},
			off(event: string, handler: Handler) {
				handlers.get(event)?.delete(handler);
			},
		};

		const registry = createListenerRegistry(api as any);
		registry.add("evt", () => {});

		expect(handlers.get("evt")?.size).toBe(1);
		registry.dispose();
		expect(handlers.get("evt")?.size ?? 0).toBe(0);
	});
});

// =========================================================================
// getTranscriptPath
// =========================================================================

describe("getTranscriptPath", () => {
	test("returns path under ~/.pi with session id", () => {
		const path = getTranscriptPath("abc-123");
		expect(path).toBe(join(homedir(), ".pi", "claude-code-compatible-transcripts", "abc-123.jsonl"));
	});

	test("uses .jsonl extension", () => {
		const path = getTranscriptPath("session-id");
		expect(path).toEndWith(".jsonl");
	});
});

// =========================================================================
// hookPayload
// =========================================================================

describe("hookPayload", () => {
	test("builds payload with required fields", () => {
		const payload = hookPayload("sid-1", "PreToolUse");
		expect(payload.session_id).toBe("sid-1");
		expect(payload.hook_event_name).toBe("PreToolUse");
		expect(payload.transcript_path).toContain("sid-1.jsonl");
		expect(payload.cwd).toBe(process.cwd());
		expect(payload.project_dir).toBe(process.cwd());
	});

	test("uses provided cwd", () => {
		const payload = hookPayload("sid", "Stop", undefined, "/custom/cwd");
		expect(payload.cwd).toBe("/custom/cwd");
		expect(payload.project_dir).toBe("/custom/cwd");
	});

	test("uses provided projectDir separately from cwd", () => {
		const payload = hookPayload("sid", "Stop", undefined, "/cwd", "/project");
		expect(payload.cwd).toBe("/cwd");
		expect(payload.project_dir).toBe("/project");
	});

	test("merges extra fields into payload", () => {
		const payload = hookPayload("sid", "PreToolUse", {
			tool_name: "bash",
			tool_input: { command: "ls" },
		});
		expect(payload.tool_name).toBe("bash");
		expect(payload.tool_input).toEqual({ command: "ls" });
	});

	test("extra fields override base fields", () => {
		// This is spread behavior — verify it's intentional
		const payload = hookPayload("sid", "Test", {
			hook_event_name: "Overridden",
		});
		expect(payload.hook_event_name).toBe("Overridden");
	});
});

// =========================================================================
// collapsedLine
// =========================================================================

describe("collapsedLine", () => {
	// Minimal theme mock
	const theme = {
		fg: (_color: string, text: string) => text,
		bg: (_color: string, text: string) => text,
	};

	test("short content renders without expand hint", () => {
		const result = collapsedLine("⚙", "hello", theme as any);
		expect(result).toContain("hello");
		expect(result).not.toContain("…");
	});

	test("long content gets truncated with expand hint", () => {
		const long = "a".repeat(200);
		const result = collapsedLine("⚙", long, theme as any);
		expect(result).toContain("…");
	});

	test("multiline content shows expand hint", () => {
		const result = collapsedLine("⚙", "line1\nline2", theme as any);
		expect(result).toContain("…");
		expect(result).toContain("line1 line2");
	});

	test("ANSI codes in content don't count toward visible length", () => {
		const ansiRed = "\x1b[31m";
		const ansiReset = "\x1b[0m";
		const visibleText = "x".repeat(70);
		const content = `${ansiRed}${visibleText}${ansiReset}`;
		const result = collapsedLine("⚙", content, theme as any);
		expect(result).toContain(visibleText);
	});

	test("ANSI codes in prefix don't inflate budget calculation", () => {
		const ansiPrefix = "\x1b[2m⚙ [Hook]\x1b[0m";
		const result = collapsedLine(ansiPrefix, "short", theme as any);
		expect(result).toContain("short");
	});

	test("empty content renders without error", () => {
		const result = collapsedLine("⚙", "", theme as any);
		expect(result).toContain("⚙");
	});

	test("budget accounts for prefix length", () => {
		// Long prefix eats into budget: 80 - 75 = 5 char budget
		const longPrefix = "P".repeat(75);

		// Exactly at budget — no truncation
		const exact = collapsedLine(longPrefix, "abcde", theme as any);
		expect(exact).not.toContain("…");

		// Over budget — truncated
		const over = collapsedLine(longPrefix, "abcdef", theme as any);
		expect(over).toContain("…");
	});
});
