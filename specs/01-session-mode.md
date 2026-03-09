# Spec: --session-mode flag

## Overview

The `--session` flag gains a companion `--session-mode` flag that controls whether
the session ID should be opened, created, or handled automatically.

## CLI Behavior

### Flag syntax

```
pi --session <id|path> --session-mode <continue|create|auto>
```

- `--session-mode` requires `--session` to be present. If used alone, pi exits with an error.

### Modes

#### `continue`
- If a session with the given ID exists (prefix match), open it.
- If no match is found, exit with a non-zero status and an error message.

#### `create`
- If a session with the given ID already exists (exact match), exit with a non-zero status and an error message.
- If no match, create a new session with that ID.
- The session ID must match `^[a-zA-Z0-9][a-zA-Z0-9._-]*$`. Invalid IDs produce an error.

#### `auto`
- If a session with the given ID exists (prefix match), open it.
- If no match, create a new session with that ID.
- Same ID validation as `create`.

### Without --session-mode

When `--session` is used without `--session-mode`, existing prefix-match behavior is preserved.

## RPC Behavior

The `new_session` RPC command accepts optional `sessionId` and `sessionMode` fields:

```json
{
  "type": "new_session",
  "sessionId": "feature-auth",
  "sessionMode": "auto"
}
```

- Response includes `sessionId` and `sessionFile` fields when `sessionId` was provided.
- `continue` mode returns an error if the session is not found.
- `create` mode returns an error if the session already exists.
- `auto` mode opens or creates as needed.

## Verification

1. `pi --session foo --session-mode create` creates a new session; running it again errors.
2. `pi --session foo --session-mode continue` opens the session created in step 1.
3. `pi --session bar --session-mode continue` errors (no such session).
4. `pi --session baz --session-mode auto` creates a new session; running it again reopens it.
5. `pi --session-mode auto` (without --session) exits with an error.
6. `pi --session "invalid!id" --session-mode create` exits with an error about invalid characters.
7. RPC `new_session` with `sessionId: "test"` and `sessionMode: "create"` succeeds once, then errors on repeat.
