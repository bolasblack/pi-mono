/**
 * CLI argument types and flag definitions for Claude Code compatibility.
 */

export interface CompatArgs {
	appendSystemPrompt?: string;
	appendSystemPromptFile?: string;
	continueLast: boolean;
	model?: string;
	print: boolean;
	resumeTarget?: string;
	resumePicker: boolean;
	sessionId?: string;
	settings: string[];
	systemPrompt?: string;
	systemPromptFile?: string;
	forkSession: boolean;
	messages: string[];
	warnings: string[];
	unknownFlags: string[];
}

export type FlagValueMode = "none" | "required" | "optional";

export type SupportedFlagName =
	| "--append-system-prompt"
	| "--append-system-prompt-file"
	| "--continue"
	| "--help"
	| "--model"
	| "--print"
	| "--resume"
	| "--session-id"
	| "--settings"
	| "--system-prompt"
	| "--system-prompt-file"
	| "--fork-session"
	| "--version";

export interface SupportedFlagDefinition {
	name: SupportedFlagName;
	short?: string;
	valueMode: FlagValueMode;
}

export interface UnsupportedFlagDefinition {
	name: string;
	takesValue: boolean;
}
