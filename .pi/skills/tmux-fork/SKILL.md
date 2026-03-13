---
name: tmux-fork
description: "Fork the current pi session into a new tmux pane. Use when user says /tmux-fork, 'fork to tmux', 'open in new pane', or wants to branch the conversation into a separate tmux pane."
---

# tmux-fork

Fork the current pi session into a new tmux window or pane using pi's built-in fork mechanism.

## Requirements
- Must be inside tmux

## Instructions

When the user invokes this skill:

1. **Get the current session ID** from the most recent `Session ID:` in the conversation (short 8-char or full UUID).

2. **Run the fork script**:
   ```bash
   bash .pi/skills/tmux-fork/scripts/tmux-fork.sh <session-id> [window|pane]
   ```

   - Second argument: `pane` (default, horizontal split) or `window` (new tmux window)
   - Binary is auto-detected from the parent process. Override with env vars if needed:
     - `TMUX_FORK_BINARY` — binary name/path (e.g. `pi`, `pi-claude`, `claude`)
     - `TMUX_FORK_BINARY_CLAUDE_COMPATIBLE=1` — use `--resume <id> --fork-session` instead of `--fork <id>`

3. **Report the result** to the user.

## Example

```bash
# Fork into a new pane (default)
bash .pi/skills/tmux-fork/scripts/tmux-fork.sh 6b4e6a87

# Fork into a new window
bash .pi/skills/tmux-fork/scripts/tmux-fork.sh 6b4e6a87 window

# With explicit binary
TMUX_FORK_BINARY=pi-claude TMUX_FORK_BINARY_CLAUDE_COMPATIBLE=1 \
  bash .pi/skills/tmux-fork/scripts/tmux-fork.sh 6b4e6a87 pane
```

## Troubleshooting

**"Not inside tmux"**: Run pi inside tmux first.

**Session not found**: Ensure at least one assistant message exists in the session.
