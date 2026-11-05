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
# The game turns a mod off when one tick passes its fuel (50 million
# instructions on Horizon's Android ports): run the component through long
# tornadoes at every quality and fail the build past 60% of that.
for q in low medium high; do
  for rate in 1 10; do
    line="$(cd "$m/tools/fuelcheck" && cargo run --release --quiet -- "$m/target/mod.wasm" "$q" "here 5" "$rate" 2>&1 | tail -1)"
    echo "fuel check (rate $rate): $line"
    pct="$(echo "$line" | sed -n 's/.*fuel (\([0-9]*\)% of.*/\1/p')"
    if [ -z "$pct" ] || [ "$pct" -gt 60 ]; then
      echo "fuel check failed" >&2
      exit 1
    fi
  done
done
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
