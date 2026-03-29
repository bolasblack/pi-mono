---
title: "Hook context piggybacks on existing triggers, never triggers independent LLM turns"
description: "additionalContext from hook callbacks is observer data — it attaches to the nearest natural trigger (user message, tool result) instead of injecting steering messages that cause extra LLM turns."
tags: architecture, extension
---

## Context

`registerClaudeHooks()` translates Claude Code hook events into pi events. Each hook callback can return `additionalContext` — supplementary information like context window usage, AGD validation results, or daemon status.

Previously, all `additionalContext` was injected via `injectHookContext()` → `pi.sendMessage(deliverAs: "steer")`, creating independent steering messages. When multiple `registerClaudeHooks()` instances register on the same event (skill hooks, settings hooks, ccc-statusd plugin), each event fires multiple handlers, each producing a steering message. With `steeringMode: "one-at-a-time"`, the steering queue accumulates faster than it drains during tool chains, causing the agent to emit meaningless replies ("代码已通过", "等你指示") when the queue flushes after the last tool completes.

## Decision

### 设计准则

1. **Hook context 不触发独立 turn** — `additionalContext` 是附属信息，不应作为独立消息触发 LLM 回复。它应搭便车到已有的触发点（用户消息、tool result）上，在同一个 turn 中被 LLM 看到。

   当 hook context 作为 steering message 注入时，它会独立触发一次 LLM turn。LLM 被迫回复这条消息，但它只是环境上下文（如 context window 使用率、AGD 验证结果），并不需要 LLM 做任何事情。LLM 只能回复 "好的" 或 "等你指示" 之类的废话，白白消耗一次 API 调用。

2. **只有用户和工具能驱动 agent** — Agent loop 的推进应该只由两件事驱动：用户发消息、工具返回结果。Hook 产生的上下文是观察者，不是驱动者。

   每个 hook event 都有一个自然的"宿主"——它附属于某个已有的触发点。SessionStart 的上下文在用户发第一条消息前不需要被处理，因为 agent 在用户说话之前根本不会做任何事。UserPromptSubmit 的上下文紧接着就是用户消息，用户消息本身就会触发 LLM turn，context 搭这趟车就够了。PreToolUse 的上下文是在工具执行前产生的，但 LLM 在执行完工具、收到 tool result 后才会被再次调用，所以 context 的自然宿主是对应的 tool result。PostToolUse 的上下文本身就是关于刚完成的 tool 的，附加到 tool result 是最正确的位置。

3. **多 hook 实例不应放大开销** — 同一个 event 上注册 N 个 hook，产生的 LLM turn 数应该和注册 1 个时一样（零额外 turn），不应随 N 线性增长。

   当前架构下，pi-claude skill hooks、pi-claude settings hooks、ccc-statusd pi plugin 三个独立的 `registerClaudeHooks()` 调用都注册在 `tool_result` event 上。每个 tool_result 触发 3 个 handler，其中 ~2 个返回 `additionalContext`，产生 2 条 steering message。由于 `steeringMode: "one-at-a-time"`，每个 turn 只消费 1 条，steering queue 每个 tool call 净增 ~1 条。一条 8 个 tool call 的 chain 结束后会有 ~8 条 pending steering 逐条排空，每条触发一次无意义的 LLM turn。如果改为搭便车到 tool result，无论注册多少个 hook 实例，所有 context 通过 `emitToolResult` chaining 合并到同一个 tool result 里，零额外 turn。

### 实现映射

| Hook | 注入方式 | 搭便车对象 |
|---|---|---|
| SessionStart | `pi.sendMessage()` (appendMessage, no deliverAs) | 用户第一条消息 |
| UserPromptSubmit | `pi.sendMessage()` (appendMessage, no deliverAs) | 用户当前消息 |
| PreToolUse | 缓存到 `Map<toolCallId, string>` | 对应 tool 的 PostToolUse |
| PostToolUse | 返回 `{ content: [...event.content, appendedText] }` | 当前 tool result |

## Consequences

- Zero extra LLM turns from hook context, regardless of how many hook instances are registered.
- `injectHookContext()` is preserved as an exported utility for third-party extensions that intentionally want steering behavior.
- PreToolUse context is buffered in memory (Map keyed by toolCallId) until the corresponding PostToolUse fires. If a tool call is somehow never completed, the entry leaks — acceptable since the Map is scoped to the `registerClaudeHooks` instance lifetime.
