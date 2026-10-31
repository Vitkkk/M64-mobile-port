#!/usr/bin/env bash
# Applies PPSS55's patches (ppss55/patches/*.patch) to the pinned KytyPS5-Android core in
# ppss55/core. Idempotent: an already applied patch is skipped.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
for p in "$here"/patches/*.patch; do
    if git -C "$here/core" apply --reverse --check "$p" 2>/dev/null; then
        echo "já aplicado: $(basename "$p")"
    else
        git -C "$here/core" apply "$p"
        echo "aplicado:    $(basename "$p")"
    fi
done
