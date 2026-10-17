#!/usr/bin/env bash
# Teste de ponta a ponta do runtime com um "jogo" sintético:
#   C -> ELF MIPS -> ROM -> gen_syms.py -> m64run (AOT + N64ModernRuntime)
# Exercita boot, threads, filas de mensagens e eventos de VI do runtime,
# com as funções da libultra trocadas pelas implementações nativas.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/tools/tests"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

M64RUN="${M64RUN:-$ROOT/native/build/m64run}"
CFLAGS=(--target=mips-unknown-elf -march=mips3 -mabi=32 -mno-abicalls -fno-pic -G0 -O2
        -ffreestanding -fno-builtin)

clang "${CFLAGS[@]}" -c "$HERE/synthetic_game.c" -o "$WORK/game.o"
clang "${CFLAGS[@]}" -c "$HERE/synthetic_ultra.c" -o "$WORK/ultra.o"
ld.lld -T "$HERE/synthetic_game.ld" "$WORK/game.o" "$WORK/ultra.o" -o "$WORK/game.elf"
llvm-objcopy -O binary "$WORK/game.elf" "$WORK/game.z64"
# O boot copia 1 MiB da ROM a partir de 0x1000.
truncate -s 2M "$WORK/game.z64"

python3 "$ROOT/tools/gen_syms.py" --elf "$WORK/game.elf" --version us --out "$WORK"

out="$("$M64RUN" --any-rom --entry 0x80000400 --exit-on m64_test_done --print-on m64_test_log --seconds 10 \
    --data "$WORK/data" "$WORK/game.z64" "$WORK/sm64.us.syms.toml" 2>&1)" || { echo "$out"; echo "FALHOU: m64run saiu com erro"; exit 1; }
echo "$out"
if ! grep -qx "m64_test_done(63)" <<<"$out"; then
    echo "FALHOU: esperado m64_test_done(63)"
    exit 1
fi
echo "runtime sintético OK"
