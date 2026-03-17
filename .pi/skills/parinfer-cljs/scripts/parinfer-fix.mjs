#!/usr/bin/env node
// Fix bracket mismatches in .clj/.cljs/.cljc files using parinfer indentMode
import { readFileSync, writeFileSync } from "fs";
import parinfer from "parinfer";

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error("Usage: parinfer-fix.mjs <file1> [file2] ...");
  process.exit(1);
}

let hasErrors = false;

for (const file of files) {
  if (!/\.clj[sc]?$/.test(file)) {
    console.log(`SKIP ${file} (not a clj/cljs/cljc file)`);
    continue;
  }

  const original = readFileSync(file, "utf-8");
  const result = parinfer.indentMode(original, { forceBalance: true });

  if (!result.success) {
    console.error(`FAIL ${file}: ${result.error.name} at line ${result.error.lineNo}`);
    hasErrors = true;
    continue;
  }

  if (result.text !== original) {
    writeFileSync(file, result.text, "utf-8");
    console.log(`FIXED ${file}`);
  } else {
    console.log(`OK ${file}`);
  }
}

process.exit(hasErrors ? 1 : 0);
