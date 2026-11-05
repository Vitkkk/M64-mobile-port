"""Empacota cinematico/ em cinematico-<versao>.hshader (zip determinístico, como o horizon-shader pack)."""
import pathlib, tomllib, zipfile

root = pathlib.Path(__file__).parent / "cinematico"
manifest = tomllib.loads((root / "horizon-shaders.toml").read_text(encoding="utf-8"))
out = root.parent / f"{manifest['id']}-{manifest['version']}.hshader"
files = sorted(p for p in root.rglob("*") if p.is_file() and (p.suffix in {".wgsl", ".toml", ".md", ".txt", ".png"}))
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for p in files:
        info = zipfile.ZipInfo(p.relative_to(root).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        z.writestr(info, p.read_bytes())
print(out, out.stat().st_size, "bytes,", len(files), "arquivos")
