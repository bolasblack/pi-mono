import { describe, expect, test } from "bun:test";
import { matchesToolName } from "./skill-hooks.js";

describe("matchesToolName", () => {
	// Regex alternation
	test("matches regex alternation pattern", () => {
		expect(matchesToolName("Bash|Write|Edit", "write")).toBe(true);
		expect(matchesToolName("Bash|Write|Edit", "bash")).toBe(true);
		expect(matchesToolName("Bash|Write|Edit", "edit")).toBe(true);
		expect(matchesToolName("Bash|Write|Edit", "read")).toBe(false);
	});

	// Case insensitive
	test("matches case-insensitively", () => {
		expect(matchesToolName("Write", "write")).toBe(true);
		expect(matchesToolName("write", "Write")).toBe(true);
		expect(matchesToolName("BASH", "bash")).toBe(true);
	});

	// Exact match
	test("matches exact tool name", () => {
		expect(matchesToolName("bash", "bash")).toBe(true);
		expect(matchesToolName("bash", "read")).toBe(false);
	});

	// Wildcard
	test("matches wildcard *", () => {
		expect(matchesToolName("*", "bash")).toBe(true);
		expect(matchesToolName("*", "write")).toBe(true);
		expect(matchesToolName("*", "anything")).toBe(true);
	});

	// Regex patterns
	test("matches regex patterns", () => {
		expect(matchesToolName("mcp__.*__delete.*", "mcp__server__delete_file")).toBe(true);
		expect(matchesToolName("mcp__.*__delete.*", "mcp__server__read_file")).toBe(false);
	});

	// Invalid regex falls back to exact match
	test("falls back to exact match on invalid regex", () => {
		expect(matchesToolName("[invalid", "[invalid")).toBe(true);
		expect(matchesToolName("[invalid", "something")).toBe(false);
	});

	// Must match full tool name, not partial
	test("matches full tool name only", () => {
		expect(matchesToolName("bash", "bash_extended")).toBe(false);
		expect(matchesToolName("read", "unread")).toBe(false);
	});
});
