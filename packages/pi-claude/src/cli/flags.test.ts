import { describe, expect, test } from "bun:test";
import {
	SUPPORTED_FLAG_BY_TOKEN,
	SUPPORTED_FLAG_DEFINITIONS,
	UNSUPPORTED_FLAG_BY_NAME,
	UNSUPPORTED_FLAG_DEFINITIONS,
} from "./flags.js";

describe("SUPPORTED_FLAG_BY_TOKEN", () => {
	test("indexes all long flag names", () => {
		for (const def of SUPPORTED_FLAG_DEFINITIONS) {
			expect(SUPPORTED_FLAG_BY_TOKEN.get(def.name)).toBe(def);
		}
	});

	test("indexes all short flag aliases", () => {
		const withShorts = SUPPORTED_FLAG_DEFINITIONS.filter((d) => d.short);
		expect(withShorts.length).toBeGreaterThan(0);

		for (const def of withShorts) {
			expect(SUPPORTED_FLAG_BY_TOKEN.get(def.short!)).toBe(def);
		}
	});

	test("short and long flags resolve to same definition", () => {
		const continueFlag = SUPPORTED_FLAG_BY_TOKEN.get("--continue");
		const continueFlagShort = SUPPORTED_FLAG_BY_TOKEN.get("-c");
		expect(continueFlag).toBe(continueFlagShort);

		const resumeFlag = SUPPORTED_FLAG_BY_TOKEN.get("--resume");
		const resumeFlagShort = SUPPORTED_FLAG_BY_TOKEN.get("-r");
		expect(resumeFlag).toBe(resumeFlagShort);
	});

	test("does not contain unsupported flags", () => {
		for (const def of UNSUPPORTED_FLAG_DEFINITIONS) {
			expect(SUPPORTED_FLAG_BY_TOKEN.has(def.name)).toBe(false);
		}
	});
});

describe("UNSUPPORTED_FLAG_BY_NAME", () => {
	test("indexes all unsupported flag names", () => {
		for (const def of UNSUPPORTED_FLAG_DEFINITIONS) {
			expect(UNSUPPORTED_FLAG_BY_NAME.get(def.name)).toBe(def);
		}
	});

	test("does not overlap with supported flags", () => {
		for (const def of SUPPORTED_FLAG_DEFINITIONS) {
			expect(UNSUPPORTED_FLAG_BY_NAME.has(def.name)).toBe(false);
		}
	});
});

describe("flag definition integrity", () => {
	test("no duplicate long flag names in supported", () => {
		const names = SUPPORTED_FLAG_DEFINITIONS.map((d) => d.name);
		expect(new Set(names).size).toBe(names.length);
	});

	test("no duplicate short flags in supported", () => {
		const shorts = SUPPORTED_FLAG_DEFINITIONS.filter((d) => d.short).map((d) => d.short!);
		expect(new Set(shorts).size).toBe(shorts.length);
	});

	test("no duplicate names in unsupported", () => {
		const names = UNSUPPORTED_FLAG_DEFINITIONS.map((d) => d.name);
		expect(new Set(names).size).toBe(names.length);
	});

	test("all flag names start with --", () => {
		for (const def of [...SUPPORTED_FLAG_DEFINITIONS, ...UNSUPPORTED_FLAG_DEFINITIONS]) {
			expect(def.name.startsWith("--")).toBe(true);
		}
	});

	test("all short flags start with -", () => {
		for (const def of SUPPORTED_FLAG_DEFINITIONS.filter((d) => d.short)) {
			expect(def.short!.startsWith("-")).toBe(true);
			expect(def.short!.startsWith("--")).toBe(false);
		}
	});
});
