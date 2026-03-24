#!/usr/bin/env node

/**
 * Claude Code CLI compatibility wrapper for pi.
 *
 * Translates Claude-style args to pi CLI args, injects pi-claude-extension,
 * then delegates execution to `pi`.
 */

import { spawn } from "node:child_process";
import { realpathSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { parseCompatArgs } from "./cli/parse.js";
import { translateToPiArgv } from "./cli/translate.js";
import { validateCompat } from "./cli/validate.js";

// =========================================================================
// Stdin
// =========================================================================

const STDIN_TIMEOUT_MS = 5_000;

async function readPipedStdin(): Promise<string | undefined> {
	if (process.stdin.isTTY) return undefined;
	return new Promise((resolveStdin) => {
		let data = "";
		let done = false;

		const finish = (value: string | undefined) => {
			if (done) return;
			done = true;
			clearTimeout(timer);
			resolveStdin(value);
		};

		const timer = setTimeout(() => {
			finish(data.trim().length > 0 ? data.trim() : undefined);
		}, STDIN_TIMEOUT_MS);

		process.stdin.setEncoding("utf8");
		process.stdin.on("data", (chunk) => {
			data += chunk;
		});
		process.stdin.on("end", () => {
			const trimmed = data.trim();
			finish(trimmed.length > 0 ? trimmed : undefined);
		});
		process.stdin.resume();
	});
}

// =========================================================================
// Help / Version
// =========================================================================

function printCompatHelp(): void {
	console.log(`Usage: pi-claude [options] [message...]

Claude Code compatible CLI wrapper for pi. Translates Claude Code flags
to pi equivalents and injects the pi-claude extension automatically.

Options:
  --model <id>                        Model to use. Claude model IDs are
                                      normalized (e.g. bracket metadata like
                                      [fast] is stripped, bare claude-* IDs
                                      get an anthropic/ prefix).
                                      Falls back to ANTHROPIC_MODEL env var.
  --continue, -c                      Continue the most recent session.
  --resume [id|name], -r              Resume a session by ID or name. Opens
                                      an interactive picker if no argument
                                      is given.
  --session-id <uuid>                 Use or create a session with this UUID.
                                      Cannot be combined with --continue or
                                      --resume.
  --fork-session                      Fork a session. Must be combined with
                                      --resume <id>.
  --print, -p                         Non-interactive (print) mode. Requires
                                      a prompt as a positional argument or
                                      via piped stdin.
  --system-prompt <text>              Set the system prompt. Mutually
                                      exclusive with --system-prompt-file.
  --system-prompt-file <path>         Set the system prompt from a file.
  --append-system-prompt <text>       Append text to the system prompt.
  --append-system-prompt-file <path>  Append to the system prompt from a file.
  --settings <path|json>              Apply settings overrides. Can be a file
                                      path or inline JSON. May be specified
                                      multiple times; all entries are applied
                                      in order.
  --help, -h                          Show this help and exit.
  --version, -v                       Show version and exit.

Environment variables:
  ANTHROPIC_MODEL            Default model when --model is not specified.
  DISABLE_AUTO_COMPACT       Set to any value to disable auto-compaction.
  PI_CLAUDE_PI_BIN           Path to the pi binary (default: "pi").

Stdin:
  Piped input is prepended to the message list. For example:
    echo "explain this" | pi-claude -p

Unsupported Claude Code flags are recognized and warned about, not
silently dropped. Unknown flags are passed through to pi as-is.`);
}

// =========================================================================
// Process spawning
// =========================================================================

async function spawnPi(piArgv: string[]): Promise<void> {
	const piBin = process.env.PI_CLAUDE_PI_BIN || "pi";

	await new Promise<void>((resolveSpawn, rejectSpawn) => {
		const child = spawn(piBin, piArgv, {
			stdio: "inherit",
			env: process.env,
		});

		child.on("error", (error) => {
			rejectSpawn(error);
		});

		child.on("exit", (code, signal) => {
			if (signal) {
				process.kill(process.pid, signal);
				return;
			}
			process.exit(code ?? 1);
		});

		child.on("close", () => {
			resolveSpawn();
		});
	});
}

// =========================================================================
// Main
// =========================================================================

async function run(): Promise<void> {
	const rawArgv = process.argv.slice(2);

	if (rawArgv.includes("--help") || rawArgv.includes("-h")) {
		printCompatHelp();
		process.exit(0);
	}
	if (rawArgv.includes("--version") || rawArgv.includes("-v")) {
		console.log("pi-claude 0.1.0");
		process.exit(0);
	}

	const compat = parseCompatArgs(rawArgv);

	for (const warning of compat.warnings) {
		console.error(`[pi-claude] Warning: ${warning}`);
	}

	const stdinContent = await readPipedStdin();
	if (stdinContent !== undefined) {
		compat.messages.unshift(stdinContent);
	}

	validateCompat(compat);
	const piArgv = await translateToPiArgv(compat, process.env);
	await spawnPi(piArgv);
}

export { parseCompatArgs } from "./cli/parse.js";
export type { CompatArgs } from "./cli/types.js";

function isMainModule(): boolean {
	if (!process.argv[1]) return false;
	try {
		return realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
	} catch {
		return false;
	}
}

if (isMainModule()) {
	void run().catch((error: unknown) => {
		const message = error instanceof Error ? error.message : String(error);
		console.error(`[pi-claude] ${message}`);
		process.exit(1);
	});
}
