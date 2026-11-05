# Packs this bundle into a .hnmod (Horizon docs/api/mod-files.md):
#   python3 tools/pack.py . dist/tempest-<version>.hnmod <horizon commit> <horizon-mixins commit>
import json, os, sys, zipfile
src, out, rev, mixins = sys.argv[1:5]
import re
text = open(os.path.join(src, "horizon-mixin.toml")).read()
mod_id = re.search(r'^id = "(.*)"', text, re.M).group(1)
version = re.search(r'^version = "(.*)"', text, re.M).group(1)
manifest = {"schema": 1, "id": mod_id, "version": version, "game": "26.3", "horizon": rev, "mixins": mixins}
files = ["horizon-mixin.toml", "README.md", "LICENSE", ".gitignore", "api/Cargo.toml"]
for root, _, names in os.walk(os.path.join(src, "api", "src")):
    for n in names:
        if n.endswith(".rs"):
            files.append(os.path.relpath(os.path.join(root, n), src).replace(os.sep, "/"))
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("horizon-native.json", json.dumps(manifest, indent=2) + "\n")
    for f in sorted(files):
        z.write(os.path.join(src, f), f"mixins/{mod_id}/{f}")
print(out, len(files) + 1, "entries")
