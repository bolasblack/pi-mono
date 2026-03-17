---
name: parinfer-cljs
description: "Auto-fix Clojure/ClojureScript bracket mismatches using parinfer indentMode. Use when writing or editing .clj, .cljs, or .cljc files, working on ClojureScript code, or after any file modification to Clojure source. Do NOT use for non-Clojure files."
---

# parinfer-cljs

Fix bracket/parenthesis mismatches in Clojure and ClojureScript files using parinfer's indentMode, which infers correct bracket structure from code indentation.

## Instructions

### After Every Write or Edit to a .clj/.cljs/.cljc File

Run parinfer on the modified file:

```bash
node <skill-dir>/scripts/parinfer-fix.mjs <file-path>
```

Where `<skill-dir>` is the directory containing this SKILL.md.

### Batch Mode

Multiple files at once:

```bash
node <skill-dir>/scripts/parinfer-fix.mjs file1.cljs file2.cljs file3.cljc
```

All files in a directory:

```bash
find packages/kernel-cljs/src -name "*.cljs" | xargs node <skill-dir>/scripts/parinfer-fix.mjs
```

## Output

| Output | Meaning |
|--------|---------|
| `OK <file>` | Brackets already correct, no changes |
| `FIXED <file>` | Brackets were wrong, file updated in place |
| `FAIL <file>` | Could not fix — manual intervention needed |
| `SKIP <file>` | Not a .clj/.cljs/.cljc file, ignored |

## How It Works

parinfer `indentMode` trusts your code's indentation and derives the correct bracket structure from it. AI-generated Clojure is typically well-indented even when brackets are wrong — exactly the input indentMode is optimized for.

## Important

- Run parinfer AFTER writing/editing, not before
- This does not replace `clj-kondo` — run kondo separately for semantic checks
- Non-.clj/.cljs/.cljc files are automatically skipped

## Examples

### Example 1: Fix after writing a new file
**User says:** "Create a new CLJS namespace for session management"
**Actions:**
1. Write the .cljs file
2. Run `node <skill-dir>/scripts/parinfer-fix.mjs packages/kernel-cljs/src/pi/kernel/session_mgmt.cljs`
**Result:** `OK` or `FIXED` — brackets guaranteed balanced

### Example 2: Fix after editing existing code
**User says:** "Add error handling to the provider interceptor"
**Actions:**
1. Edit the .cljs file
2. Run `node <skill-dir>/scripts/parinfer-fix.mjs packages/kernel-cljs/src/pi/kernel/interceptors/provider.cljs`
**Result:** Any bracket mismatches from the edit are auto-corrected

### Example 3: Batch verify after large refactor
**Actions:**
1. Complete refactoring across multiple files
2. Run `find packages/kernel-cljs/src -name "*.cljs" | xargs node <skill-dir>/scripts/parinfer-fix.mjs`
**Result:** All files checked and fixed in one pass

## Troubleshooting

**Error:** `FAIL <file>: unclosed-paren at line N`
**Cause:** Indentation is also broken, so parinfer can't infer correct brackets.
**Solution:** Fix the indentation at the reported line, then re-run.

**Error:** `FAIL <file>: unmatched-close-paren at line N`
**Cause:** Extra closing bracket with no matching opener.
**Solution:** Remove the extra bracket at the reported line, then re-run.

## Version History
- v1.0.0 (2026-03-16): Initial version — parinfer indentMode with forceBalance
