import { describe, expect, test } from "bun:test";
import { runInParallelStable } from "./ordered-parallel.js";

describe("runInParallelStable", () => {
	test("returns results in input order even when completion order differs", async () => {
		const result = await runInParallelStable([1, 2, 3], async (id) => {
			if (id === 1) await Bun.sleep(50);
			if (id === 2) await Bun.sleep(10);
			if (id === 3) await Bun.sleep(1);
			return `ctx-${id}`;
		});

		expect(result).toEqual(["ctx-1", "ctx-2", "ctx-3"]);
	});
});
