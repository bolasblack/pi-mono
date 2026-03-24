# Spec: Claude Code CLI Compatibility Wrapper (pi-claude)

## Overview

A `pi-claude` binary that accepts Claude Code CLI arguments and launches pi.
This allows tools and integrations built for `claude` CLI to work with pi.

## Binary

- Installed as `pi-claude` via the package's `bin` field in package.json.
- Process title is set to `pi`.

## Flag Handling

### Supported flags

| Flag | Behavior |
|------|----------|
| `--print`, `-p` | Non-interactive mode: process prompt and exit |
| `--model <id>` | Select model (strict matching, no fuzzy) |
| `--continue`, `-c` | Continue most recent session |
| `--resume [id\|name]`, `-r` | Resume a specific session or open picker |
| `--session-id <uuid>` | Open or create a session with a specific UUID |
| `--settings <path\|json>` | Override settings (JSON string or file path) |
| `--system-prompt <text>` | Set system prompt |
| `--system-prompt-file <file>` | Set system prompt from file |
| `--append-system-prompt <text>` | Append to system prompt |
| `--append-system-prompt-file <file>` | Append to system prompt from file |
| `--fork-session` | Fork the resumed session into a new session |
| `--help`, `-h` | Show help |
| `--version`, `-v` | Show version |

### Known unsupported flags

Claude Code flags that pi does not implement are recognized and produce a warning
to stderr. They do not cause a hard error. Examples: `--add-dir`, `--agent`,
`--dangerously-skip-permissions`, `--mcp-config`, etc.

### Unknown flags

Flags not recognized as either supported or known-unsupported cause an error and exit.

## Flag Interactions

- `--session-id` cannot combine with `--continue` or `--resume` (error).
- `--continue` cannot combine with `--resume` (error).
- `--system-prompt` and `--system-prompt-file` are mutually exclusive (error).
- `--print` requires at least one message (from args or stdin).
- `--*-file` variants for system prompts are restricted to `--print` mode.

## Model Resolution

- `provider/modelId` syntax: exact lookup.
- Bare `modelId`: must match exactly one model across providers. Ambiguous matches error.
- No prefix or fuzzy matching.

## Resume Resolution

- Exact session ID match first, then exact session name match.
- Duplicate name matches produce an error.
- Without an argument, opens the interactive session picker.

## Settings Merge

- Accepts a JSON string or path to a JSON file.
- Scalar settings (defaultModel, theme, etc.) applied via SettingsManager.
- Resource arrays (packages, extensions, skills) injected via additional resource paths.

## Piped stdin

If stdin is not a TTY, its content is read and prepended to the message list.

## Verification

1. `pi-claude --version` prints the pi version.
2. `pi-claude --help` shows help with all supported flags.
3. `pi-claude -p "hello"` runs in non-interactive print mode and exits.
4. `pi-claude --dangerously-skip-permissions` emits a warning but does not error.
5. `pi-claude --unknown-flag` exits with an error.
6. `pi-claude --session-id <uuid> --continue` exits with a conflict error.
7. `echo "hello" | pi-claude -p` reads stdin as the prompt.
8. `pi-claude --model nonexistent` exits with a model-not-found error.
9. `pi-claude --resume` (no args, interactive) opens the session picker.
10. `pi-claude --system-prompt "x" --system-prompt-file y` exits with a conflict error.
