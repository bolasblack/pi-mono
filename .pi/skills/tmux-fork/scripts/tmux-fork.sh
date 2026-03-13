#!/usr/bin/env bash
# tmux-fork: Fork current pi session into a new tmux window or pane.
#
# Usage: tmux-fork.sh <session-id> [window|pane]
#
# Environment variables:
#   TMUX_FORK_BINARY                   - Binary to use (default: auto-detect from parent process, fallback "pi")
#   TMUX_FORK_BINARY_CLAUDE_COMPATIBLE - If "1" or "true", binary uses Claude Code CLI (--resume <id> --fork-session).
#                                        Otherwise uses pi CLI (--fork <id>).

set -euo pipefail

SESSION_ID="${1:?Usage: tmux-fork.sh <session-id> [window|pane]}"
MODE="${2:-pane}"

BINARY="${TMUX_FORK_BINARY:-}"
CLAUDE_COMPAT="${TMUX_FORK_BINARY_CLAUDE_COMPATIBLE:-}"

# --- Detect claude-compatible mode via env var ---
if [[ -z "$CLAUDE_COMPAT" && ( -n "${CLAUDECODE:-}" || -n "${CLAUDE_CODE:-}" ) ]]; then
    CLAUDE_COMPAT="1"
fi

# --- Detect binary if not provided ---
if [[ -z "$BINARY" ]]; then
    if [[ "$CLAUDE_COMPAT" == "1" || "$CLAUDE_COMPAT" == "true" ]]; then
        BINARY="claude"
    else
        BINARY="pi"
    fi
fi

# --- Check tmux ---
if [[ -z "${TMUX:-}" ]]; then
    echo "ERROR: Not inside tmux" >&2
    exit 1
fi

# --- Build command ---
if [[ "$CLAUDE_COMPAT" == "1" || "$CLAUDE_COMPAT" == "true" ]]; then
    CMD=("$BINARY" "--resume" "$SESSION_ID" "--fork-session")
else
    CMD=("$BINARY" "--fork" "$SESSION_ID")
fi

# --- Open window or pane ---
case "$MODE" in
    window)
        tmux new-window -- "${CMD[@]}"
        echo "Forked session $SESSION_ID in new window"
        ;;
    pane)
        tmux split-window -h -- "${CMD[@]}"
        echo "Forked session $SESSION_ID in new pane"
        ;;
    *)
        echo "ERROR: Invalid mode '$MODE'. Use 'window' or 'pane'." >&2
        exit 1
        ;;
esac

echo "Binary: $BINARY (claude-compatible: ${CLAUDE_COMPAT:-0})"
