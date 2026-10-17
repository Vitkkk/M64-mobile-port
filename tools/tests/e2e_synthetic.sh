#!/usr/bin/env bash
# Teste de ponta a ponta com um programa MIPS sintético:
#   C -> ELF MIPS (clang + lld) -> ROM sintética (objcopy) -> gen_syms.py -> m64aot (AOT + execução)
# É o mesmo caminho usado com o ELF da decompilação de SM64, sem precisar de ROM.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/tools/tests"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

M64AOT="${M64AOT:-$ROOT/native/build/m64aot}"

clang --target=mips-unknown-elf -march=mips3 -mabi=32 -mno-abicalls -fno-pic -G0 -O2 \
    -ffreestanding -fno-builtin -c "$HERE/synthetic.c" -o "$WORK/synthetic.o"
ld.lld -T "$HERE/synthetic.ld" "$WORK/synthetic.o" -o "$WORK/synthetic.elf"

# Como na decompilação, a ROM é o binário cru do ELF (alinhado em 4 KiB).
llvm-objcopy -O binary "$WORK/synthetic.elf" "$WORK/synthetic.z64"
truncate -s 16K "$WORK/synthetic.z64"

python3 "$ROOT/tools/gen_syms.py" --elf "$WORK/synthetic.elf" --version us --out "$WORK"

# Carrega .text+.data na RAM, como o código de boot faria (a tabela `ops` mora em .data).
LOAD_ARG="0x1000:0x80000400:0x400"

check() {
    local fn="$1" arg="$2" expected="$3"
    local out
    out="$("$M64AOT" --any-rom --load "$LOAD_ARG" --call "$fn" "$arg" "$WORK/synthetic.z64" "$WORK/sm64.us.syms.toml" | tail -1)"
    if [[ "$out" != "$fn($arg) = $expected" ]]; then
        echo "FALHOU: esperado '$fn($arg) = $expected', obtido '$out'"
        exit 1
    fi
    echo "ok: $out"
}

check add5 37 42
check fib 10 55
check entry 10 70    # fib(10) + add5(10), via ponteiro
check entry 7 26     # fib(7) + fib(7), via ponteiro
echo "pipeline sintético OK"
