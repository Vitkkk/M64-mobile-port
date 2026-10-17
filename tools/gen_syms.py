#!/usr/bin/env python3
"""Gera a tabela de símbolos (sm64.<versão>.syms.toml) usada pelo recompilador AOT.

A tabela contém apenas metadados: nome, endereço e tamanho de cada função e
os limites das seções. Nenhuma instrução ou asset do jogo vai para ela; o
código de verdade é lido da ROM do usuário no aparelho.

Ela é extraída do ELF produzido pela decompilação (github.com/n64decomp/sm64)
compilada por você, a partir da sua própria ROM:

    git clone https://github.com/n64decomp/sm64
    cp /caminho/da/sua/rom.z64 sm64/baserom.us.z64
    make -C sm64 VERSION=us
    python3 tools/gen_syms.py --elf sm64/build/us/sm64.us.elf --version us

O resultado vai para generated/ (ignorado pelo git). Importe-o no app pelo
botão "Importar tabela de símbolos", ou copie para
android/app/src/main/assets/syms/ antes de compilar um APK de uso pessoal.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
N64RECOMP_SRC = ROOT / "native" / "third_party" / "N64ModernRuntime" / "N64Recomp"
TOOLS_BUILD = ROOT / "native" / "build-tools"
VERSIONS = ("us", "jp", "eu", "sh", "cn")


def build_n64recomp() -> Path:
    exe = TOOLS_BUILD / "N64Recomp"
    if exe.exists():
        return exe
    if not (N64RECOMP_SRC / "CMakeLists.txt").exists():
        sys.exit("Submódulo ausente. Rode: git submodule update --init --recursive")
    print("Compilando o N64Recomp (só na primeira vez)...")
    subprocess.run(["cmake", "-S", str(N64RECOMP_SRC), "-B", str(TOOLS_BUILD),
                    "-DCMAKE_BUILD_TYPE=Release"], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["cmake", "--build", str(TOOLS_BUILD), "--target", "N64RecompCLI",
                    "--parallel"], check=True)
    return exe


def dump_context(n64recomp: Path, elf: Path) -> str:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        config = tmp_path / "dump.config.toml"
        config.write_text(
            "[input]\n"
            f"elf_path = {str(elf.resolve())!r}\n"
            "output_func_path = \"unused\"\n"
        )
        result = subprocess.run([str(n64recomp), str(config), "--dump-context"], cwd=tmp_path,
                                capture_output=True, text=True)
        dump = tmp_path / "dump.toml"
        if result.returncode != 0 or not dump.exists():
            sys.stderr.write(result.stdout + result.stderr)
            sys.exit("O N64Recomp não conseguiu ler o ELF.")
        return dump.read_text()


def summarize(syms: str) -> tuple[int, int]:
    sections = len(re.findall(r"^\[\[section\]\]", syms, re.MULTILINE))
    functions = len(re.findall(r"^\s*\{ name = ", syms, re.MULTILINE))
    return sections, functions


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--elf", required=True, type=Path, help="ELF gerado pela decompilação")
    parser.add_argument("--version", required=True, choices=VERSIONS, help="versão da ROM")
    parser.add_argument("--out", type=Path, default=ROOT / "generated", help="pasta de saída")
    parser.add_argument("--n64recomp", type=Path, help="executável do N64Recomp já compilado")
    args = parser.parse_args()

    if not args.elf.is_file():
        sys.exit(f"ELF não encontrado: {args.elf}")

    n64recomp = args.n64recomp or build_n64recomp()
    syms = dump_context(n64recomp, args.elf)
    sections, functions = summarize(syms)
    if functions == 0:
        sys.exit("Nenhuma função encontrada no ELF.")

    args.out.mkdir(parents=True, exist_ok=True)
    out_file = args.out / f"sm64.{args.version}.syms.toml"
    header = (f"# Tabela de símbolos de Super Mario 64 ({args.version}) para o recompilador AOT.\n"
              "# Apenas metadados (nomes, endereços e tamanhos). Gerada localmente; não versionar.\n")
    out_file.write_text(header + syms)
    print(f"{out_file}: {functions} funções em {sections} seções")


if __name__ == "__main__":
    main()
