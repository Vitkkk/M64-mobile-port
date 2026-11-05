#!/usr/bin/env bash
# Type-checks Tempest against a real Horizon source tree, the way the
# launcher builds it: as a member of Horizon's workspace, on its Cargo.lock.
#   tools/check-horizon.sh <path to a horizon clone> [check|clippy]
# Audio and controllers are left out (they need libasound/libudev and do not
# touch the mod API). Changes the clone's Cargo.toml: use a scratch clone.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
horizon="$(cd "${1:?usage: $0 <horizon clone> [check|clippy]}" && pwd)"
dest="$horizon/native-crates/tempest/api"
rm -rf "$dest"
mkdir -p "$(dirname "$dest")"
cp -r "$here/api" "$dest"
sed -i.bak 's|^horizon-client = .*|horizon-client = { path = "../../../crates/horizon-client", default-features = false }|' "$dest/Cargo.toml"
grep -q '"native-crates/tempest/api"' "$horizon/Cargo.toml" ||
  sed -i.bak 's|^members = \[|members = [\n    "native-crates/tempest/api",|' "$horizon/Cargo.toml"
cd "$horizon"
cargo "${2:-check}" -p mod-tempest --lib
