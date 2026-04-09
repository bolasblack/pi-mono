/**
 * Unit tests for resolveSessionForMode().
 *
 * Covers every branch of the --session-mode matrix without touching
 * process.exit / stdout, so the RPC boundary is safe to call the same
 * function.
 */

import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { isSessionPathLike, isValidSessionIdForCreation, resolveSessionForMode } from "../src/core/session-resolver.js";

/** Write a minimal valid session file with the given id directly to `sessionDir`. */
function seedSession(sessionDir: string, sessionId: string, cwd: string): string {
	const timestamp = new Date().toISOString();
	const fileTimestamp = timestamp.replace(/[:.]/g, "-");
	const filePath = join(sessionDir, `${fileTimestamp}_${sessionId}.jsonl`);
	const lines = [
		JSON.stringify({
			type: "session",
			version: 3,
			id: sessionId,
			timestamp,
			cwd,
		}),
		JSON.stringify({
			type: "message",
			id: "e1",
			parentId: null,
			timestamp,
			message: {
				role: "assistant",
				content: [{ type: "text", text: "hi" }],
				api: "anthropic-messages",
				provider: "anthropic",
				model: "fake",
				usage: {
					input: 0,
					output: 0,
					cacheRead: 0,
					cacheWrite: 0,
					totalTokens: 0,
					cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
				},
				stopReason: "stop",
				timestamp: Date.now(),
			},
		}),
	];
	writeFileSync(filePath, `${lines.join("\n")}\n`);
	return filePath;
}

describe("session-resolver helpers", () => {
	it("isSessionPathLike matches slashes and .jsonl", () => {
		expect(isSessionPathLike("./foo/bar")).toBe(true);
		expect(isSessionPathLike("C:\\foo")).toBe(true);
		expect(isSessionPathLike("session.jsonl")).toBe(true);
		expect(isSessionPathLike("feature-auth")).toBe(false);
	});

	it("isValidSessionIdForCreation matches allowed characters", () => {
		expect(isValidSessionIdForCreation("feature-auth")).toBe(true);
		expect(isValidSessionIdForCreation("feature.auth")).toBe(true);
		expect(isValidSessionIdForCreation("Feature_123")).toBe(true);
		expect(isValidSessionIdForCreation("1foo")).toBe(true);
		expect(isValidSessionIdForCreation("_foo")).toBe(false);
		expect(isValidSessionIdForCreation("-foo")).toBe(false);
		expect(isValidSessionIdForCreation(".foo")).toBe(false);
		expect(isValidSessionIdForCreation("bad!id")).toBe(false);
		expect(isValidSessionIdForCreation("has space")).toBe(false);
		expect(isValidSessionIdForCreation("")).toBe(false);
	});
});

describe("resolveSessionForMode", () => {
	let tempDir: string;
	let sessionDir: string;
	let cwd: string;
	let originalAgentDirEnv: string | undefined;

	beforeEach(() => {
		tempDir = join(tmpdir(), `session-resolver-test-${Date.now()}-${Math.random().toString(36).slice(2)}`);
		sessionDir = join(tempDir, "sessions");
		cwd = join(tempDir, "cwd");
		mkdirSync(sessionDir, { recursive: true });
		mkdirSync(cwd, { recursive: true });
		// Isolate from the user's real ~/.pi/agent/sessions/ — SessionManager.listAll()
		// walks getSessionsDir() which reads getAgentDir() which honours this env var.
		originalAgentDirEnv = process.env.PI_CODING_AGENT_DIR;
		process.env.PI_CODING_AGENT_DIR = join(tempDir, "agent");
	});

	afterEach(() => {
		if (originalAgentDirEnv === undefined) {
			delete process.env.PI_CODING_AGENT_DIR;
		} else {
			process.env.PI_CODING_AGENT_DIR = originalAgentDirEnv;
		}
		rmSync(tempDir, { recursive: true, force: true });
	});

	// ------------------------------------------------------------------
	// create
	// ------------------------------------------------------------------

	it("create: creates a new session when id does not exist", async () => {
		const result = await resolveSessionForMode({
			sessionId: "feature-auth",
			mode: "create",
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.action.type).toBe("created_new");
		expect(result.manager.getSessionId()).toBe("feature-auth");
		expect(result.manager.getHeader()?.id).toBe("feature-auth");
	});

	it("create: returns already_exists when an exact match exists", async () => {
		seedSession(sessionDir, "seeded", cwd);

		const result = await resolveSessionForMode({
			sessionId: "seeded",
			mode: "create",
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(false);
		if (result.ok) return;
		expect(result.error.code).toBe("already_exists");
	});

	it("create: rejects an invalid id", async () => {
		const result = await resolveSessionForMode({
			sessionId: "bad!id",
			mode: "create",
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(false);
		if (result.ok) return;
		expect(result.error.code).toBe("invalid_id");
	});

	// ------------------------------------------------------------------
	// continue
	// ------------------------------------------------------------------

	it("continue: opens an existing session when a prefix match exists", async () => {
		seedSession(sessionDir, "feature-abc", cwd);

		const result = await resolveSessionForMode({
			sessionId: "feature",
			mode: "continue",
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.action.type).toBe("opened_existing");
		if (result.action.type === "opened_existing") {
			expect(result.action.sessionId).toBe("feature-abc");
		}
	});

	it("continue: returns not_found when no matching session", async () => {
		const result = await resolveSessionForMode({
			sessionId: "does-not-exist",
			mode: "continue",
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(false);
		if (result.ok) return;
		expect(result.error.code).toBe("not_found");
	});

	// ------------------------------------------------------------------
	// auto
	// ------------------------------------------------------------------

	it("auto: creates a fresh session when id does not match anything", async () => {
		const result = await resolveSessionForMode({
			sessionId: "fresh-one",
			mode: "auto",
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.action.type).toBe("created_new");
		expect(result.manager.getSessionId()).toBe("fresh-one");
	});

	it("auto: opens an existing session when a prefix match exists", async () => {
		seedSession(sessionDir, "auto-existing", cwd);

		const result = await resolveSessionForMode({
			sessionId: "auto-ex",
			mode: "auto",
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.action.type).toBe("opened_existing");
	});

	it("auto: rejects an invalid id before attempting creation", async () => {
		const result = await resolveSessionForMode({
			sessionId: "bad!id",
			mode: "auto",
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(false);
		if (result.ok) return;
		expect(result.error.code).toBe("invalid_id");
	});

	// ------------------------------------------------------------------
	// undefined mode (legacy prefix-match-only)
	// ------------------------------------------------------------------

	it("undefined mode: opens existing session by prefix match", async () => {
		seedSession(sessionDir, "legacy-session", cwd);

		const result = await resolveSessionForMode({
			sessionId: "legacy",
			mode: undefined,
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.action.type).toBe("opened_existing");
	});

	it("undefined mode: returns not_found when nothing matches (no create)", async () => {
		const result = await resolveSessionForMode({
			sessionId: "nothing-there",
			mode: undefined,
			cwd,
			sessionDir,
		});
		expect(result.ok).toBe(false);
		if (result.ok) return;
		expect(result.error.code).toBe("not_found");
	});
});
