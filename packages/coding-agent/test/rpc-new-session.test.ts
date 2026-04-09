/**
 * RPC `new_session` command tests focused on session-id/mode validation.
 *
 * These exercise the RPC boundary without an API key by mocking runtimeHost
 * behavior — the goal is to verify the new_session handler delegates to the
 * shared resolver (so invalid IDs produce a structured RPC error) rather
 * than silently succeeding as the pre-refactor code did.
 */

import { existsSync, mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Agent } from "@earendil-works/pi-agent-core";
import { type AssistantMessage, type AssistantMessageEvent, EventStream, getModel } from "@earendil-works/pi-ai";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AgentSession } from "../src/core/agent-session.js";
import type { AgentSessionRuntime } from "../src/core/agent-session-runtime.js";
import { AuthStorage } from "../src/core/auth-storage.js";
import { ModelRegistry } from "../src/core/model-registry.js";
import { SessionManager } from "../src/core/session-manager.js";
import { SettingsManager } from "../src/core/settings-manager.js";
import { runRpcMode } from "../src/modes/rpc/rpc-mode.js";
import { createTestResourceLoader } from "./utilities.js";

const rpcIo = vi.hoisted(() => ({
	outputLines: [] as string[],
	lineHandler: undefined as ((line: string) => void) | undefined,
}));

vi.mock("../src/core/output-guard.js", () => ({
	takeOverStdout: vi.fn(),
	writeRawStdout: (line: string) => {
		rpcIo.outputLines.push(line);
	},
}));

vi.mock("../src/modes/interactive/theme/theme.js", () => ({ theme: {} }));

vi.mock("../src/modes/rpc/jsonl.js", () => ({
	attachJsonlLineReader: vi.fn((_stream: NodeJS.ReadableStream, onLine: (line: string) => void) => {
		rpcIo.lineHandler = onLine;
		return () => {};
	}),
	serializeJsonLine: (value: unknown) => `${JSON.stringify(value)}\n`,
}));

class NoopAssistantStream extends EventStream<AssistantMessageEvent, AssistantMessage> {
	constructor() {
		super(
			(event) => event.type === "done" || event.type === "error",
			(event) => {
				if (event.type === "done") return event.message;
				if (event.type === "error") return event.error;
				throw new Error("Unexpected event type");
			},
		);
	}
}

type ParsedOutputLine = Record<string, unknown>;

function parseOutputLines(outputLines: string[]): ParsedOutputLine[] {
	return outputLines
		.flatMap((line) => line.split("\n"))
		.filter((line) => line.trim().length > 0)
		.map((line) => JSON.parse(line) as ParsedOutputLine);
}

function findResponse(outputLines: string[], id: string): ParsedOutputLine | undefined {
	return parseOutputLines(outputLines).find((record) => record.id === id && record.type === "response");
}

function createRuntimeHost(): {
	runtimeHost: AgentSessionRuntime;
	cleanup: () => Promise<void>;
} {
	const tempDir = join(tmpdir(), `pi-rpc-new-session-${Date.now()}-${Math.random().toString(36).slice(2)}`);
	mkdirSync(tempDir, { recursive: true });

	const model = getModel("anthropic", "claude-sonnet-4-5");
	if (!model) {
		throw new Error("Test model not found");
	}

	const agent = new Agent({
		getApiKey: () => "test-key",
		initialState: {
			model,
			systemPrompt: "Test",
			tools: [],
		},
		streamFn: () => new NoopAssistantStream(),
	});

	const sessionManager = SessionManager.inMemory(tempDir);
	const settingsManager = SettingsManager.create(tempDir, tempDir);
	const authStorage = AuthStorage.create(join(tempDir, "auth.json"));
	const modelRegistry = ModelRegistry.create(authStorage, tempDir);
	authStorage.setRuntimeApiKey("anthropic", "test-key");

	const session = new AgentSession({
		agent,
		sessionManager,
		settingsManager,
		cwd: tempDir,
		modelRegistry,
		resourceLoader: createTestResourceLoader(),
	});

	const runtimeHost = {
		session,
		newSession: vi.fn(async () => ({ cancelled: true })),
		switchSession: vi.fn(async () => ({ cancelled: true })),
		fork: vi.fn(async () => ({ cancelled: true, selectedText: "" })),
		dispose: vi.fn(async () => {}),
		setRebindSession: vi.fn(),
	} as unknown as AgentSessionRuntime;

	return {
		runtimeHost,
		cleanup: async () => {
			try {
				if (session.isStreaming) {
					await session.abort();
				}
			} catch {
				// ignore test cleanup failures
			}
			session.dispose();
			if (existsSync(tempDir)) {
				rmSync(tempDir, { recursive: true });
			}
		},
	};
}

async function startRpcMode(): Promise<{
	lineHandler: (line: string) => void;
	cleanup: () => Promise<void>;
}> {
	rpcIo.outputLines = [];
	rpcIo.lineHandler = undefined;

	const { runtimeHost, cleanup } = createRuntimeHost();
	void runRpcMode(runtimeHost);
	await vi.waitFor(() => expect(rpcIo.lineHandler).toBeDefined());

	return { lineHandler: rpcIo.lineHandler!, cleanup };
}

describe("RPC new_session validation", () => {
	let originalAgentDirEnv: string | undefined;
	let agentDirForTests: string;

	beforeEach(() => {
		// Isolate from the user's real ~/.pi/agent — resolveSessionForMode ends up
		// calling SessionManager.listAll() which walks getSessionsDir() under
		// getAgentDir(). Without this, the test would read (and create) files in
		// the developer's actual config directory.
		agentDirForTests = join(
			tmpdir(),
			`pi-rpc-new-session-agent-${Date.now()}-${Math.random().toString(36).slice(2)}`,
		);
		mkdirSync(agentDirForTests, { recursive: true });
		originalAgentDirEnv = process.env.PI_CODING_AGENT_DIR;
		process.env.PI_CODING_AGENT_DIR = agentDirForTests;
	});

	afterEach(() => {
		if (originalAgentDirEnv === undefined) {
			delete process.env.PI_CODING_AGENT_DIR;
		} else {
			process.env.PI_CODING_AGENT_DIR = originalAgentDirEnv;
		}
		if (existsSync(agentDirForTests)) {
			rmSync(agentDirForTests, { recursive: true, force: true });
		}
		rpcIo.outputLines = [];
		rpcIo.lineHandler = undefined;
	});

	it("rejects invalid session IDs on create mode (regression for RPC skipping validation)", async () => {
		const { lineHandler, cleanup } = await startRpcMode();

		try {
			lineHandler(
				JSON.stringify({
					id: "req-invalid-create",
					type: "new_session",
					sessionId: "bad!id",
					sessionMode: "create",
				}),
			);

			await vi.waitFor(() => {
				const response = findResponse(rpcIo.outputLines, "req-invalid-create");
				expect(response).toBeDefined();
				expect(response).toMatchObject({
					id: "req-invalid-create",
					type: "response",
					command: "new_session",
					success: false,
				});
				expect(String(response?.error)).toMatch(/Invalid session ID/);
			});
		} finally {
			await cleanup();
		}
	});

	it("rejects invalid session IDs on auto mode", async () => {
		const { lineHandler, cleanup } = await startRpcMode();

		try {
			lineHandler(
				JSON.stringify({ id: "req-invalid-auto", type: "new_session", sessionId: "bad!id", sessionMode: "auto" }),
			);

			await vi.waitFor(() => {
				const response = findResponse(rpcIo.outputLines, "req-invalid-auto");
				expect(response).toMatchObject({
					id: "req-invalid-auto",
					type: "response",
					command: "new_session",
					success: false,
				});
				expect(String(response?.error)).toMatch(/Invalid session ID/);
			});
		} finally {
			await cleanup();
		}
	});

	it("rejects invalid session IDs on default (auto) mode when sessionMode omitted", async () => {
		const { lineHandler, cleanup } = await startRpcMode();

		try {
			lineHandler(JSON.stringify({ id: "req-invalid-default", type: "new_session", sessionId: "bad!id" }));

			await vi.waitFor(() => {
				const response = findResponse(rpcIo.outputLines, "req-invalid-default");
				expect(response).toMatchObject({
					id: "req-invalid-default",
					type: "response",
					command: "new_session",
					success: false,
				});
				expect(String(response?.error)).toMatch(/Invalid session ID/);
			});
		} finally {
			await cleanup();
		}
	});
});
