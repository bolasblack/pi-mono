/**
 * Translate parsed CompatArgs into pi CLI argv.
 *
 * Handles model ID normalization, file reading for prompt args,
 * tool mapping, session mode selection, and extension injection.
 *
 * All environment access is injected — no direct process.env reads.
 */

import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { CompatArgs } from "./types.js";

/** Environment variables used by the translator. */
export type TranslateEnv = Record<string, string | undefined>;

// =========================================================================
// Helpers
// =========================================================================

function isUuid(value: string): boolean {
	return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

async function loadFileText(path: string, description: string): Promise<string> {
	try {
		return await readFile(path, "utf-8");
	} catch {
		throw new Error(`Failed to read ${description}: ${path}`);
	}
}

/**
 * Normalize Claude Code model IDs for pi.
 *
 * Claude Code appends bracket-enclosed metadata (e.g., `[fast]`, `[thinking]`)
 * to model IDs. These are not part of the actual API model ID and must be stripped.
 * Non-claude models are passed through unchanged.
 */
export function rewriteClaudeModel(model: string): string {
	if (model.includes("/")) {
		const afterSlash = model.slice(model.indexOf("/") + 1);
		if (!afterSlash.startsWith("claude-")) return model;
		return `${model.slice(0, model.indexOf("/") + 1)}${afterSlash.replace(/\[.*\]$/, "")}`;
	}

	if (!model.startsWith("claude-")) return model;

	return `anthropic/${model.replace(/\[.*\]$/, "")}`;
}

/**
 * Build the list of candidate paths where extension.ts might live,
 * given the directory of the current module and the working directory.
 */
export function extensionCandidates(baseDir: string, cwd: string): string[] {
	return [
		resolve(baseDir, "../extension.ts"),
		resolve(baseDir, "../src/extension.ts"),
		resolve(baseDir, "../../src/extension.ts"),
		resolve(cwd, "pi-claude/src/extension.ts"),
	];
}

export function resolveCompatExtensionFile(cwd: string = process.cwd()): string {
	const here = dirname(fileURLToPath(import.meta.url));
	const candidates = extensionCandidates(here, cwd);

	for (const candidate of candidates) {
		if (existsSync(candidate)) {
			return candidate;
		}
	}

	throw new Error("Unable to locate pi-claude extension entry.");
}

// =========================================================================
// Main translator
// =========================================================================

export async function translateToPiArgv(compat: CompatArgs, env: TranslateEnv = {}): Promise<string[]> {
	const argv: string[] = [];

	if (!compat.model && env.ANTHROPIC_MODEL) {
		compat.model = env.ANTHROPIC_MODEL;
	}

	if (compat.model) {
		argv.push("--model", rewriteClaudeModel(compat.model));
	}

	if (compat.systemPromptFile) {
		const content = await loadFileText(compat.systemPromptFile, "system prompt file");
		argv.push("--system-prompt", content);
	} else if (compat.systemPrompt) {
		argv.push("--system-prompt", compat.systemPrompt);
	}

	if (compat.appendSystemPromptFile) {
		const content = await loadFileText(compat.appendSystemPromptFile, "append system prompt file");
		argv.push("--append-system-prompt", content);
	} else if (compat.appendSystemPrompt) {
		argv.push("--append-system-prompt", compat.appendSystemPrompt);
	}

	for (const entry of compat.settings) {
		argv.push("--settings", entry);
	}

	if (env.DISABLE_AUTO_COMPACT) {
		argv.push("--settings", '{"compaction":{"enabled":false}}');
	}

	if (compat.print) {
		argv.push("--print");
	}

	if (compat.sessionId) {
		if (!isUuid(compat.sessionId)) {
			throw new Error("--session-id must be a valid UUID");
		}
		argv.push("--session", compat.sessionId, "--session-mode", "auto");
	} else if (compat.forkSession && compat.resumeTarget) {
		argv.push("--fork", compat.resumeTarget);
	} else if (compat.resumeTarget) {
		argv.push("--session", compat.resumeTarget);
	} else if (compat.resumePicker) {
		argv.push("--resume");
	} else if (compat.continueLast) {
		argv.push("--continue");
	}

	const extensionFile = resolveCompatExtensionFile();
	argv.push("--extension", extensionFile);

	argv.push(...compat.unknownFlags);
	argv.push(...compat.messages);

	return argv;
}
