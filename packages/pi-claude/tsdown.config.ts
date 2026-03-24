import { defineConfig } from "tsdown";

export default defineConfig({
  entry: [
    "src/index.ts",
    "src/extension.ts",
    "src/ui-helpers.ts",
    "src/claude-helpers.ts",
  ],
  format: "esm",
  dts: true,
  outDir: "dist",
  deps: {
    // Only bundle our own code; treat everything else as external
    onlyBundle: [],
  },
});
