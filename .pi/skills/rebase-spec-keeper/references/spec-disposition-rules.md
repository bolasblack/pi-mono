# Spec Disposition Rules

## Keep in `specs/`

Use for specs that still define current intended behavior.

Examples:
- active user-facing features
- active fork-maintained workflows
- still-required CLI or runtime behavior

## Move to `specs/obsoleted/`

Use when a newer spec replaced the old one, but the old document is still useful for lineage.

Examples:
- old inline wrapper spec replaced by standalone package spec
- old behavior superseded by a new canonical design

## Move to `specs/archived/`

Use when the work is no longer an active implementation target, but is worth keeping for historical reference.

Examples:
- experiments that landed differently
- old migration notes that are no longer current

## Delete

Delete specs that only existed to manage a single cleanup/rebase pass.

Examples:
- temporary upstream adaptation notes
- checkpoint implementation notes duplicated by canonical specs
- notes whose only purpose was explaining one conflict-resolution strategy

## Compatibility Follow-Ups

Compatibility notes are not durable specs by themselves. Before finalizing a rewritten stack:

- merge lasting behavior back into the owning canonical spec or implementation commit
- move historically useful compatibility notes to `specs/archived/`
- delete one-off rebase notes that no longer describe an active contract

## Rule of thumb

If the file describes a lasting product or workflow contract, keep it.
If it only describes how one rebase was completed, delete or archive it.
