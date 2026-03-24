---
name: add-changes
description: "Implement changes to this fork of pi-mono. Use when the user asks to add a feature, fix a bug, or make any code change to the project. Records requirements as a numbered spec in specs/, implements the change, then commits referencing the spec."
---

# Add Changes

Workflow for making changes to this pi-mono fork. Every change gets a spec before implementation.

## Workflow

### Step 1: Write the Spec

1. List existing specs to determine the next number:
   ```bash
   ls specs/
   ```
2. Create `specs/<NN>-<short-name>.md` with the next available number, using kebab-case for the name.
3. Follow the project's spec format (see existing specs for reference):
   - `# Spec: <Title>`
   - `## Overview` — what and why, 2-3 sentences
   - `## Problem` (if it's a fix) or `## Behavior` (if it's a feature) — details of the change
   - `## Fix` / `## Implementation` — how it's done, which files are affected
   - `## Verification` — concrete steps to confirm the change works
4. Keep it concise. Specs are a record of intent, not a design doc.
5. Present the spec to the user for confirmation before proceeding.

### Step 2: Implement the Change

1. Read all files you plan to modify before editing.
2. Make the changes described in the spec.
3. Run `npm run check` and fix all errors, warnings, and infos.
4. If tests are affected, run relevant test files and iterate until they pass.

### Step 3: Commit

1. Stage only the files you changed (never `git add -A` or `git add .`).
2. Commit message format:
   ```
   <type>(<scope>): <summary>

   Spec: specs/<NN>-<short-name>.md
   ```
   - `type`: `feat`, `fix`, `refactor`, `chore`, etc.
   - `scope`: the affected package (e.g., `coding-agent`, `ai`, `tui`)
   - Reference the spec file in the commit body.
3. Do NOT push unless the user asks.

## Notes

- If the user provides a pre-written spec or a link to an issue, use that as the basis — still create the spec file.
- If the change is trivial (typo, config tweak), the spec can be minimal but must still exist.
- One spec per logical change. Don't bundle unrelated changes.

## When to Skip the Spec

If the change **only** affects packages that don't exist in the upstream repo, you may skip creating a spec file. Before skipping, always verify by checking whether the package directory exists upstream:

```bash
git ls-remote --exit-code origin refs/heads/main >/dev/null 2>&1 && \
  git archive --remote=origin main -- <package-path> 2>/dev/null | tar t >/dev/null 2>&1 \
  && echo "EXISTS upstream" || echo "NOT in upstream"
```

Or more practically, check the upstream repo's directory listing (e.g., via `gh api` or by reading the upstream's package.json/workspace config).

Examples of packages that are typically fork-only and don't need specs:
- `packages/kernel-cljs`
- `packages/coding-agent-cljs`
- `packages/pi-claude`

**Do not hardcode this list** — always verify against upstream before deciding. Upstream may add new packages, or fork-only packages may get upstreamed.
