#!/usr/bin/env bash
# Type-checks and tests Tempest against devstub/horizon-client, a stand-in
# for the part of Horizon's mod API it uses, without building the game.
#   tools/check.sh            # cargo test
#   tools/check.sh clippy     # cargo clippy
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="${TEMPEST_CHECK_DIR:-${TMPDIR:-/tmp}/tempest-check}"
rm -rf "$work/api"
mkdir -p "$work"
cp -r "$here/api" "$work/api"
sed -i.bak "s|^horizon-client = .*|horizon-client = { path = \"$here/devstub/horizon-client\" }|" "$work/api/Cargo.toml"
cd "$work/api"
case "${1:-test}" in
  clippy) cargo clippy --all-targets -- -D warnings ;;
  *) cargo test ;;
esac
