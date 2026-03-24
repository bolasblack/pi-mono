import { describe, expect, test } from "bun:test";
import { makeArgs } from "./test-helpers.js";
import { validateCompat } from "./validate.js";

describe("validateCompat", () => {
	test("valid args pass without error", () => {
		expect(() => validateCompat(makeArgs())).not.toThrow();
	});

	test("--fork-session without --continue or --resume throws", () => {
		expect(() => validateCompat(makeArgs({ forkSession: true }))).toThrow(
			"--fork-session requires --continue or --resume",
		);
	});

	test("--fork-session with --resume target is valid", () => {
		expect(() => validateCompat(makeArgs({ forkSession: true, resumeTarget: "abc" }))).not.toThrow();
	});

	test("--session-id with --continue throws", () => {
		expect(() => validateCompat(makeArgs({ sessionId: "abc", continueLast: true }))).toThrow(
			"--session-id cannot be combined",
		);
	});

	test("--session-id with --resume throws", () => {
		expect(() => validateCompat(makeArgs({ sessionId: "abc", resumeTarget: "def" }))).toThrow(
			"--session-id cannot be combined",
		);
	});

	test("--continue with --resume throws", () => {
		expect(() => validateCompat(makeArgs({ continueLast: true, resumeTarget: "abc" }))).toThrow(
			"--continue cannot be combined with --resume",
		);
	});

	test("--system-prompt with --system-prompt-file throws", () => {
		expect(() => validateCompat(makeArgs({ systemPrompt: "hi", systemPromptFile: "file.txt" }))).toThrow(
			"Use either --system-prompt or --system-prompt-file",
		);
	});

	test("--print without prompt and no continue/resume throws", () => {
		expect(() => validateCompat(makeArgs({ print: true }))).toThrow("--print requires a prompt");
	});

	test("--print with message is valid", () => {
		expect(() => validateCompat(makeArgs({ print: true, messages: ["hello"] }))).not.toThrow();
	});

	test("--print with --continue is valid", () => {
		expect(() => validateCompat(makeArgs({ print: true, continueLast: true }))).not.toThrow();
	});

	test("--fork-session with --continue throws (not yet supported)", () => {
		expect(() => validateCompat(makeArgs({ forkSession: true, continueLast: true }))).toThrow("not supported yet");
	});

	test("--fork-session with --resume picker throws (not yet supported)", () => {
		expect(() => validateCompat(makeArgs({ forkSession: true, resumePicker: true }))).toThrow("not supported yet");
	});
});
