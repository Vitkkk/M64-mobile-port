#!/usr/bin/env bash
# Builds dist/tempest-mobile-<version>.hmod: Tempest for Horizon's Android
# ports, a sandboxed WebAssembly client mod (horizon:mods 0.1).
# Needs: rustup target add wasm32-unknown-unknown, and python3 for the zip.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
m="$here/mobile"
(cd "$m" && cargo test --quiet && cargo build --release --target wasm32-unknown-unknown)
(cd "$m/tools/componentize" && cargo run --release --quiet -- \
  "$m/target/wasm32-unknown-unknown/release/tempest_mobile.wasm" "$m/target/mod.wasm")
version="$(sed -n 's/^version = "\(.*\)"/\1/p' "$m/horizon-mod.toml")"
mkdir -p "$here/dist"
out="$here/dist/tempest-mobile-$version.hmod"
python3 -I - "$m/horizon-mod.toml" "$m/target/mod.wasm" "$out" <<'PY'
import sys, zipfile
manifest, wasm, out = sys.argv[1:4]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(manifest, "horizon-mod.toml")
    z.write(wasm, "mod.wasm")
print(out)
PY
