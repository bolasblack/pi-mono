# Spec: Refresh Agent Model After Extension Initialization

> **Archived**: Implemented upstream in [#2291](https://github.com/badlogic/pi-mono/issues/2291).

## Overview

Extensions can modify provider configuration (e.g., baseUrl) via
`pi.registerProvider()` during `session_start`. The agent must pick up these
changes before the first LLM request.

## Problem

1. `createAgentSession()` selects a model and creates the Agent with it.
2. Extensions initialize later in `bindExtensions()` (`session_start` event).
3. An extension calls `pi.registerProvider("anthropic", { baseUrl: "..." })`,
   which updates models in `ModelRegistry`.
4. The Agent still holds the old model object with the original baseUrl.
5. First message uses the stale model, sending the request to the wrong endpoint.

## Fix

After emitting `session_start` in `bindExtensions()`, call
`refreshModelFromRegistry()` to re-fetch the current model from the registry.
This syncs any baseUrl/headers changes made by extensions without side effects
(no session entries, no settings writes, no events).

The same refresh is applied in the extension reload path (`_initializeSession`).

## Verification

1. Create an extension that overrides anthropic baseUrl via `registerProvider`.
2. Set `ANTHROPIC_API_KEY` to a dummy value and `ANTHROPIC_BASE_URL` to a proxy.
3. Start pi-claude and send a message.
4. Without fix: 401 error (request goes to api.anthropic.com with dummy key).
5. With fix: request goes to the proxy as expected.
