# Final Report Template

## Upstream Target

- Remote/branch: `upstream/main`
- Commit: `<sha>` `<message>`

## Commit Summary

- `<commit>` `<message>`
- `<commit>` `<message>`

## Backup / Branch Promotion

- Backup branch: `<branch-before-upstream-timestamp>` at `<old-sha>`
- Active branch after promotion: `<branch>` at `<new-sha>`
- Pushed: yes / no

## Spec Disposition

### Kept
- `specs/...`

### Obsoleted
- `specs/obsoleted/...`

### Archived
- `specs/archived/...`

### Deleted
- `specs/...`

## Spec Satisfaction

- `specs/...`: satisfied
- `specs/...`: satisfied with upstream compatibility adjustments
- `specs/...`: not yet satisfied

## Fork-Only / Dependency Alignment

- Fork-only packages preserved: `packages/...`
- Dependency alignment: `<package>` follows upstream `<dependency>` at `<version/range>`
- Lockfile handling: regenerated / updated / unchanged
- Dependency safety gate: not needed because following upstream baseline / run for new dependency / user-approved override

## Dirty / Untracked Disposition

- Preserved dirty tracked files: `path` / none
- Restored or overwritten with explicit approval: `path` / none
- Left untracked files untouched: `path` / none

## Verification

- Ran: `npm ci`
- Ran: `npm run check`
- Ran: `<specific test command>`
- Result: pass / fail

## Temporary Worktrees

- Removed: `<path>`
- Retained: `<path>` because `<reason>`
