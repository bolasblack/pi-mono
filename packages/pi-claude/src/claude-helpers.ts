export type {
	BaseHookParams,
	ClaudeHookCallbacks,
	ClaudeHookResult,
	HookParams,
	Logger,
	NotificationParams,
	PostToolUseParams,
	PreCompactParams,
	PreToolUseParams,
	SessionEndParams,
	SessionStartParams,
	StopParams,
	UserPromptSubmitParams,
} from "./extension/claude-hooks.js";
export {
	getTranscriptPath,
	hookPayload,
	injectHookContext,
	registerClaudeHooks,
} from "./extension/claude-hooks.js";
export type { HookOutput } from "./extension/hook-runner.js";
export { mergeHookResults, spawnHookCommand } from "./extension/hook-runner.js";
export { createLogger } from "./extension/logger.js";
