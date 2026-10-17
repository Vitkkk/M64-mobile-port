// Liga o programa recompilado ao N64ModernRuntime (librecomp + ultramodern).
//
// O runtime substitui a libultra: threads, filas de mensagens, timers,
// DMA da ROM, VI, controle, saves. Este módulo:
//   1. recompila a ROM com as funções da libultra apontando para as
//      implementações nativas do runtime;
//   2. registra as seções/funções traduzidas no librecomp, que as usa para
//      resolver ponteiros de função e iniciar threads;
//   3. registra o jogo e inicia o runtime.
#pragma once

#include <cstdint>
#include <filesystem>
#include <memory>
#include <string>
#include <unordered_map>

#include "m64/aot.hpp"
#include "m64/rom.hpp"

namespace ultramodern::renderer {
class RendererContext;
}

namespace m64 {

enum class SaveType {
    None,
    Eep4k, // Super Mario 64
    Eep16k,
    Sram,
    Flashram,
};

struct GameDescription {
    std::string game_id;      // nome do arquivo da ROM sem extensão, ex.: "sm64.us"
    std::string display_name;
    uint32_t entrypoint = 0;  // vram da entrada; a ROM 0x1000 é copiada para cá no boot
    SaveType save_type = SaveType::None;
};

// Descrição de Super Mario 64 para uma versão conhecida.
GameDescription sm64_description(const KnownRom& rom);

// Funções que o runtime implementa nativamente (nome sem o sufixo _recomp).
const std::unordered_map<std::string, recomp_func_t*>& runtime_native_functions();

// Ganchos para recompilar um jogo que vai rodar sobre o runtime. As
// funções da libultra conhecidas pelo N64Recomp que o runtime não
// implementa ficam proibidas. `extra_native` permite acrescentar outras.
AotHooks runtime_hooks(const std::unordered_map<std::string, recomp_func_t*>& extra_native = {});

struct RunOptions {
    // Pasta onde a ROM está como <game_id>.z64 (também recebe saves e config).
    std::filesystem::path data_dir;
    // Janela/superfície nativa repassada ao renderizador (ANativeWindow* no Android).
    void* window = nullptr;
    // Cria o renderizador. Nulo = renderizador vazio, que descarta as
    // display lists (útil para rodar sem tela, em testes).
    std::unique_ptr<ultramodern::renderer::RendererContext> (*create_renderer)(uint8_t* rdram, void* window) =
        nullptr;
};

// Registra o programa (já recompilado com runtime_hooks) e a ROM no
// librecomp e roda o jogo. Bloqueia até stop_game() ser chamado.
// Só pode ser chamado uma vez por processo (limitação do runtime).
bool run_game(const GameDescription& game, const LoadedRom& rom, const AotProgram& program,
              const RunOptions& options, std::string& error);

// Pede para o runtime encerrar; run_game retorna em seguida.
void stop_game();

// Quantos quadros (interrupções de VI) já passaram desde o início do jogo.
uint64_t vi_count();

} // namespace m64
