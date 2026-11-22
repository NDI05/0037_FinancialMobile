#!/usr/bin/env bash
# Reproducible Go entry point. The toolchain lives under the ignored .toolchain/ directory.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOROOT_DIR="${PAM_GOROOT:-$ROOT/.toolchain/go}"
if [[ ! -x "$GOROOT_DIR/bin/go" ]]; then
  echo "Go toolchain not found at $GOROOT_DIR/bin/go" >&2
  echo "Install go1.27.4 darwin-arm64 under .toolchain/go (see docs/07_FREE_STACK.md)." >&2
  exit 1
fi
export GOROOT="$GOROOT_DIR"
export PATH="$GOROOT/bin:$PATH"
export GOCACHE="${PAM_GOCACHE:-$ROOT/.toolchain/go-cache}"
export GOMODCACHE="${PAM_GOMODCACHE:-$ROOT/.toolchain/go-mod}"
exec go "$@"
