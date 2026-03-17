#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo ">>> Installing npm dependencies..."
npm install

echo ">>> Compiling ClojureScript with shadow-cljs (release :poc)..."
npx shadow-cljs release :poc

echo ">>> Verifying output JS..."
if [ ! -f out/poc.js ]; then
  echo "ERROR: out/poc.js not found"
  exit 1
fi

echo ">>> Building binary with bun..."
/root/.bun/bin/bun build --compile out/poc.js --outfile out/poc-binary

echo ">>> Verifying binary..."
if [ ! -f out/poc-binary ]; then
  echo "ERROR: out/poc-binary not found"
  exit 1
fi

chmod +x out/poc-binary

echo ">>> Running binary..."
./out/poc-binary

echo ">>> Build complete!"
