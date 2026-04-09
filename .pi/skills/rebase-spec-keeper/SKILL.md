---
name: rebase-spec-keeper
description: "Rebase this fork onto the latest upstream while preserving spec-defined local behavior. Use when the user wants to rebase onto upstream, sync a fork with upstream/main, preserve local behavior after upstream changes, or clean history after an upstream sync. Do not use for ordinary merge conflict resolution unrelated to spec-backed fork maintenance."
compatibility: "Requires a git repo with an upstream remote and a specs/ directory."
---

# rebase-spec-keeper

Rebuild a fork branch on top of current upstream by preserving intended behavior from `specs/`, not raw old commit shape.

## Core Rule

Treat canonical spec files as the source of truth. Old commits are evidence, not the contract.

## Spec State Rules

Classify specs into four buckets:

1. **Canonical specs** — still define behavior that must remain true on current upstream.
2. **Compatibility follow-ups** — temporary notes for adapting canonical specs to upstream changes; merge their intent back into the canonical spec's implementation, then delete or archive them.
3. **Obsoleted specs** — replaced by newer canonical specs; keep under `specs/obsoleted/`.
4. **Archived / abandoned specs** — no longer active implementation targets; keep under `specs/archived/` or `specs/abandoned/`.

Do not leave temporary rebase-only specs in the final active spec set.

## Use when / Don't use when

Use when:
- the user says to rebase onto upstream or sync the fork with upstream/main
- local behavior must still satisfy existing specs after upstream changes
- history needs cleanup after an upstream sync

Do not use when:
- the task is ordinary merge conflict resolution with no spec-backed fork maintenance
- the user is only adding a new feature unrelated to upstream sync

## Instructions

### 1. Fetch and inventory before changing history

First refresh upstream and inspect the current state:

```bash
git status --short --branch
git fetch upstream --prune
git rev-parse --short upstream/main
git log --oneline --reverse upstream/main..HEAD
find specs -maxdepth 2 -type f | sort
```

Record the upstream commit SHA used as the rebase target. If `git fetch upstream --prune` fails, stop and report the failure instead of rebasing against stale state.

Then classify local changes into:

- **Spec-backed features** — behavior that should survive upstream sync
- **Compatibility fixes** — code only needed because upstream APIs or structure changed
- **Fork-local packages/features** — packages not present upstream but intentionally kept
- **Transient noise** — checkpoint commits, temporary notes, handoff files, issue drafts, one-off diagnostics

Dirty worktree rules:

- Treat tracked dirty files and all untracked files as user/other-agent work unless proven otherwise.
- Do not restore, delete, move, or stage dirty files unless the user explicitly approves that exact file or path.
- Keep a written dirty/untracked disposition for the final report.
- Use an isolated worktree for rewrite work so dirty main-worktree files cannot be swept into commits.

### 2. Build a spec satisfaction table and map durable changes

For each active canonical spec, write down:

- whether upstream already provides the behavior
- whether the fork still needs it
- what code currently implements it
- what minimum upstream adaptation is needed

Use this format:

```markdown
| spec | still required | upstream gap | current status | minimum adaptation |
|---|---|---|---|---|
| specs/01-session-mode.md | yes | yes | partial | rpc runtimeHost migration |
```

Then map each durable local change to one of:

- an existing canonical spec in `specs/`
- a fork-only package that does not require a canonical spec because it does not exist upstream
- transient work that should not survive as a durable feature commit

If a durable local behavior has no canonical spec and is not fork-only, create or refine the canonical spec before finalizing the rewritten branch.

Do not create temporary rebase-only specs for one-off cleanup notes.

If a change only affects packages that do not exist in upstream, you may keep it as a fork-local commit without creating a canonical spec. Before treating a package as fork-only, verify it is not present upstream:

```bash
git ls-remote --exit-code upstream refs/heads/main >/dev/null 2>&1 && \
  git archive --remote=upstream main -- <package-path> 2>/dev/null | tar t >/dev/null 2>&1 \
  && echo "EXISTS upstream" || echo "NOT in upstream"
```

For fork-only packages, also check dependency version alignment before finalizing the rewritten stack:

- If a fork-only package depends on packages that do exist upstream, compare those dependency versions against current `upstream/main`.
- If upstream bumped one of those package versions, update the fork-only package's dependency entry to match the current upstream version.
- Treat upstream's already-adopted dependency versions as the approved baseline; do not run a separate dependency-safety gate merely to follow upstream.
- Run dependency safety checks only for new third-party dependencies that are not already introduced by upstream or explicitly approved by the user.
- Apply this only to fork-only packages maintained in the fork. Do **not** rewrite dependency versions inside upstream-owned packages just to mirror the bump.
- If package manifests change, sync the corresponding lockfile. Prefer deterministic regeneration with the project package manager, such as `npm install --package-lock-only --ignore-scripts`, instead of hand-editing large lockfile sections.

Typical example: if fork-only `packages/pi-claude` depends on an upstream-owned package whose version changed during the sync, update `packages/pi-claude/package.json` and `package-lock.json` accordingly as part of preserving that fork-only package.

### 3. Use an isolated worktree for history surgery

Never do the main history cleanup directly in a dirty working tree.

Create a temporary worktree and do the rebase there:

```bash
branch=$(git branch --show-current)
tmpdir=$(mktemp -d /tmp/pi-rebase-spec-keeper-XXXXXX)
git worktree add --detach "$tmpdir" HEAD
cd "$tmpdir"
git switch -c "${branch}-upstream-rewrite"
```

Before replacing the user's active branch, create a backup branch from the old tip:

```bash
git branch "<branch>-before-upstream-$(date +%Y%m%d-%H%M%S)" <old-tip>
```

Only after the rewritten branch passes verification should the main branch be updated.

### 3b. Use a clean-branch rebuild when rebase state becomes noisy

If interactive rebase, autosquash, or lockfile conflicts become hard to reason about, stop trying to salvage the conflicted rebase. Build a clean branch from the verified upstream target and cherry-pick only durable commits:

```bash
tmpdir=$(mktemp -d /tmp/pi-rebase-spec-keeper-clean-XXXXXX)
git worktree add -b "<branch>-upstream-clean" "$tmpdir" upstream/main
cd "$tmpdir"
git cherry-pick <durable-commit>...
```

Use this fallback especially when:

- lockfile conflict markers accumulate or nested conflict markers appear
- autosquash/fixup commits conflict with lockfile package entries
- transient commits are easier to drop by omission than by conflict resolution
- the clean result can be described more clearly than the conflicted rebase state

After rebuilding, regenerate lockfiles as needed, verify, then promote the clean branch.

### 4. Rewrite history around behavior, not accident

Preferred end state:

- one commit per durable feature/spec
- compatibility fixes squashed into the feature commit they restore
- fork-only packages kept in their own commits
- transient notes/config/docs split into separate chores or dropped entirely

During interactive rebase:

- **squash compatibility fixes into the feature/spec commit they preserve**
- **drop superseded intermediate wrapper commits** once their lasting pieces are reassigned
- **keep fork-only packages separate** when they are intentionally maintained outside upstream
- **separate local notes/config from product behavior**

### 5. Clean up specs as part of the same workflow

Apply the spec state rules above:

- Keep only canonical active specs in `specs/`
- Move replaced specs to `specs/obsoleted/`
- Move inactive but historically useful specs to `specs/archived/`
- Delete temporary rebase-only specs once their intent is merged back into canonical implementation work

### 6. Verify against specs, not just compilation

After rewriting, verify each canonical spec explicitly.

Minimum required checks:

```bash
npm run check
```

If dependencies are not installed in the isolated worktree, install from the lockfile first with the repository's normal bootstrap command (for this repo, `npm ci`). If package manifests changed, regenerate or validate the lockfile before running checks.

If you add or modify a test file, run that test file too.

Then produce a short satisfaction report:

```markdown
- Spec 01: satisfied
- Spec 08: satisfied after startup wiring fix
- Spec 10: satisfied with upstream lifecycle event adaptation
- Fork-only extras preserved: packages/coding-agent-cljs
```

### 7. Final output to the user

Always report these things:

1. **Upstream target** — remote/branch and commit SHA used
2. **Commit plan or final commit list**
3. **Backup branch** — name and old tip, if the active branch was replaced
4. **Spec disposition** — kept / obsoleted / archived / deleted
5. **Spec satisfaction** — whether each canonical spec is satisfied
6. **Dependency alignment summary** — fork-only package version alignment and lockfile handling
7. **Dirty/untracked disposition** — what was preserved, restored by approval, or left untouched
8. **Verification summary** — what was run and the result
9. **Temporary worktree cleanup** — removed, retained, or blocked with reason

### 8. Final commit hygiene

When finalizing the rewritten branch:

- stage only intended files explicitly
- never use `git add .` or `git add -A`
- keep local notes/config/docs separate from feature/spec commits
- include `Spec: specs/...` in commit messages for spec-backed commits
- do not push unless the user explicitly asks

Safe promotion pattern after verification:

```bash
# in the main worktree, only after preserving dirty work, creating a backup branch,
# and removing or detaching the temporary worktree that held <rewritten-branch>
git branch -m <branch> <backup-branch>
git switch <rewritten-branch>
git branch -m <rewritten-branch> <branch>
git branch --set-upstream-to=origin/<branch> <branch>
```

If any promotion step would overwrite dirty files or disturb another worktree, stop and ask.

## Failure handling

Common cases:

- **No `upstream` remote**: stop and ask whether a different remote should be treated as upstream.
- **No active `specs/`**: do not proceed with a spec-preserving rewrite until the user confirms the intended canonical behavior set.
- **Dirty worktree**: create an isolated worktree for rewrite work and avoid sweeping unrelated files into history surgery.
- **Dirty tracked file needs replacement**: ask for explicit file-level approval before restoring or overwriting it.
- **Untracked files**: leave untouched unless the user explicitly asks for cleanup.
- **Fork-only misclassification risk**: verify package existence upstream before deciding to skip a canonical spec.
- **Lockfile conflicts become unclear**: abandon the conflicted attempt and rebuild from a clean upstream branch.
- **Push requested after history rewrite**: remind yourself this is rewritten history; never force-push unless the user explicitly confirms the exact remote/branch and force mode.

## Examples

### Example 1: upstream rebase broke an active feature
**User says:** "Rebase us onto upstream/main and make sure our local session-mode behavior still matches specs."
**Actions:**
1. Inventory commits and specs
2. Map durable behavior to canonical specs
3. Rebuild history in an isolated worktree
4. Fold runtime compatibility fixes back into the spec-backed feature commit
5. Verify spec satisfaction and `npm run check`
**Result:** Clean branch history on top of current upstream, with canonical specs still satisfied

### Example 2: clean up fork history after upstream sync
**User says:** "We already rebased, but the history is messy. Separate fork-only packages, obsolete old specs, and keep only the real feature commits."
**Actions:**
1. Classify changes into spec-backed, compatibility, fork-only, and transient buckets
2. Rewrite history so compatibility fixes are squashed into their owning feature commits
3. Keep fork-only packages in separate commits
4. Move or delete non-canonical spec files
**Result:** Minimal maintainable history aligned to active specs instead of checkpoint commits

### Example 3: lockfile conflicts during rebase
**User says:** "Rebase onto latest upstream, and keep our fork-only package."
**Actions:**
1. Fetch upstream and inventory dirty files
2. Attempt isolated rebase
3. If lockfile conflicts become nested or ambiguous, create a clean branch from `upstream/main`
4. Cherry-pick durable commits, align fork-only dependencies to upstream versions, regenerate lockfile
5. Verify and promote with a backup branch
**Result:** Clean branch on current upstream without carrying accidental lockfile conflict artifacts

## Practical Heuristics

- If a change only exists because upstream renamed APIs, it is a compatibility fix, not a new feature.
- If a commit introduced a temporary implementation later replaced by a standalone package, drop the old commit after moving any still-needed behavior elsewhere.
- If a package does not exist upstream and the user wants to keep it, keep it as a separate fork-local commit.
- If a fork-only package depends on upstream-owned packages, treat version bumps for those dependencies as part of keeping the fork-only package compatible with current upstream.
- Do not edit dependency versions inside upstream-owned packages just to satisfy fork-only package alignment.
- If a file looks like `handoff.md`, `issue-body.md`, or temporary planning notes, do not mix it into feature/spec commits.

## References

For reusable checklists and templates, load:

- `references/upstream-sync-checklist.md`
- `references/spec-disposition-rules.md`
- `references/final-report-template.md`

## Version History

- v1.2.0 (2026-04-27): Added fetch/upstream SHA reporting, dirty worktree preservation, clean-branch rebuild fallback, upstream dependency baseline handling, lockfile regeneration guidance, branch promotion safety, and expanded final reporting
- v1.1.0 (2026-04-09): Added fork-only package dependency version alignment rules for upstream syncs
- v1.0.0 (2026-04-09): Initial version
