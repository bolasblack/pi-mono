import type { CompatArgs } from "./types.js";

export function makeArgs(overrides: Partial<CompatArgs> = {}): CompatArgs {
	return {
		continueLast: false,
		forkSession: false,
		print: false,
		resumePicker: false,
		settings: [],
		messages: [],
		warnings: [],
		unknownFlags: [],
		...overrides,
	};
}
