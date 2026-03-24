import { describe, expect, test } from "bun:test";
import { parseCompatArgs } from "./index.js";

describe("parseCompatArgs", () => {
	// -- Unknown flags --
	test("does not swallow following positional token", () => {
		const parsed = parseCompatArgs(["--unknown-flag", "user text", "next"]);
		expect(parsed.unknownFlags).toEqual(["--unknown-flag"]);
		expect(parsed.messages).toEqual(["user text", "next"]);
	});

	test("keeps inline unknown flag and preserves positional token", () => {
		const parsed = parseCompatArgs(["--unknown-flag=value", "prompt"]);
		expect(parsed.unknownFlags).toEqual(["--unknown-flag=value"]);
		expect(parsed.messages).toEqual(["prompt"]);
	});

	// -- --resume --
	test("--resume without value enables picker", () => {
		const parsed = parseCompatArgs(["--resume"]);
		expect(parsed.resumePicker).toBe(true);
		expect(parsed.resumeTarget).toBeUndefined();
	});

	test("--resume with value sets target", () => {
		const parsed = parseCompatArgs(["--resume", "abc-123"]);
		expect(parsed.resumeTarget).toBe("abc-123");
		expect(parsed.resumePicker).toBe(false);
	});

	test("-r shorthand works like --resume", () => {
		const parsed = parseCompatArgs(["-r"]);
		expect(parsed.resumePicker).toBe(true);
	});

	test("-r with value sets target", () => {
		const parsed = parseCompatArgs(["-r", "abc-123"]);
		expect(parsed.resumeTarget).toBe("abc-123");
	});

	test("--resume does not consume following flag as value", () => {
		const parsed = parseCompatArgs(["--resume", "--print"]);
		expect(parsed.resumePicker).toBe(true);
		expect(parsed.print).toBe(true);
	});

	// -- --continue --
	test("--continue sets continueLast", () => {
		const parsed = parseCompatArgs(["--continue"]);
		expect(parsed.continueLast).toBe(true);
	});

	test("-c shorthand works like --continue", () => {
		const parsed = parseCompatArgs(["-c"]);
		expect(parsed.continueLast).toBe(true);
	});

	// -- --fork-session --
	test("--fork-session sets forkSession", () => {
		const parsed = parseCompatArgs(["--fork-session"]);
		expect(parsed.forkSession).toBe(true);
	});

	// -- --print / -p --
	test("--print sets print", () => {
		const parsed = parseCompatArgs(["--print"]);
		expect(parsed.print).toBe(true);
	});

	test("-p sets print and next arg becomes message", () => {
		const parsed = parseCompatArgs(["-p", "my prompt"]);
		expect(parsed.print).toBe(true);
		expect(parsed.messages).toEqual(["my prompt"]);
	});

	// -- --model --
	test("--model requires value", () => {
		const parsed = parseCompatArgs(["--model", "claude-3-5-sonnet"]);
		expect(parsed.model).toBe("claude-3-5-sonnet");
	});

	test("--model with inline value", () => {
		const parsed = parseCompatArgs(["--model=claude-3-5-sonnet"]);
		expect(parsed.model).toBe("claude-3-5-sonnet");
	});

	test("--model without value throws", () => {
		expect(() => parseCompatArgs(["--model"])).toThrow("requires a value");
	});

	// -- --session-id --
	test("--session-id stores value", () => {
		const parsed = parseCompatArgs(["--session-id", "abc-def"]);
		expect(parsed.sessionId).toBe("abc-def");
	});

	// -- --settings --
	test("--settings collects multiple entries", () => {
		const parsed = parseCompatArgs(["--settings", "path1.json", "--settings", "path2.json"]);
		expect(parsed.settings).toEqual(["path1.json", "path2.json"]);
	});

	// -- --system-prompt / --system-prompt-file --
	test("--system-prompt stores value", () => {
		const parsed = parseCompatArgs(["--system-prompt", "You are helpful."]);
		expect(parsed.systemPrompt).toBe("You are helpful.");
	});

	test("--system-prompt-file stores value", () => {
		const parsed = parseCompatArgs(["--system-prompt-file", "prompt.txt"]);
		expect(parsed.systemPromptFile).toBe("prompt.txt");
	});

	// -- Unsupported flags --
	test("unsupported flag without value generates warning", () => {
		const parsed = parseCompatArgs(["--verbose"]);
		expect(parsed.warnings.length).toBe(1);
		expect(parsed.warnings[0]).toContain("--verbose");
	});

	test("unsupported flag with value skips value token", () => {
		const parsed = parseCompatArgs(["--max-turns", "5", "prompt"]);
		expect(parsed.warnings.length).toBe(1);
		expect(parsed.messages).toEqual(["prompt"]);
	});

	test("unsupported flag with inline value", () => {
		const parsed = parseCompatArgs(["--max-turns=5", "prompt"]);
		expect(parsed.warnings.length).toBe(1);
		expect(parsed.messages).toEqual(["prompt"]);
	});

	// -- Positional messages --
	test("positional args become messages", () => {
		const parsed = parseCompatArgs(["hello", "world"]);
		expect(parsed.messages).toEqual(["hello", "world"]);
	});

	// -- Combined flags --
	test("combined flags parse correctly", () => {
		const parsed = parseCompatArgs(["-p", "--model", "claude-3", "do something"]);
		expect(parsed.print).toBe(true);
		expect(parsed.model).toBe("claude-3");
		expect(parsed.messages).toEqual(["do something"]);
	});
});
