// m64run: recompila a ROM e roda o jogo sobre o N64ModernRuntime, sem tela
// (renderizador vazio, sem áudio). Serve para testar o runtime no PC.
//
//   m64run [opções] <rom> <syms.toml>
//
//   --seconds N      encerra depois de N segundos (padrão 10)
//   --data DIR       pasta de dados (ROM, saves); padrão: pasta temporária
//   --any-rom        aceita ROM com hash desconhecido; exige --entry
//   --entry VRAM     ponto de entrada (padrão: o do jogo reconhecido)
//   --exit-on NOME   trata a função NOME do jogo como "fim do teste": ela
//                    imprime o argumento a0 e encerra o runtime
//   --print-on NOME  trata a função NOME do jogo como log: imprime a0
#include <atomic>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>
#include <vector>

#include "m64/game.hpp"
#include "m64/rom.hpp"

namespace {

std::atomic<bool> g_exit_called{false};
std::atomic<int64_t> g_exit_value{0};
const char* g_exit_name = nullptr;

void exit_hook(uint8_t*, recomp_context* ctx) {
    g_exit_value = int64_t(int32_t(ctx->r4));
    g_exit_called = true;
    m64::stop_game();
}

void print_hook(uint8_t*, recomp_context* ctx) {
    std::printf("[jogo] %d\n", int32_t(ctx->r4));
    std::fflush(stdout);
}

} // namespace

int main(int argc, char** argv) {
    int seconds = 10;
    bool any_rom = false;
    uint32_t entry = 0;
    std::filesystem::path data_dir;
    std::vector<const char*> print_names;
    std::vector<const char*> positional;
    for (int i = 1; i < argc; i++) {
        if (std::strcmp(argv[i], "--seconds") == 0 && i + 1 < argc) {
            seconds = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--data") == 0 && i + 1 < argc) {
            data_dir = argv[++i];
        } else if (std::strcmp(argv[i], "--any-rom") == 0) {
            any_rom = true;
        } else if (std::strcmp(argv[i], "--entry") == 0 && i + 1 < argc) {
            entry = uint32_t(std::strtoul(argv[++i], nullptr, 0));
        } else if (std::strcmp(argv[i], "--print-on") == 0 && i + 1 < argc) {
            print_names.push_back(argv[++i]);
        } else if (std::strcmp(argv[i], "--exit-on") == 0 && i + 1 < argc) {
            g_exit_name = argv[++i];
        } else {
            positional.push_back(argv[i]);
        }
    }
    if (positional.size() != 2) {
        std::fprintf(stderr, "uso: %s [opções] <rom> <syms.toml>\n", argv[0]);
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
    m64::GameDescription game;
    if (err == m64::RomError::None) {
        game = m64::sm64_description(*rom.info.known);
    } else if (err == m64::RomError::UnsupportedRom && any_rom) {
        game = m64::GameDescription{.game_id = "custom", .display_name = rom.info.internal_name};
    } else {
        std::fprintf(stderr, "erro: %s\n", m64::rom_error_message(err));
        return 1;
    }
    if (entry != 0) {
        game.entrypoint = entry;
    }
    if (game.entrypoint == 0) {
        std::fprintf(stderr, "ROM desconhecida: informe --entry\n");
        return 2;
    }

    if (data_dir.empty()) {
        data_dir = std::filesystem::temp_directory_path() / "m64run";
    }
    std::filesystem::create_directories(data_dir);
    {
        std::ofstream out{data_dir / (game.game_id + ".z64"), std::ios::binary};
        out.write(reinterpret_cast<const char*>(rom.data.data()), std::streamsize(rom.data.size()));
    }

    std::unordered_map<std::string, recomp_func_t*> extra;
    for (const char* name : print_names) {
        extra[name] = print_hook;
    }
    if (g_exit_name != nullptr) {
        extra[g_exit_name] = exit_hook;
    }

    m64::AotProgram program;
    std::string error;
    if (!m64::recompile_program(rom.data, positional[1], m64::runtime_hooks(extra), program, error)) {
        std::fprintf(stderr, "erro AOT: %s\n", error.c_str());
        return 1;
    }
    const auto& s = program.stats();
    std::printf("AOT: %zu funções (%zu nativas do runtime, %zu proibidas) em %" PRIu64 " ms\n", s.num_functions,
                s.num_native, s.num_forbidden, s.codegen_microseconds / 1000);
    std::fflush(stdout);

    std::thread timeout{[seconds] {
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
        while (!g_exit_called && std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
        if (!g_exit_called) {
            m64::stop_game();
        }
    }};

    const bool ok = m64::run_game(game, rom, program, m64::RunOptions{.data_dir = data_dir}, error);
    timeout.join();
    if (!ok) {
        std::fprintf(stderr, "erro: %s\n", error.c_str());
        return 1;
    }

    std::printf("quadros (VI): %" PRIu64 "\n", m64::vi_count());
    if (g_exit_name != nullptr) {
        if (!g_exit_called) {
            std::printf("%s não foi chamada antes do tempo limite\n", g_exit_name);
            return 1;
        }
        std::printf("%s(%" PRId64 ")\n", g_exit_name, int64_t(g_exit_value));
    }
    return 0;
}
