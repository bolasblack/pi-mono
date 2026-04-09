/**
 * Pure session resolution for the --session / --session-mode CLI flags and
 * the equivalent RPC `new_session` command.
 *
 * This module is deliberately side-effect free: it does not call process.exit,
 * does not write to stdout/stderr, and does not mutate global state. Callers
 * (main.ts / rpc-mode.ts) are responsible for converting errors into the
 * appropriate top-level behavior (exit code + console.error for CLI, structured
 * RPC error for RPC).
 */

import { existsSync } from "node:fs";
import type { SessionMode } from "../cli/args.js";
import { SessionManager } from "./session-manager.js";

export interface ResolveSessionOptions {
	sessionId: string;
	mode: SessionMode | undefined;
	cwd: string;
	sessionDir: string | undefined;
}

export interface ResolveSessionError {
	code: "invalid_id" | "not_found" | "already_exists" | "missing_arg";
	message: string;
}

export type ResolveSessionResult =
	| { ok: true; manager: SessionManager; action: ResolveAction }
	| { ok: false; error: ResolveSessionError };

/**
 * Describes which branch produced the manager. Callers that care about the
 * difference (for example the RPC `new_session` handler, which needs to pick
 * between `switchSession(path)` and `newSession({ setup })`) can look at this.
 * CLI callers can ignore it — the manager is already ready to use.
 */
export type ResolveAction =
	| { type: "opened_existing"; path: string; sessionId: string; reusedFromCwd?: string }
	| { type: "created_new"; sessionId: string };

/** True for values that look like a filesystem path to a session file rather than a short ID. */
export function isSessionPathLike(sessionArg: string): boolean {
	return sessionArg.includes("/") || sessionArg.includes("\\") || sessionArg.endsWith(".jsonl");
}

/** Regex matching legal custom session IDs for create/auto modes. */
export function isValidSessionIdForCreation(sessionId: string): boolean {
	return /^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(sessionId);
}

/**
 * Resolve a session argument given an explicit mode.
 *
 * Modes:
 * - `continue`   - prefix-match existing session, error if not found.
 * - `create`     - exact-match; error if one already exists; else create fresh.
 * - `auto`       - prefix-match existing session; if not found, create fresh.
 * - `undefined`  - legacy behavior: prefix-match only (no create-on-miss).
 *
 * For `create` and `auto` the `sessionId` must pass `isValidSessionIdForCreation`
 * unless it looks like a path (see `isSessionPathLike`).
 */
export async function resolveSessionForMode(opts: ResolveSessionOptions): Promise<ResolveSessionResult> {
	const { sessionId, mode, cwd, sessionDir } = opts;

	if (!sessionId) {
		return { ok: false, error: { code: "missing_arg", message: "Session id is required" } };
	}

	// `create` mode: use exact-match so "foo" does not match an existing "foobar".
	if (mode === "create") {
		const existing = await findSessionExact(sessionId, cwd, sessionDir);
		if (existing) {
			return {
				ok: false,
				error: {
					code: "already_exists",
					message: `Session '${sessionId}' already exists`,
				},
			};
		}
		return createNewSession(sessionId, cwd, sessionDir);
	}

	// `continue`, `auto`, or undefined (legacy): prefix-match first.
	const found = await findSessionByPrefix(sessionId, cwd, sessionDir);
	if (found) {
		return {
			ok: true,
			manager: SessionManager.open(found.path, sessionDir),
			action: {
				type: "opened_existing",
				path: found.path,
				sessionId: found.id,
				reusedFromCwd: found.cwd && found.cwd !== cwd ? found.cwd : undefined,
			},
		};
	}

	if (mode === "continue") {
		return {
			ok: false,
			error: {
				code: "not_found",
				message: `Session '${sessionId}' not found`,
			},
		};
	}

	if (mode === "auto") {
		return createNewSession(sessionId, cwd, sessionDir);
	}

	// Legacy mode (undefined): match-only, report not found without creating.
	return {
		ok: false,
		error: {
			code: "not_found",
			message: `No session found matching '${sessionId}'`,
		},
	};
}

/**
 * Exact-match lookup used for `create` mode. Returns info about the first
 * session whose id exactly equals `sessionId` in the local or global scope.
 */
async function findSessionExact(
	sessionId: string,
	cwd: string,
	sessionDir: string | undefined,
): Promise<{ path: string; cwd: string; id: string } | null> {
	if (isSessionPathLike(sessionId)) {
		return existsSync(sessionId) ? { path: sessionId, cwd, id: sessionId } : null;
	}

	const localSessions = await SessionManager.list(cwd, sessionDir);
	const localMatch = localSessions.find((s) => s.id === sessionId);
	if (localMatch) {
		return { path: localMatch.path, cwd, id: localMatch.id };
	}

	const allSessions = await SessionManager.listAll();
	const globalMatch = allSessions.find((s) => s.id === sessionId);
	if (globalMatch) {
		return { path: globalMatch.path, cwd: globalMatch.cwd, id: globalMatch.id };
	}

	return null;
}

/**
 * Prefix-match lookup used for `continue`, `auto`, and legacy (undefined) mode.
 * Mirrors the original CLI `resolveSessionPath` semantics, including the
 * "look in current project first, then global" ordering.
 */
async function findSessionByPrefix(
	sessionArg: string,
	cwd: string,
	sessionDir: string | undefined,
): Promise<{ path: string; cwd: string; id: string } | null> {
	if (isSessionPathLike(sessionArg)) {
		return existsSync(sessionArg) ? { path: sessionArg, cwd, id: sessionArg } : null;
	}

	const localSessions = await SessionManager.list(cwd, sessionDir);
	const localMatch = localSessions.find((s) => s.id.startsWith(sessionArg));
	if (localMatch) {
		return { path: localMatch.path, cwd, id: localMatch.id };
	}

	const allSessions = await SessionManager.listAll();
	const globalMatch = allSessions.find((s) => s.id.startsWith(sessionArg));
	if (globalMatch) {
		return { path: globalMatch.path, cwd: globalMatch.cwd, id: globalMatch.id };
	}

	return null;
}

/** Allocate a fresh SessionManager for the given id. Validates id characters. */
function createNewSession(sessionId: string, cwd: string, sessionDir: string | undefined): ResolveSessionResult {
	if (isSessionPathLike(sessionId)) {
		const manager = SessionManager.open(sessionId, sessionDir);
		return {
			ok: true,
			manager,
			action: { type: "created_new", sessionId: manager.getSessionId() },
		};
	}

	if (!isValidSessionIdForCreation(sessionId)) {
		return {
			ok: false,
			error: {
				code: "invalid_id",
				message: `Invalid session ID '${sessionId}'. Use only letters, numbers, '.', '-', and '_' characters.`,
			},
		};
	}

	// TODO(session-manager-dedup): SessionManager.create() already initialises
	// a session with a random UUID; we immediately call newSession({ id }) to
	// overwrite it. Consider accepting an id option on create() to avoid the
	// double-init.
	const sm = SessionManager.create(cwd, sessionDir);
	sm.newSession({ id: sessionId });
	return { ok: true, manager: sm, action: { type: "created_new", sessionId } };
}
