/**
 * Validate parsed CLI arguments for conflicting or invalid combinations.
 */

import type { CompatArgs } from "./types.js";

export function validateCompat(args: CompatArgs): void {
	if (args.forkSession && !args.continueLast && !args.resumeTarget && !args.resumePicker) {
		throw new Error("--fork-session requires --continue or --resume");
	}
	if (args.sessionId && (args.continueLast || args.resumePicker || args.resumeTarget)) {
		throw new Error("--session-id cannot be combined with --continue or --resume");
	}
	if (args.continueLast && (args.resumePicker || args.resumeTarget)) {
		throw new Error("--continue cannot be combined with --resume");
	}
	if (args.systemPrompt && args.systemPromptFile) {
		throw new Error("Use either --system-prompt or --system-prompt-file, not both");
	}
	if (args.print && args.messages.length === 0 && !args.continueLast && !args.resumeTarget) {
		throw new Error("--print requires a prompt argument or piped stdin");
	}
	if (args.forkSession && args.continueLast) {
		throw new Error("--fork-session with --continue is not supported yet in pi-claude");
	}
	if (args.forkSession && args.resumePicker) {
		throw new Error("--fork-session with --resume (no target) is not supported yet in pi-claude");
	}
}
