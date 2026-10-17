#!/usr/bin/env python3
"""Reescreve OpImageWrite com texel inteiro escalar para um vetor de 4.

O compilador de shaders do Adreno (visto no Adreno 750, driver 0x802fa01f)
recusa pipelines de computação que escrevem um inteiro escalar numa imagem
(`OpImageWrite img coord %uint`), embora isso seja SPIR-V válido; o DXC gera
esse padrão para RWBuffer<uint>/RWTexture2D<uint>. Escritas escalares de
float e de vetores passam.

Este passo troca o texel escalar `v` por `OpCompositeConstruct v4 v v v v`.
Para os formatos de um componente (R32ui etc.) só o primeiro é usado, então
o resultado é idêntico.

    spirv_vectorize_image_writes.py shader.spv   (reescreve o arquivo)
"""

import struct
import sys

OP_TYPE_INT = 21
OP_TYPE_VECTOR = 23
OP_FUNCTION = 54
OP_COMPOSITE_CONSTRUCT = 80
OP_IMAGE_WRITE = 99

# Instruções que não produzem um valor com tipo (palavra 1 = tipo,
# palavra 2 = id): depuração, anotações, declarações de modo, tipos,
# controle de fluxo e escritas.
NOT_TYPED_VALUES = {
    1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 14, 15, 16, 17,  # depuração e modo
    19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 322, 327,  # OpType*
    56, 62, 63, 64, 99,  # OpFunctionEnd, OpStore, OpCopyMemory*, OpImageWrite
    71, 72, 73, 74, 75, 332, 5632, 5633,  # anotações
    224, 225, 228,  # barreiras, OpAtomicStore
    246, 247, 248, 249, 250, 251, 252, 253, 254, 255,  # controle de fluxo, OpLabel
    317, 330, 331,  # OpNoLine, OpModuleProcessed, OpExecutionModeId
}


def instructions(words, start=5):
    i = start
    while i < len(words):
        count = words[i] >> 16
        if count == 0:
            raise ValueError("instrução SPIR-V com tamanho 0")
        yield i, words[i] & 0xFFFF, words[i:i + count]
        i += count


def vectorize(data: bytes) -> tuple[bytes, int]:
    if len(data) < 20 or data[:4] not in (b"\x03\x02\x23\x07", b"\x07\x23\x02\x03"):
        raise ValueError("não é um módulo SPIR-V")
    endian = "<" if data[:4] == b"\x03\x02\x23\x07" else ">"
    words = list(struct.unpack(f"{endian}{len(data) // 4}I", data))
    bound = words[3]

    int_types = set()
    vec4_of = {}       # tipo inteiro -> id do vetor de 4 desse tipo
    type_of = {}       # id de valor -> id do tipo
    first_function = None
    writes = []
    for pos, op, ins in instructions(words):
        if op == OP_TYPE_INT:
            int_types.add(ins[1])
        elif op == OP_TYPE_VECTOR and ins[3] == 4:
            vec4_of.setdefault(ins[2], ins[1])
        elif op == OP_FUNCTION and first_function is None:
            first_function = pos
        if op not in NOT_TYPED_VALUES and len(ins) >= 3:
            type_of.setdefault(ins[2], ins[1])
        if op == OP_IMAGE_WRITE:
            writes.append((pos, ins[3]))

    targets = [(pos, texel) for pos, texel in writes if type_of.get(texel) in int_types]
    if not targets:
        return data, 0

    # Vetores de 4 que ainda não existem entram no fim das declarações de tipos.
    new_types = []
    for pos, texel in targets:
        scalar = type_of[texel]
        if scalar not in vec4_of:
            vec4_of[scalar] = bound
            new_types += [(4 << 16) | OP_TYPE_VECTOR, bound, scalar, 4]
            bound += 1

    out = []
    target_positions = {pos: texel for pos, texel in targets}
    for pos, op, ins in instructions(words):
        if pos == first_function:
            out += new_types
        if pos in target_positions:
            texel = target_positions[pos]
            vec = bound
            bound += 1
            out += [(7 << 16) | OP_COMPOSITE_CONSTRUCT, vec4_of[type_of[texel]], vec, texel, texel, texel, texel]
            ins = list(ins)
            ins[3] = vec
        out += ins

    header = words[:5]
    header[3] = bound
    return struct.pack(f"{endian}{len(header) + len(out)}I", *(header + out)), len(targets)


def main() -> None:
    for path in sys.argv[1:]:
        with open(path, "rb") as f:
            data = f.read()
        new_data, count = vectorize(data)
        if count:
            with open(path, "wb") as f:
                f.write(new_data)


if __name__ == "__main__":
    main()
