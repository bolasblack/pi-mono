import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { SettingsManager } from "../src/core/settings-manager.js";
import { parseCliSettingsEntries } from "../src/main.js";

describe("CLI --settings parsing", () => {
	let tempDir: string;

	beforeEach(() => {
		tempDir = join(tmpdir(), `pi-settings-${Date.now()}`);
		mkdirSync(tempDir, { recursive: true });
	});

	afterEach(() => {
		rmSync(tempDir, { recursive: true, force: true });
	});

	test("parses inline JSON entries", () => {
		const overrides = parseCliSettingsEntries(['{"defaultProvider":"openai"}'], tempDir);
		expect(overrides).toEqual([{ defaultProvider: "openai" }]);
	});

	test("parses settings from file paths", () => {
		const settingsPath = join(tempDir, "settings.json");
		writeFileSync(settingsPath, '{"theme":"night-owl"}');

		const overrides = parseCliSettingsEntries([settingsPath], tempDir);
		expect(overrides).toEqual([{ theme: "night-owl" }]);
	});

	test("applies repeated entries left-to-right", () => {
		const settingsPath = join(tempDir, "settings.json");
		writeFileSync(settingsPath, '{"defaultProvider":"anthropic","theme":"light"}');

		const settingsManager = SettingsManager.inMemory();
		for (const overrides of parseCliSettingsEntries([settingsPath, '{"defaultProvider":"openai"}'], tempDir)) {
			settingsManager.applyOverrides(overrides);
		}

		expect(settingsManager.getDefaultProvider()).toBe("openai");
		expect(settingsManager.getTheme()).toBe("light");
	});

	test("throws for invalid values", () => {
		expect(() => parseCliSettingsEntries(["not-json"], tempDir)).toThrow(
			"expected inline JSON or an existing file path",
		);
	});

	test("re-applying parsed overrides does not re-read the file", () => {
		// Regression test for the double-parse bug: once parseCliSettingsEntries
		// returns, the resulting Partial<Settings>[] is fully detached from the
		// filesystem. applyParsedOverrides must use those payloads verbatim,
		// and callers can call it once at startup AND again for each runtime
		// rebuild without triggering additional reads.
		const settingsPath = join(tempDir, "settings.json");
		writeFileSync(settingsPath, '{"theme":"night-owl"}');

		// Replace the file with a sentinel after parsing so any subsequent
		// parse would be observable as a changed value. applyParsedOverrides
		// should be fully in-memory and therefore not notice.
		const parsed = parseCliSettingsEntries([settingsPath], tempDir);
		writeFileSync(settingsPath, '{"theme":"SENTINEL-IF-RE-READ"}');

		// Re-apply the parsed payload multiple times — simulates startup +
		// every createRuntime() rebuild across session switch / resume / reload.
		const managerA = SettingsManager.inMemory();
		managerA.applyParsedOverrides(parsed);
		const managerB = SettingsManager.inMemory();
		managerB.applyParsedOverrides(parsed);
		const managerC = SettingsManager.inMemory();
		managerC.applyParsedOverrides(parsed);

		// All three managers must see the original value, not the sentinel —
		// proving applyParsedOverrides did not re-open the file.
		expect(managerA.getTheme()).toBe("night-owl");
		expect(managerB.getTheme()).toBe("night-owl");
		expect(managerC.getTheme()).toBe("night-owl");
	});

	test("applyParsedOverrides preserves left-to-right order across entries", () => {
		// Guard against a future refactor that tries to deep-merge once instead
		// of replaying each entry. The semantic we want is "later entries win
		// at field granularity", matching applyOverrides() called in a loop.
		const manager = SettingsManager.inMemory();
		const parsed = parseCliSettingsEntries(
			['{"defaultProvider":"anthropic","theme":"dark"}', '{"defaultProvider":"openai"}', '{"theme":"light"}'],
			tempDir,
		);
		manager.applyParsedOverrides(parsed);
		expect(manager.getDefaultProvider()).toBe("openai");
		expect(manager.getTheme()).toBe("light");
	});
});
