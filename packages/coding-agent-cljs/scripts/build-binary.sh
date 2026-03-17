#!/bin/bash
set -euo pipefail
echo "Step 1: shadow-cljs release..."
npx shadow-cljs release app
echo "Step 2: bun compile..."
bun build --compile out/coding-agent.js --outfile out/coding-agent-bin
echo "Done: out/coding-agent-bin"
