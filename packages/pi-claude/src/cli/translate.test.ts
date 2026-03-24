import { describe, expect, test } from "bun:test";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { makeArgs } from "./test-helpers.js";
import { extensionCandidates, resolveCompatExtensionFile, rewriteClaudeModel, translateToPiArgv } from "./translate.js";

// =========================================================================
// rewriteClaudeModel — pure, no env
// =========================================================================

describe("rewriteClaudeModel", () => {
	test("adds anthropic/ prefix to claude models", () => {
		expect(rewriteClaudeModel("claude-3-5-sonnet-20241022")).toBe("anthropic/claude-3-5-sonnet-20241022");
	});

	test("strips bracket metadata from claude models", () => {
		expect(rewriteClaudeModel("claude-3-5-sonnet-20241022[fast]")).toBe("anthropic/claude-3-5-sonnet-20241022");
	});

	test("preserves provider prefix for claude models", () => {
		expect(rewriteClaudeModel("bedrock/claude-3-5-sonnet-20241022[thinking]")).toBe(
			"bedrock/claude-3-5-sonnet-20241022",
		);
	});

	test("passes through non-claude models unchanged", () => {
		expect(rewriteClaudeModel("gpt-4o")).toBe("gpt-4o");
	});

	test("passes through non-claude models with provider prefix", () => {
		expect(rewriteClaudeModel("openai/gpt-4o")).toBe("openai/gpt-4o");
	});

	test("strips nested bracket content", () => {
		expect(rewriteClaudeModel("claude-3[a[b]]")).toBe("anthropic/claude-3");
	});
});

// =========================================================================
// resolveCompatExtensionFile
// =========================================================================

describe("resolveCompatExtensionFile", () => {
	test("auto-discovers extension.ts relative to module", () => {
		const result = resolveCompatExtensionFile();
		expect(result).toContain("extension.ts");
	});

	test("candidates include ../src/extension.ts for bundled dist/ layout", () => {
		// When the bundled binary runs from dist/, baseDir is <package>/dist.
		// The candidates must include resolve(dist, "../src/extension.ts")
		// which resolves to <package>/src/extension.ts.
		const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
		const distDir = resolve(packageRoot, "dist");
		const candidates = extensionCandidates(distDir, "/nonexistent-cwd");
		const expected = resolve(packageRoot, "src/extension.ts");
		expect(candidates).toContain(expected);
	});
});

// =========================================================================
// translateToPiArgv — env injected, no process.env
// =========================================================================

describe("translateToPiArgv", () => {
	const emptyEnv = {};

	test("includes --extension in output", async () => {
		const argv = await translateToPiArgv(makeArgs(), emptyEnv);
		expect(argv).toContain("--extension");
	});

	test("translates --model with rewrite", async () => {
		const argv = await translateToPiArgv(makeArgs({ model: "claude-3-5-sonnet" }), emptyEnv);
		const modelIdx = argv.indexOf("--model");
		expect(modelIdx).toBeGreaterThanOrEqual(0);
		expect(argv[modelIdx + 1]).toBe("anthropic/claude-3-5-sonnet");
	});

	test("falls back to ANTHROPIC_MODEL from env", async () => {
		const argv = await translateToPiArgv(makeArgs(), { ANTHROPIC_MODEL: "claude-3-haiku" });
		const modelIdx = argv.indexOf("--model");
		expect(modelIdx).toBeGreaterThanOrEqual(0);
		expect(argv[modelIdx + 1]).toBe("anthropic/claude-3-haiku");
	});

	test("explicit model overrides ANTHROPIC_MODEL env", async () => {
		const argv = await translateToPiArgv(makeArgs({ model: "gpt-4o" }), { ANTHROPIC_MODEL: "claude-3-haiku" });
		const modelIdx = argv.indexOf("--model");
		expect(argv[modelIdx + 1]).toBe("gpt-4o");
	});

	test("translates --print", async () => {
		const argv = await translateToPiArgv(makeArgs({ print: true }), emptyEnv);
		expect(argv).toContain("--print");
	});

	test("translates --continue", async () => {
		const argv = await translateToPiArgv(makeArgs({ continueLast: true }), emptyEnv);
		expect(argv).toContain("--continue");
	});

	test("translates --resume with target to --session", async () => {
		const argv = await translateToPiArgv(makeArgs({ resumeTarget: "abc-123" }), emptyEnv);
		const idx = argv.indexOf("--session");
		expect(idx).toBeGreaterThanOrEqual(0);
		expect(argv[idx + 1]).toBe("abc-123");
	});

	test("translates --resume picker", async () => {
		const argv = await translateToPiArgv(makeArgs({ resumePicker: true }), emptyEnv);
		expect(argv).toContain("--resume");
	});

	test("translates --fork-session with resume target to --fork", async () => {
		const argv = await translateToPiArgv(makeArgs({ forkSession: true, resumeTarget: "abc" }), emptyEnv);
		const idx = argv.indexOf("--fork");
		expect(idx).toBeGreaterThanOrEqual(0);
		expect(argv[idx + 1]).toBe("abc");
	});

	test("translates --session-id to --session with --session-mode auto", async () => {
		const uuid = "12345678-1234-1234-1234-123456789abc";
		const argv = await translateToPiArgv(makeArgs({ sessionId: uuid }), emptyEnv);
		const sessionIdx = argv.indexOf("--session");
		expect(sessionIdx).toBeGreaterThanOrEqual(0);
		expect(argv[sessionIdx + 1]).toBe(uuid);
		expect(argv).toContain("--session-mode");
	});

	test("rejects non-UUID --session-id", async () => {
		expect(translateToPiArgv(makeArgs({ sessionId: "not-a-uuid" }), emptyEnv)).rejects.toThrow("valid UUID");
	});

	test("translates --system-prompt", async () => {
		const argv = await translateToPiArgv(makeArgs({ systemPrompt: "You are helpful." }), emptyEnv);
		const idx = argv.indexOf("--system-prompt");
		expect(idx).toBeGreaterThanOrEqual(0);
		expect(argv[idx + 1]).toBe("You are helpful.");
	});

	test("translates --append-system-prompt", async () => {
		const argv = await translateToPiArgv(makeArgs({ appendSystemPrompt: "Be concise." }), emptyEnv);
		const idx = argv.indexOf("--append-system-prompt");
		expect(idx).toBeGreaterThanOrEqual(0);
		expect(argv[idx + 1]).toBe("Be concise.");
	});

	test("translates --settings entries", async () => {
		const argv = await translateToPiArgv(makeArgs({ settings: ["path1.json", "path2.json"] }), emptyEnv);
		const indices: number[] = [];
		for (let i = 0; i < argv.length; i++) {
			if (argv[i] === "--settings") indices.push(i);
		}
		expect(indices.length).toBeGreaterThanOrEqual(2);
	});

	test("DISABLE_AUTO_COMPACT env adds compaction settings", async () => {
		const argv = await translateToPiArgv(makeArgs(), { DISABLE_AUTO_COMPACT: "1" });
		const settingsValues = argv.map((v, i) => (argv[i - 1] === "--settings" ? v : null)).filter(Boolean);
		expect(settingsValues.some((v) => v!.includes("compaction"))).toBe(true);
	});

	test("appends messages at end", async () => {
		const argv = await translateToPiArgv(makeArgs({ messages: ["hello", "world"] }), emptyEnv);
		expect(argv[argv.length - 2]).toBe("hello");
		expect(argv[argv.length - 1]).toBe("world");
	});

	test("appends unknownFlags before messages", async () => {
		const argv = await translateToPiArgv(makeArgs({ unknownFlags: ["--custom"], messages: ["prompt"] }), emptyEnv);
		const customIdx = argv.indexOf("--custom");
		const promptIdx = argv.indexOf("prompt");
		expect(customIdx).toBeLessThan(promptIdx);
	});
});
