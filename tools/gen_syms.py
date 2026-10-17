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
import hashlib
import re
import struct
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


def dump_context(n64recomp: Path, elf: Path, use_mdebug: bool = False, bss_suffix: str | None = None) -> str:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        config = tmp_path / "dump.config.toml"
        config.write_text(
            "[input]\n"
            f"elf_path = {str(elf.resolve())!r}\n"
            "output_func_path = \"unused\"\n"
            + ("use_mdebug = true\n" if use_mdebug else "")
            + (f"bss_section_suffix = {bss_suffix!r}\n" if bss_suffix else "")
        )
        result = subprocess.run([str(n64recomp), str(config), "--dump-context"], cwd=tmp_path,
                                capture_output=True, text=True)
        dump = tmp_path / "dump.toml"
        if result.returncode != 0 or not dump.exists():
            sys.stderr.write(result.stdout + result.stderr)
            sys.exit("O N64Recomp não conseguiu ler o ELF.")
        return dump.read_text()


class Elf32:
    """Leitor mínimo de ELF32 big-endian (MIPS): seções, símbolos e relocações."""

    def __init__(self, data: bytes):
        if data[:4] != b"\x7fELF" or data[4] != 1 or data[5] != 2:
            sys.exit("esperado um ELF32 big-endian")
        self.data = data
        shoff, = struct.unpack_from(">I", data, 0x20)
        shentsize, shnum, shstrndx = struct.unpack_from(">HHH", data, 0x2E)
        self.sections = []
        for i in range(shnum):
            name, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from(
                ">IIIIIIIIII", data, shoff + i * shentsize)
            self.sections.append(dict(name_off=name, type=typ, addr=addr, off=off, size=size,
                                      link=link, info=info, entsize=entsize))
        strtab = self.sections[shstrndx]
        for sec in self.sections:
            sec["name"] = self._str(strtab, sec["name_off"])

    def _str(self, strtab, off):
        start = strtab["off"] + off
        return self.data[start:self.data.index(b"\0", start)].decode()

    def section(self, name):
        for i, sec in enumerate(self.sections):
            if sec["name"] == name:
                return i, sec
        sys.exit(f"seção {name} não existe no ELF")

    def undefined_reloc_offsets(self, section_index):
        """Offsets (dentro da seção) de relocações contra símbolos indefinidos."""
        out = set()
        for sec in self.sections:
            if sec["type"] != 9 or sec["info"] != section_index:  # SHT_REL
                continue
            symtab = self.sections[sec["link"]]
            for r in range(sec["size"] // 8):
                r_offset, r_info = struct.unpack_from(">II", self.data, sec["off"] + r * 8)
                sym = r_info >> 8
                st_shndx, = struct.unpack_from(">H", self.data, symtab["off"] + sym * 16 + 14)
                if st_shndx == 0:  # SHN_UNDEF
                    out.add(r_offset - self.sections[section_index]["addr"])
        return out


    def symbols(self):
        """(nome, valor, tamanho, tipo, índice da seção) de todos os símbolos."""
        for sec in self.sections:
            if sec["type"] != 2:  # SHT_SYMTAB
                continue
            strtab = self.sections[sec["link"]]
            for i in range(sec["size"] // 16):
                name, value, size, info, other, shndx = struct.unpack_from(">IIIBBH", self.data, sec["off"] + i * 16)
                yield self._str(strtab, name), value, size, info & 0xF, shndx


def add_unsized_functions(syms: str, elf: Elf32) -> tuple[str, int]:
    """Dá tamanho às funções que o ELF não mede: as de assembly sem .size
    (parte da libultra do SM64) e as que a .mdebug traz com tamanho 0. O
    tamanho vai até o início da próxima função da seção."""
    lines = syms.split("\n")
    func_re = re.compile(r'^(\s*\{ name = "([^"]+)", vram = 0x)([0-9A-Fa-f]+)(, size = 0x)([0-9A-Fa-f]+)( \},)$')

    # Seções: limites, linha de "functions = [" e linhas das funções.
    sections = {}
    current = None
    for i, line in enumerate(lines):
        if m := re.match(r'name = "(.*)"$', line):
            current = m.group(1)
            sections[current] = {"vram": 0, "size": 0, "funcs_line": None, "funcs": {}}
        elif current and (m := re.match(r"vram = 0x([0-9A-Fa-f]+)$", line)):
            sections[current]["vram"] = int(m.group(1), 16)
        elif current and (m := re.match(r"size = 0x([0-9A-Fa-f]+)$", line)):
            sections[current]["size"] = int(m.group(1), 16)
        elif current and line.startswith("functions = ["):
            sections[current]["funcs_line"] = i
        elif current and (m := func_re.match(line)):
            sections[current]["funcs"][int(m.group(3), 16)] = (i, int(m.group(5), 16))

    changed = 0
    inserts = {}
    names = {sec["name"]: idx for idx, sec in enumerate(elf.sections)}
    for name, info in sections.items():
        if info["funcs_line"] is None:
            continue
        end = info["vram"] + info["size"]
        idx = names.get(name)
        elf_funcs = {}
        if idx is not None:
            for n, v, size, typ, shndx in elf.symbols():
                if shndx == idx and typ == 2 and info["vram"] <= v < end:
                    elf_funcs.setdefault(v, n)
        starts = sorted(set(info["funcs"]) | set(elf_funcs))

        def size_at(v):
            return next((x for x in starts if x > v), end) - v

        # Funções presentes com tamanho 0.
        for v, (line_no, size) in info["funcs"].items():
            if size == 0:
                m = func_re.match(lines[line_no])
                lines[line_no] = f"{m.group(1)}{m.group(3)}{m.group(4)}{size_at(v):X}{m.group(6)}"
                changed += 1
        # Funções que faltam (assembly sem .size).
        new = [f'    {{ name = "{n}", vram = 0x{v:08X}, size = 0x{size_at(v):X} }},'
               for v, n in sorted(elf_funcs.items()) if v not in info["funcs"]]
        if new:
            inserts[info["funcs_line"]] = new
            changed += len(new)

    out = []
    for i, line in enumerate(lines):
        out.append(line)
        out.extend(inserts.get(i, []))
    return "\n".join(out), changed


def add_entrypoint(syms: str, entry: int) -> str:
    """Inclui a função de entrada (rótulo de assembly sem tipo no ELF),
    medida até a próxima função da seção."""
    if re.search(rf"vram = 0x{entry:08X}, size", syms):
        return syms
    starts = sorted(int(v, 16) for v in re.findall(r"vram = 0x([0-9A-Fa-f]+), size", syms))
    nxt = next(x for x in starts if x > entry)
    sections = list(re.finditer(r'\[\[section\]\]\nname = "[^"]*"\nrom = [^\n]*\n(?:m64_locate[^\n]*\n)?vram = 0x([0-9A-Fa-f]+)\nsize = 0x([0-9A-Fa-f]+)', syms))
    for m in sections:
        base, size = int(m.group(1), 16), int(m.group(2), 16)
        if base <= entry < base + size:
            at = syms.index("functions = [\n", m.end()) + len("functions = [\n")
            line = f'    {{ name = "entry_point", vram = 0x{entry:08X}, size = 0x{nxt - entry:X} }},\n'
            return syms[:at] + line + syms[at:]
    sys.exit(f"nenhuma seção contém o ponto de entrada 0x{entry:08X}")


def locate_entry(elf: Elf32, section_name: str, window: int = 0x100) -> str:
    """m64_locate para a seção: SHA-1 de um trecho sem relocações indefinidas."""
    index, sec = elf.section(section_name)
    bad = elf.undefined_reloc_offsets(index)
    if not any(s["type"] == 9 and s["info"] == index for s in elf.sections):
        sys.exit(f"o ELF não tem relocações para {section_name}; ligue com --emit-relocs")
    for start in range(0, sec["size"] - window + 1, 0x10):
        if not any(start <= b < start + window for b in bad):
            chunk = elf.data[sec["off"] + start:sec["off"] + start + window]
            digest = hashlib.sha1(chunk).hexdigest()
            return f"m64_locate = {{ offset = 0x{start:X}, size = 0x{window:X}, sha1 = \"{digest}\" }}"
    sys.exit(f"nenhum trecho estável em {section_name}")


def apply_rom_base_and_locate(syms: str, elf_path: Path, rom_base: int, locate: list[str]) -> str:
    """Corrige `rom` das seções e marca as que o app deve localizar na ROM."""
    elf = Elf32(elf_path.read_bytes()) if locate else None
    out = []
    current = None
    for line in syms.split("\n"):
        m = re.match(r'name = "(.*)"$', line)
        if m:
            current = m.group(1)
        m = re.match(r"rom = 0x([0-9A-Fa-f]+)$", line)
        if m and current is not None:
            if current in locate:
                out.append("rom = 0x0")
                out.append(locate_entry(elf, current))
                continue
            line = f"rom = 0x{int(m.group(1), 16) + rom_base:08X}"
        out.append(line)
    return "\n".join(out)


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
    parser.add_argument("--use-mdebug", action="store_true",
                        help="lê as funções static da seção .mdebug (ELFs compilados com IDO)")
    parser.add_argument("--bss-suffix", help="sufixo das seções bss pareadas (ex.: .noload na decompilação do SM64)")
    parser.add_argument("--size-unsized", action="store_true",
                        help="inclui funções sem tamanho no ELF, medindo até a próxima função")
    parser.add_argument("--entrypoint", type=lambda v: int(v, 0),
                        help="vram da entrada do jogo, se ela não for uma função no ELF (SM64: 0x80246000)")
    parser.add_argument("--rom-base", type=lambda v: int(v, 0), default=0,
                        help="soma à posição na ROM (quando o ELF não começa no início da ROM)")
    parser.add_argument("--locate", action="append", default=[], metavar="SEÇÃO",
                        help="seção cuja posição na ROM o app descobre pelo hash (exige ELF com --emit-relocs)")
    args = parser.parse_args()

    if not args.elf.is_file():
        sys.exit(f"ELF não encontrado: {args.elf}")

    n64recomp = args.n64recomp or build_n64recomp()
    syms = dump_context(n64recomp, args.elf, args.use_mdebug, args.bss_suffix)
    if args.size_unsized:
        syms, added = add_unsized_functions(syms, Elf32(args.elf.read_bytes()))
        print(f"{added} funções sem tamanho medidas até a próxima")
    if args.rom_base or args.locate:
        syms = apply_rom_base_and_locate(syms, args.elf, args.rom_base, args.locate)
    if args.entrypoint is not None:
        syms = add_entrypoint(syms, args.entrypoint)
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
