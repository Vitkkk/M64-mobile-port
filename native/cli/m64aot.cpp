// m64aot: valida uma ROM e recompila todas as funções dela, no PC.
// Serve para testar o mesmo caminho que o app executa no celular.
//
//   m64aot [--any-rom] [--load rom:vram:tam]... [--call <função> <a0>] <rom> [syms.toml]
//
// --any-rom   aceita ROMs com hash desconhecido (para testes com ROMs sintéticas)
// --load      copia um trecho da ROM para a RAM antes do --call (o papel do
//             código de boot/DMA do jogo, que aqui não roda)
// --call      depois de recompilar, executa a função com a0 = <a0> e imprime v0
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "m64/aot.hpp"
#include "m64/rom.hpp"

static const char* order_name(m64::RomByteOrder order) {
    switch (order) {
        case m64::RomByteOrder::BigEndian: return ".z64 (big-endian)";
        case m64::RomByteOrder::ByteSwapped: return ".v64 (byte-swapped)";
        case m64::RomByteOrder::LittleEndian: return ".n64 (little-endian)";
        case m64::RomByteOrder::Unknown: break;
    }
    return "desconhecido";
}

int main(int argc, char** argv) {
    bool any_rom = false;
    const char* call_name = nullptr;
    int64_t call_arg = 0;
    struct Load {
        uint32_t rom, vram, size;
    };
    std::vector<Load> loads;
    std::vector<const char*> positional;
    for (int i = 1; i < argc; i++) {
        if (std::strcmp(argv[i], "--any-rom") == 0) {
            any_rom = true;
        } else if (std::strcmp(argv[i], "--load") == 0 && i + 1 < argc) {
            Load load{};
            if (std::sscanf(argv[++i], "%" SCNi32 ":%" SCNi32 ":%" SCNi32, &load.rom, &load.vram, &load.size) != 3) {
                std::fprintf(stderr, "--load espera rom:vram:tamanho\n");
                return 2;
            }
            loads.push_back(load);
        } else if (std::strcmp(argv[i], "--call") == 0 && i + 2 < argc) {
            call_name = argv[++i];
            call_arg = std::strtoll(argv[++i], nullptr, 0);
        } else {
            positional.push_back(argv[i]);
        }
    }
    if (positional.empty()) {
        std::fprintf(stderr, "uso: %s [--any-rom] [--call <função> <a0>] <rom> [syms.toml]\n", argv[0]);
        return 2;
    }

    std::ifstream file{positional[0], std::ios::binary};
    if (!file) {
        std::fprintf(stderr, "não foi possível abrir %s\n", positional[0]);
        return 1;
    }
    std::vector<uint8_t> data{std::istreambuf_iterator<char>(file), {}};

    m64::LoadedRom rom;
    const m64::RomError err = m64::load_rom(std::move(data), rom);
    std::printf("formato : %s\n", order_name(rom.info.original_order));
    std::printf("título  : %s\n", rom.info.internal_name.c_str());
    std::printf("sha1    : %s\n", rom.info.sha1.c_str());
    if (err == m64::RomError::UnsupportedRom && any_rom) {
        std::printf("versão  : desconhecida (aceita por --any-rom)\n");
    } else if (err != m64::RomError::None) {
        std::fprintf(stderr, "erro    : %s\n", m64::rom_error_message(err));
        return 1;
    } else {
        std::printf("versão  : %s\n", std::string(rom.info.known->description).c_str());
    }

    if (positional.size() < 2) {
        return 0;
    }

    m64::AotProgram program;
    std::string error;
    if (!m64::recompile_program(rom.data, positional[1], program, error)) {
        std::fprintf(stderr, "erro AOT: %s\n", error.c_str());
        return 1;
    }
    const auto& s = program.stats();
    std::printf("AOT     : %zu funções em %zu seções, %zu bytes nativos, %" PRIu64 " ms\n", s.num_functions,
                s.num_sections, s.code_size, s.codegen_microseconds / 1000);

    if (call_name != nullptr) {
        recomp_func_t* func = program.find(call_name);
        if (func == nullptr) {
            std::fprintf(stderr, "função %s não existe na tabela de símbolos\n", call_name);
            return 1;
        }
        std::vector<uint8_t> rdram(8 * 1024 * 1024);
        for (const Load& load : loads) {
            const uint32_t offset = load.vram - 0x80000000u;
            if (uint64_t(load.rom) + load.size > rom.data.size() || uint64_t(offset) + load.size > rdram.size()) {
                std::fprintf(stderr, "--load fora dos limites\n");
                return 1;
            }
            // A RDRAM recompilada guarda cada palavra de 32 bits na ordem do host.
            for (uint32_t i = 0; i < load.size; i++) {
                rdram[(offset + i) ^ 3] = rom.data[load.rom + i];
            }
        }
        recomp_context ctx{};
        ctx.r29 = 0xFFFFFFFF80000000ull + rdram.size() - 0x10;
        ctx.r4 = uint64_t(call_arg);
        func(rdram.data(), &ctx);
        std::printf("%s(%" PRId64 ") = %" PRId64 "\n", call_name, call_arg, int64_t(int32_t(ctx.r2)));
    }
    return 0;
}
