# Spec: tmux-fork Skill

## Overview

A pi skill that forks the current session into a new tmux pane or window,
allowing parallel conversation branches in separate terminals.

## Trigger

Activated when the user says `/tmux-fork`, "fork to tmux", "open in new pane",
or similar requests to branch the conversation.

## Requirements

- Must be running inside a tmux session. If not, the skill reports an error.

## Behavior

1. The skill reads the current session ID from the conversation context.
2. It runs `tmux-fork.sh <session-id> [window|pane]` to open a new tmux pane
   (default) or window with a forked pi session.
3. The forked session starts with the full conversation history.

## Script Interface

```
bash .pi/skills/tmux-fork/scripts/tmux-fork.sh <session-id> [window|pane]
```

- `pane` (default): horizontal split via `tmux split-window -h`
- `window`: new tmux window via `tmux new-window`

## Binary Detection

- Default binary: `pi`
- Override with `TMUX_FORK_BINARY` env var.
- Set `TMUX_FORK_BINARY_CLAUDE_COMPATIBLE=1` to use Claude Code CLI syntax
  (`--resume <id> --fork-session` instead of `--fork <id>`).
- Auto-detects Claude Code compatibility if `CLAUDECODE` or `CLAUDE_CODE` env
  vars are set.

## Verification

1. Inside tmux, invoking the skill with a valid session ID opens a new pane
   with the forked session.
2. Using `window` mode opens a new tmux window instead.
3. Outside tmux, the script exits with "Not inside tmux" error.
4. With `TMUX_FORK_BINARY=pi-claude TMUX_FORK_BINARY_CLAUDE_COMPATIBLE=1`,
   the script launches `pi-claude --resume <id> --fork-session`.
5. Invalid mode argument (not "window" or "pane") produces an error.
