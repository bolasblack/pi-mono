import { describe, expect, test } from "bun:test";
import { mergeHookResults, spawnHookCommand } from "./hook-runner.js";

describe("spawnHookCommand", () => {
	test("returns null when hook process exceeds timeout", async () => {
		const start = Date.now();

		const result = await spawnHookCommand(
			process.execPath,
			["-e", "setTimeout(() => {}, 5000)"],
			{ ok: true },
			process.env as Record<string, string | undefined>,
			"timeout-test",
			{ timeoutMs: 80 },
		);

		const elapsed = Date.now() - start;
		expect(result?.hookSpecificOutput?.additionalContext).toContain("⚠");
		expect(result?.hookSpecificOutput?.additionalContext).toContain("timeout-test");
		expect(elapsed).toBeLessThan(1500);
	});

	test("parses trailing multiline JSON after log lines", async () => {
		const script = [
			"console.log('hook log line');",
			"console.log(JSON.stringify({ ignored: true }));",
			"console.log(JSON.stringify({ hookSpecificOutput: { additionalContext: 'ok' } }, null, 2));",
		].join(" ");

		const result = await spawnHookCommand(
			process.execPath,
			["-e", script],
			{ ok: true },
			process.env as Record<string, string | undefined>,
			"multiline-json-test",
			{ timeoutMs: 1000 },
		);

		expect(result?.hookSpecificOutput?.additionalContext).toBe("[multiline-json-test] ok");
	});
});

describe("mergeHookResults", () => {
	test("returns null for empty array", () => {
		expect(mergeHookResults([])).toBeNull();
	});

	test("returns null for all-null array", () => {
		expect(mergeHookResults([null, null])).toBeNull();
	});

	test("returns single non-null result as-is", () => {
		const result = { continue: true, reason: "ok" };
		expect(mergeHookResults([result])).toEqual(result);
	});

	test("any continue=false wins", () => {
		const merged = mergeHookResults([{ continue: true }, { continue: false }]);
		expect(merged?.continue).toBe(false);
	});

	test("any decision=block wins", () => {
		const merged = mergeHookResults([{ decision: "allow" }, { decision: "block" }]);
		expect(merged?.decision).toBe("block");
	});

	test("first stopReason wins", () => {
		const merged = mergeHookResults([{ stopReason: "first" }, { stopReason: "second" }]);
		expect(merged?.stopReason).toBe("first");
	});

	test("first reason wins", () => {
		const merged = mergeHookResults([{ reason: "first" }, { reason: "second" }]);
		expect(merged?.reason).toBe("first");
	});

	test("first systemMessage wins", () => {
		const merged = mergeHookResults([{ systemMessage: "msg1" }, { systemMessage: "msg2" }]);
		expect(merged?.systemMessage).toBe("msg1");
	});

	test("additionalContext is concatenated with newline", () => {
		const merged = mergeHookResults([
			{ hookSpecificOutput: { additionalContext: "ctx1" } },
			{ hookSpecificOutput: { additionalContext: "ctx2" } },
		]);
		expect(merged?.hookSpecificOutput?.additionalContext).toBe("ctx1\nctx2");
	});

	test("permissionDecision: deny beats ask beats allow", () => {
		const merged = mergeHookResults([
			{ hookSpecificOutput: { permissionDecision: "allow" } },
			{ hookSpecificOutput: { permissionDecision: "ask", permissionDecisionReason: "ask-reason" } },
			{ hookSpecificOutput: { permissionDecision: "deny", permissionDecisionReason: "deny-reason" } },
		]);
		expect(merged?.hookSpecificOutput?.permissionDecision).toBe("deny");
		expect(merged?.hookSpecificOutput?.permissionDecisionReason).toBe("deny-reason");
	});

	test("permissionDecision: ask beats allow", () => {
		const merged = mergeHookResults([
			{ hookSpecificOutput: { permissionDecision: "allow" } },
			{ hookSpecificOutput: { permissionDecision: "ask", permissionDecisionReason: "caution" } },
		]);
		expect(merged?.hookSpecificOutput?.permissionDecision).toBe("ask");
		expect(merged?.hookSpecificOutput?.permissionDecisionReason).toBe("caution");
	});

	test("null results are filtered out during merge", () => {
		const merged = mergeHookResults([null, { reason: "valid" }, null]);
		expect(merged?.reason).toBe("valid");
	});
});
