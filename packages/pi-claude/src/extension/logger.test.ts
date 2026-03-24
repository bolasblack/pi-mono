import { afterEach, describe, expect, test } from "bun:test";
import { existsSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { createLogger } from "./logger.js";

const LOG_DIR = "/tmp/pi-claude";

function readLog(fileName: string): string {
	return readFileSync(join(LOG_DIR, fileName), "utf-8");
}

function cleanLog(fileName: string): void {
	const path = join(LOG_DIR, fileName);
	if (existsSync(path)) rmSync(path);
}

describe("createLogger", () => {
	const testSessionId = `test-logger-${Date.now()}`;
	const testFile = `${testSessionId}.log`;
	const fallbackFile = "pi-claude.log";

	afterEach(() => {
		cleanLog(testFile);
		// Don't clean fallback — shared by other processes
	});

	test("log() writes INFO level to session-specific file", () => {
		const logger = createLogger(() => testSessionId, "[test]");
		logger.log("hello world");

		const content = readLog(testFile);
		expect(content).toContain("INFO");
		expect(content).toContain("[test]");
		expect(content).toContain("hello world");
	});

	test("logWarn() writes WARN level", () => {
		const logger = createLogger(() => testSessionId, "[test]");
		logger.logWarn("something concerning");

		const content = readLog(testFile);
		expect(content).toContain("WARN");
		expect(content).toContain("something concerning");
	});

	test("logError() writes ERROR level", () => {
		const logger = createLogger(() => testSessionId, "[test]");
		logger.logError("something broke");

		const content = readLog(testFile);
		expect(content).toContain("ERROR");
		expect(content).toContain("something broke");
	});

	test("serializes object arguments as JSON", () => {
		const logger = createLogger(() => testSessionId, "[test]");
		logger.log("data:", { key: "value" });

		const content = readLog(testFile);
		expect(content).toContain('"key":"value"');
	});

	test("writes to fallback file when session id is null", () => {
		const logger = createLogger(() => null, "[test]");
		const marker = `fallback-test-${Date.now()}`;
		logger.log(marker);

		const content = readLog(fallbackFile);
		expect(content).toContain(marker);
	});

	test("includes ISO timestamp", () => {
		const logger = createLogger(() => testSessionId, "[test]");
		logger.log("timestamp check");

		const content = readLog(testFile);
		// ISO 8601 format: 2024-01-15T12:34:56.789Z
		expect(content).toMatch(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/);
	});

	test("multiple log calls append to same file", () => {
		const logger = createLogger(() => testSessionId, "[test]");
		logger.log("line one");
		logger.log("line two");

		const content = readLog(testFile);
		expect(content).toContain("line one");
		expect(content).toContain("line two");
		// Should be separate lines
		const lines = content.trim().split("\n");
		expect(lines.length).toBe(2);
	});
});
