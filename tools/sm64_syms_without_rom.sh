#!/usr/bin/env bash
# Gera a tabela de símbolos do Super Mario 64 (EUA) SEM precisar da ROM.
#
# A decompilação (n64decomp/sm64) só precisa da ROM para extrair gráficos e
# sons. Os segmentos de código do jogo (main, engine e goddard) são feitos só
# de código-fonte, então dá para compilá-los sem a ROM:
#
#   1. compila o código com NOEXTRACT=1 (sem extrair assets);
#   2. o goddard inclui 25 texturas da tela de título no próprio código; elas
#      viram imagens vazias com as mesmas dimensões, o que mantém o tamanho dos
#      dados sem conter nada do jogo;
#   3. liga um ELF só com main, engine, goddard e as dynlists do goddard;
#   4. gera a tabela com tools/gen_syms.py.
#
# A posição do goddard na ROM depende do tamanho dos assets comprimidos, que
# vêm antes dele. A tabela leva só o SHA-1 de um trecho do goddard, e o app o
# encontra na ROM do usuário (m64_locate).
#
# Requisitos: git, make, gcc/g++, python3, binutils-mips-linux-gnu.
#
#   tools/sm64_syms_without_rom.sh [pasta de trabalho]
#   -> generated/sm64.us.syms.toml
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-$ROOT/build/sm64-decomp}"
DECOMP_COMMIT=9921382a68bb0c865e5e45eb594d9c64db59b1af
VERSION=us

mkdir -p "$WORK"
if [[ ! -d "$WORK/sm64/.git" ]]; then
    git clone https://github.com/n64decomp/sm64 "$WORK/sm64"
fi
cd "$WORK/sm64"
git checkout -q "$DECOMP_COMMIT"

# 2. Texturas do goddard: imagens vazias com as dimensões de assets.json.
python3 - <<'PY'
import json, os, struct, zlib
assets = json.load(open("assets.json"))
def png(path, w, h):
    raw = b"".join(b"\0" + b"\0" * (w * 4) for _ in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
for name, info in assets.items():
    if name.startswith("textures/intro_raw/"):
        png(name, info[0], info[1])
PY

# 1. Código (os erros de assets que não entram nos segmentos de código são esperados).
make VERSION=$VERSION NOEXTRACT=1 COMPARE=0 -k -j"$(nproc)" >"$WORK/make.log" 2>&1 || true
make VERSION=$VERSION NOEXTRACT=1 COMPARE=0 -j"$(nproc)" build/$VERSION/libgoddard.a >>"$WORK/make.log" 2>&1
for f in build/$VERSION/libultra.a build/$VERSION/libgcc.a build/$VERSION/libgoddard.a build/$VERSION/sm64.ld; do
    [[ -f "$f" ]] || { echo "faltou $f (veja $WORK/make.log)"; exit 1; }
done

# 3. ELF só com os segmentos de código. A ROM começa o código em 0x1000.
B=build/$VERSION
python3 - "$B/sm64.ld" "$B/code_only.ld" <<'PY'
import sys
lines = open(sys.argv[1]).read().split("\n")
def find(text):
    return next(i for i, l in enumerate(lines) if text in l)
def segment(name, noload):
    start = find(f"_{name}SegmentStart =")
    end = find(f"_{name}SegmentNoloadEnd =" if noload else f"__romPos += SIZEOF(.{name})")
    return lines[start:end + 1]
header = lines[:find("__romPos = 0;")]
pre_main = lines[find("_bootSegmentRomEnd") + 1:find("_mainSegmentStart =")]  # zbuffer e buffers
out = (header + ["    __romPos = 0x1000;"] + pre_main
       + segment("main", True) + segment("engine", True)
       + lines[find("_framebuffersSegmentNoloadStart"):find("_framebuffersSegmentNoloadEnd") + 1]
       + segment("goddard", True) + segment("gd_dynlists", False)
       + ["    .mdebug : { *(.mdebug) }", "    /DISCARD/ :", "    {", "        *(*);", "    }", "}"])
open(sys.argv[2], "w").write("\n".join(out) + "\n")
PY
mapfile -t OBJS < <(find "$B/src" "$B/asm" -name "*.o" | grep -v "ipl3_font\|src/goddard/" | sort)
mips-linux-gnu-ld -N --emit-relocs -L "$B" -T undefined_syms.txt -T "$B/code_only.ld" \
    -Map "$B/code_only.map" --no-check-sections --unresolved-symbols=ignore-all \
    -o "$B/code_only.elf" "${OBJS[@]}" -lultra -lgoddard -lgcc 2>"$WORK/ld.log" || true
[[ -f "$B/code_only.elf" ]] || { echo "falha ao ligar o ELF (veja $WORK/ld.log)"; exit 1; }

# 4. Tabela de símbolos.
python3 "$ROOT/tools/gen_syms.py" --elf "$B/code_only.elf" --version $VERSION \
    --use-mdebug --bss-suffix .noload --size-unsized \
    --rom-base 0x1000 --locate .goddard --entrypoint 0x80246000
