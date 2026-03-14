# Spec: Session Fork

> **Archived**: Implemented upstream in [#2290](https://github.com/badlogic/pi-mono/issues/2290).

## Overview

Fork an existing session into a new independent session that starts with the
full conversation history of the original but diverges from that point.

## pi CLI: --fork

```
pi --fork <session-id|path>
```

- Resolves the session argument using prefix matching (same as `--session`).
- If no matching session is found, exits with a non-zero status and an error.
- Creates a new session file containing all entries from the source session.
- The new session has its own unique ID and continues independently.
- The original session is not modified.

## pi-claude CLI: --fork-session

```
pi-claude --resume <session-id> --fork-session
```

- `--fork-session` must be combined with `--resume` (provides the source session).
- Without `--resume`, `--fork-session` is meaningless and should be ignored or warned.
- The forked session behaves the same as `--fork` above.

## Verification

1. Create a session with some conversation, note its ID.
2. `pi --fork <id>` opens a new session. The conversation history from the original is visible.
3. New messages in the forked session do not appear in the original.
4. New messages in the original do not appear in the forked session.
5. `pi --fork nonexistent` exits with a "session not found" error.
6. `pi-claude --resume <id> --fork-session` produces the same fork behavior.
7. The forked session file contains a session header with a different ID than the original.
