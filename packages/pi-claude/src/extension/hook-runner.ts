import { spawn } from "node:child_process";

export interface HookOutput {
	continue?: boolean;
	suppressOutput?: boolean;
	stopReason?: string;
	decision?: string;
	reason?: string;
	systemMessage?: string;
	hookSpecificOutput?: {
		hookEventName?: string;
		additionalContext?: string;
		permissionDecision?: string;
		permissionDecisionReason?: string;
		decision?: { behavior: string };
	};
}

export interface HookCommandOptions {
	timeoutMs?: number;
	cwd?: string;
}

const DEFAULT_HOOK_TIMEOUT_MS = 10_000;

function looksLikeHookOutput(value: unknown): value is HookOutput {
	if (!value || typeof value !== "object") return false;
	const knownKeys = [
		"continue",
		"suppressOutput",
		"stopReason",
		"decision",
		"reason",
		"systemMessage",
		"hookSpecificOutput",
	];
	return knownKeys.some((key) => key in (value as Record<string, unknown>));
}

function parseHookOutputFromStdout(stdout: string): HookOutput | null {
	const trimmed = stdout.trim();
	if (!trimmed) return null;

	try {
		const parsed = JSON.parse(trimmed);
		if (looksLikeHookOutput(parsed)) return parsed;
	} catch {
		// continue
	}

	let fallback: HookOutput | null = null;

	// Strategy 1: Try each line from last to first (handles log lines before JSON)
	const lines = trimmed.split("\n").filter(Boolean);
	for (let i = lines.length - 1; i >= 0; i--) {
		try {
			const parsed = JSON.parse(lines[i]!);
			if (looksLikeHookOutput(parsed)) return parsed;
			if (!fallback && parsed && typeof parsed === "object") {
				fallback = parsed as HookOutput;
			}
		} catch {
			// try previous line
		}
	}

	// Strategy 2: Try parsing from each line-start `{` to end of string
	// (handles pretty-printed multi-line JSON after log lines)
	for (let i = lines.length - 1; i >= 0; i--) {
		if (!lines[i]!.trimStart().startsWith("{")) continue;
		const candidate = lines.slice(i).join("\n").trim();
		if (candidate === lines[i]) continue; // already tried as single line
		try {
			const parsed = JSON.parse(candidate);
			if (looksLikeHookOutput(parsed)) return parsed;
			if (!fallback && parsed && typeof parsed === "object") {
				fallback = parsed as HookOutput;
			}
		} catch {
			// try previous candidate
		}
	}

	return fallback;
}

export function spawnHookCommand(
	command: string,
	args: string[],
	payload: Record<string, any>,
	env: Record<string, string | undefined>,
	label: string,
	options: HookCommandOptions = {},
): Promise<HookOutput | null> {
	return new Promise((resolve) => {
		const child = spawn(command, args, {
			shell: args.length === 0,
			env,
			cwd: options.cwd,
		});

		const timeoutMs = options.timeoutMs ?? DEFAULT_HOOK_TIMEOUT_MS;

		let stdout = "";
		let stderr = "";
		let resolved = false;

		const finish = (value: HookOutput | null) => {
			if (resolved) return;
			resolved = true;
			clearTimeout(timeout);
			resolve(value);
		};

		const timeout = setTimeout(() => {
			console.error(`[${label}] timed out after ${timeoutMs}ms`);
			child.kill("SIGKILL");
			finish({ hookSpecificOutput: { additionalContext: `[${label}] ⚠ \`${command}\` — timed out` } });
		}, timeoutMs);

		child.stdout.on("data", (chunk: Buffer) => {
			stdout += chunk.toString();
		});

		child.stderr.on("data", (chunk: Buffer) => {
			stderr += chunk.toString();
		});

		child.on("close", (code) => {
			if (resolved) return;
			if (code === null) {
				// Killed by signal (e.g., timeout SIGKILL) — already logged by timeout handler
				finish({ hookSpecificOutput: { additionalContext: `[${label}] ⚠ \`${command}\` — killed by signal` } });
				return;
			}
			if (code !== 0) {
				console.error(`[${label}] exited with code ${code}`);
				const errMsg = stderr.trim().split("\n").pop() || `exit code ${code}`;
				finish({ hookSpecificOutput: { additionalContext: `[${label}] ✗ \`${command}\` — ${errMsg}` } });
				return;
			}

			const parsed = parseHookOutputFromStdout(stdout);
			if (parsed) {
				if (!parsed.hookSpecificOutput) parsed.hookSpecificOutput = {};
				const existing = parsed.hookSpecificOutput.additionalContext;
				if (existing && existing.trim() !== "") {
					// Hook provided its own additionalContext — prefix with label
					parsed.hookSpecificOutput.additionalContext = `[${label}] ${existing}`;
				} else {
					parsed.hookSpecificOutput.additionalContext = `[${label}] ✓ \`${command}\``;
				}
				finish(parsed);
			} else {
				finish({ hookSpecificOutput: { additionalContext: `[${label}] ✓ \`${command}\`` } });
			}
		});

		child.on("error", (err) => {
			console.error(`[${label}] spawn error:`, err);
			finish(null);
		});

		child.stdin.on("error", (err: NodeJS.ErrnoException) => {
			if (err.code === "EPIPE" || err.code === "ERR_STREAM_DESTROYED") return;
		});

		try {
			child.stdin.write(JSON.stringify(payload));
			child.stdin.end();
		} catch {
			// Process may have exited before stdin write completed (EPIPE)
		}
	});
}

export function mergeHookResults(results: (HookOutput | null)[]): HookOutput | null {
	const valid = results.filter((r): r is HookOutput => r !== null);
	if (valid.length === 0) return null;
	if (valid.length === 1) return valid[0]!;

	const merged: HookOutput = {};

	for (const r of valid) {
		if (r.continue === false) merged.continue = false;
		if (r.decision === "block") merged.decision = "block";

		if (r.stopReason && !merged.stopReason) merged.stopReason = r.stopReason;
		if (r.reason && !merged.reason) merged.reason = r.reason;
		if (r.systemMessage && !merged.systemMessage) merged.systemMessage = r.systemMessage;

		if (r.hookSpecificOutput) {
			if (!merged.hookSpecificOutput) merged.hookSpecificOutput = {};

			if (r.hookSpecificOutput.additionalContext) {
				merged.hookSpecificOutput.additionalContext = merged.hookSpecificOutput.additionalContext
					? `${merged.hookSpecificOutput.additionalContext}\n${r.hookSpecificOutput.additionalContext}`
					: r.hookSpecificOutput.additionalContext;
			}

			const pd = r.hookSpecificOutput.permissionDecision;
			if (pd) {
				const current = merged.hookSpecificOutput.permissionDecision;
				if (pd === "deny") {
					merged.hookSpecificOutput.permissionDecision = "deny";
					if (r.hookSpecificOutput.permissionDecisionReason) {
						merged.hookSpecificOutput.permissionDecisionReason = r.hookSpecificOutput.permissionDecisionReason;
					}
				} else if (pd === "ask" && current !== "deny") {
					merged.hookSpecificOutput.permissionDecision = "ask";
					if (
						r.hookSpecificOutput.permissionDecisionReason &&
						!merged.hookSpecificOutput.permissionDecisionReason
					) {
						merged.hookSpecificOutput.permissionDecisionReason = r.hookSpecificOutput.permissionDecisionReason;
					}
				} else if (pd === "allow" && !current) {
					merged.hookSpecificOutput.permissionDecision = "allow";
				}
			}
		}
	}

	return merged;
}
