# Upstream Sync Checklist

## Before rewrite

- Check `git status --short --branch`
- Run `git fetch upstream --prune`
- Record `git rev-parse --short upstream/main`
- List fork commits vs `upstream/main`
- List all specs under `specs/`
- Identify dirty tracked files and untracked files that must not be swept into history surgery
- Stop if there is no `upstream` remote or `git fetch upstream --prune` fails

## Classification

For each change, decide whether it is:

- spec-backed feature
- compatibility patch
- fork-local package/workflow
- transient local note/config/noise

Map every durable change to:

- an active canonical spec
- a verified fork-only package or workflow
- a user-approved local/config chore

## Rewrite target

Aim for:

- one durable commit per canonical feature/spec
- compatibility patches squashed into the related feature commit
- fork-local packages isolated
- local notes/config split off or dropped
- no temporary rebase notes in the active spec set

## Worktree safety

- Do not perform history surgery directly in a dirty main worktree
- Create an isolated rewrite worktree
- Preserve tracked dirty files unless the user explicitly approves overwriting the exact path
- Leave untracked files untouched unless the user explicitly asks for cleanup
- Create a backup branch before replacing the active branch

## Clean-branch fallback

Use a clean branch from `upstream/main` instead of continuing a noisy rebase when:

- lockfile conflicts are nested or ambiguous
- autosquash/fixup commits conflict in generated files
- transient commits are easier to drop by omission
- the clean final commit list is easier to verify than the conflicted rebase state

Pattern:

```bash
git worktree add -b <branch>-upstream-clean <tmpdir> upstream/main
git cherry-pick <durable-commit>...
```

## Dependency and lockfile alignment

- Verify fork-only packages are absent from upstream before classifying them as fork-only
- If a fork-only package depends on upstream-owned packages, compare against current `upstream/main`
- Align fork-only package dependency ranges to upstream's current package versions when upstream bumped them
- Treat upstream-adopted dependency versions as the approved baseline
- Run dependency-safety checks only for new third-party dependencies not already present in upstream or explicitly approved by the user
- Sync lockfiles after manifest changes; prefer deterministic package-manager regeneration over manual large lockfile edits

## Spec cleanup

- keep canonical active specs in `specs/`
- move replaced ones to `specs/obsoleted/`
- move inactive historical ones to `specs/archived/`
- delete temporary rebase-only specs

## Verification

- run `npm run check`
- run any modified test files
- confirm each canonical spec is satisfied on current upstream
- confirm fork-only packages are preserved and dependency-aligned
- confirm no conflict markers remain in touched generated/lock files

## Promotion and cleanup

- Promote only after verification passes
- Preserve old branch tip in a backup branch
- Rename or switch branches without overwriting dirty files
- Set upstream tracking if the active branch is replaced
- Remove temporary worktrees when no longer needed
- Do not push unless the user explicitly asks
