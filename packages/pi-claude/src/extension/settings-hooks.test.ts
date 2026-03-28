import { describe, expect, test } from "bun:test";
import {
	extractSettingsFromArgv,
	hasSettingsHooksInMap,
	parseSettingsHooks,
	runSettingsHooks,
} from "./settings-hooks.js";

// =========================================================================
// extractSettingsFromArgv — pure function, no process.argv
// =========================================================================

describe("extractSettingsFromArgv", () => {
	test("extracts --settings value pairs", () => {
		const result = extractSettingsFromArgv(["node", "script.js", "--settings", "path.json"]);
		expect(result).toEqual(["path.json"]);
	});

	test("extracts --settings=value inline syntax", () => {
		const result = extractSettingsFromArgv(["node", "script.js", "--settings=inline.json"]);
		expect(result).toEqual(["inline.json"]);
	});

	test("extracts multiple --settings", () => {
		const result = extractSettingsFromArgv(["node", "script.js", "--settings", "a.json", "--settings", "b.json"]);
		expect(result).toEqual(["a.json", "b.json"]);
	});

	test("returns empty array when no --settings present", () => {
		const result = extractSettingsFromArgv(["node", "script.js", "--other"]);
		expect(result).toEqual([]);
	});

	test("skips --settings at end without value", () => {
		const result = extractSettingsFromArgv(["node", "script.js", "--settings"]);
		expect(result).toEqual([]);
	});
});

// =========================================================================
// parseSettingsHooks — pure function, no cache
// =========================================================================

describe("parseSettingsHooks", () => {
	test("returns null for empty settings values", () => {
		expect(parseSettingsHooks([])).toBeNull();
	});

	test("returns null when settings have no hooks key", () => {
		expect(parseSettingsHooks([JSON.stringify({ other: true })])).toBeNull();
	});

	test("returns null for empty hooks object", () => {
		expect(parseSettingsHooks([JSON.stringify({ hooks: {} })])).toBeNull();
	});

	test("parses valid hooks from JSON string", () => {
		const settings = JSON.stringify({
			hooks: {
				PreToolUse: [{ hooks: [{ type: "command", command: "echo" }] }],
			},
		});
		const map = parseSettingsHooks([settings]);
		expect(map).not.toBeNull();
		expect(map!.PreToolUse).toHaveLength(1);
	});

	test("merges hooks from multiple settings values", () => {
		const s1 = JSON.stringify({
			hooks: { PreToolUse: [{ hooks: [{ type: "command", command: "a" }] }] },
		});
		const s2 = JSON.stringify({
			hooks: { PostToolUse: [{ hooks: [{ type: "command", command: "b" }] }] },
		});
		const map = parseSettingsHooks([s1, s2]);
		expect(map).not.toBeNull();
		expect(map!.PreToolUse).toHaveLength(1);
		expect(map!.PostToolUse).toHaveLength(1);
	});

	test("ignores malformed JSON", () => {
		expect(parseSettingsHooks(["not json"])).toBeNull();
	});

	test("ignores settings without hooks even when mixed with valid", () => {
		const valid = JSON.stringify({
			hooks: { Stop: [{ hooks: [{ type: "command", command: "x" }] }] },
		});
		const map = parseSettingsHooks(["not json", JSON.stringify({}), valid]);
		expect(map).not.toBeNull();
		expect(map!.Stop).toHaveLength(1);
	});
});

// =========================================================================
// hasSettingsHooksInMap — pure function
// =========================================================================

describe("hasSettingsHooksInMap", () => {
	test("returns false for null", () => {
		expect(hasSettingsHooksInMap(null)).toBe(false);
	});

	test("returns false for empty groups", () => {
		expect(hasSettingsHooksInMap({ PreToolUse: [{ hooks: [] }] })).toBe(false);
	});

	test("returns true when valid command hooks exist", () => {
		const map = {
			PreToolUse: [{ hooks: [{ type: "command" as const, command: "echo" }] }],
		};
		expect(hasSettingsHooksInMap(map)).toBe(true);
	});
});

// =========================================================================
// runSettingsHooks — receives map + env, no process globals
// =========================================================================

describe("runSettingsHooks", () => {
	test("returns null for null map", async () => {
		const result = await runSettingsHooks(null, "PreToolUse", {}, {});
		expect(result).toBeNull();
	});

	test("returns null when no hooks match event", async () => {
		const map = {
			PreToolUse: [{ hooks: [{ type: "command" as const, command: "echo" }] }],
		};
		const result = await runSettingsHooks(map, "NonExistent", {}, {});
		expect(result).toBeNull();
	});

	test("returns result from successful hook command", async () => {
		const map = {
			SessionStart: [
				{
					hooks: [
						{
							type: "command" as const,
							command: `echo '{"hookSpecificOutput":{"additionalContext":"test-ctx"}}'`,
						},
					],
				},
			],
		};
		const result = await runSettingsHooks(map, "SessionStart", { session_id: "test" }, {});
		expect(result?.hookSpecificOutput?.additionalContext).toBe("test-ctx");
	});

	test("times out stalled hook commands", async () => {
		const map = {
			PreToolUse: [
				{
					hooks: [
						{
							type: "command" as const,
							command: `${process.execPath} -e "setTimeout(() => {}, 5000)"`,
						},
					],
				},
			],
		};

		const start = Date.now();
		const result = await runSettingsHooks(map, "PreToolUse", {}, {}, { timeoutMs: 100 });
		const elapsed = Date.now() - start;

		expect(result?.hookSpecificOutput?.additionalContext).toContain("⚠");
		expect(result?.hookSpecificOutput?.additionalContext).toContain("timed out");
		expect(elapsed).toBeLessThan(2000);
	});

	test("keeps successful output when another hook times out", async () => {
		const map = {
			PreToolUse: [
				{
					hooks: [
						{
							type: "command" as const,
							command: `${process.execPath} -e "setTimeout(() => {}, 5000)"`,
						},
						{
							type: "command" as const,
							command: `echo '{"hookSpecificOutput":{"additionalContext":"fast"}}'`,
						},
					],
				},
			],
		};

		const result = await runSettingsHooks(map, "PreToolUse", {}, {}, { timeoutMs: 100 });
		expect(result?.hookSpecificOutput?.additionalContext).toContain("fast");
	});

	test("passes env to hook subprocess", async () => {
		const map = {
			Test: [
				{
					hooks: [
						{
							type: "command" as const,
							command: `${process.execPath} -e "
            const v = process.env.MY_TEST_VAR;
            console.log(JSON.stringify({reason: v}));
          "`,
						},
					],
				},
			],
		};
		const result = await runSettingsHooks(map, "Test", {}, { MY_TEST_VAR: "injected" });
		expect(result?.reason).toBe("injected");
	});
});
