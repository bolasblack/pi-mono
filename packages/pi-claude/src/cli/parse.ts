/**
 * Parse Claude Code CLI arguments into a structured CompatArgs object.
 */

import { SUPPORTED_FLAG_BY_TOKEN, UNSUPPORTED_FLAG_BY_NAME } from "./flags.js";
import type { CompatArgs, SupportedFlagName } from "./types.js";

function normalizeFlag(arg: string): string {
	const eq = arg.indexOf("=");
	return eq >= 0 ? arg.slice(0, eq) : arg;
}

function getInlineValue(arg: string): string | undefined {
	const eq = arg.indexOf("=");
	return eq >= 0 ? arg.slice(eq + 1) : undefined;
}

function isFlagToken(v: string | undefined): boolean {
	return typeof v === "string" && v.startsWith("-");
}

function applyFlag(args: CompatArgs, name: SupportedFlagName, value: string | undefined): void {
	switch (name) {
		case "--append-system-prompt":
			args.appendSystemPrompt = value;
			return;
		case "--append-system-prompt-file":
			args.appendSystemPromptFile = value;
			return;
		case "--continue":
			args.continueLast = true;
			return;
		case "--model":
			args.model = value;
			return;
		case "--fork-session":
			args.forkSession = true;
			return;
		case "--print":
			args.print = true;
			return;
		case "--resume":
			if (value) {
				args.resumeTarget = value;
			} else {
				args.resumePicker = true;
			}
			return;
		case "--session-id":
			args.sessionId = value;
			return;
		case "--settings":
			if (value) args.settings.push(value);
			return;
		case "--system-prompt":
			args.systemPrompt = value;
			return;
		case "--system-prompt-file":
			args.systemPromptFile = value;
			return;
		case "--help":
		case "--version":
			return;
	}
}

export function parseCompatArgs(argv: string[]): CompatArgs {
	const args: CompatArgs = {
		continueLast: false,
		forkSession: false,
		print: false,
		resumePicker: false,
		settings: [],
		messages: [],
		warnings: [],
		unknownFlags: [],
	};

	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i]!;
		const def = SUPPORTED_FLAG_BY_TOKEN.get(normalizeFlag(arg));

		if (def) {
			const inline = getInlineValue(arg);

			if (def.valueMode === "none") {
				applyFlag(args, def.name, undefined);
				continue;
			}

			if (inline !== undefined) {
				applyFlag(args, def.name, inline);
				continue;
			}

			const next = argv[i + 1];
			if (def.valueMode === "optional") {
				if (isFlagToken(next) || next === undefined) {
					applyFlag(args, def.name, undefined);
				} else {
					applyFlag(args, def.name, next);
					i++;
				}
				continue;
			}

			if (next === undefined) throw new Error(`${def.name} requires a value`);
			applyFlag(args, def.name, next);
			i++;
			continue;
		}

		if (arg.startsWith("-")) {
			const unsup = UNSUPPORTED_FLAG_BY_NAME.get(normalizeFlag(arg));
			if (unsup) {
				args.warnings.push(`Ignoring unsupported Claude flag: ${arg}`);
				if (!arg.includes("=") && unsup.takesValue && !isFlagToken(argv[i + 1])) {
					i++;
				}
				continue;
			}

			args.unknownFlags.push(arg);
			continue;
		}

		args.messages.push(arg);
	}

	return args;
}
