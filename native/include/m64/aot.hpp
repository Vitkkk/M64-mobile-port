// Recompilação ahead-of-time feita no próprio aparelho.
//
// A ROM do usuário + a tabela de símbolos (endereços e tamanhos das funções)
// são passadas ao frontend do N64Recomp, e o backend LiveRecomp (sljit) gera
// código nativo (ARM64 no celular, x86-64 no PC) para TODAS as funções de uma
// vez, antes do jogo começar. Não há interpretação de instruções nem dynarec
// rastreando a execução: é a mesma tradução função-a-função do recomp
// estático, só que feita na hora da carga em vez de no PC de quem compila.
//
// Consequência prática: o APK distribuído não contém nenhum código do jogo.
// O código nativo existe apenas na memória do aparelho do usuário.
#pragma once

#include <cstdint>
#include <filesystem>
#include <memory>
#include <span>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "recomp.h"

namespace m64 {

// Ganchos que o código gerado chama. Os nulos usam implementações padrão,
// suficientes para rodar funções isoladas (testes, m64aot --call). O runtime
// (m64/game.hpp) preenche com as versões do librecomp/ultramodern.
struct AotHooks {
    recomp_func_t* (*get_function)(int32_t vram) = nullptr;
    void (*cop0_status_write)(recomp_context* ctx, gpr value) = nullptr;
    gpr (*cop0_status_read)(recomp_context* ctx) = nullptr;
    void (*switch_error)(const char* func, uint32_t vram, uint32_t jtbl) = nullptr;
    void (*do_break)(uint32_t vram) = nullptr;
    void (*pause_self)(uint8_t* rdram) = nullptr;
    void (*trigger_event)(uint8_t* rdram, recomp_context* ctx, uint32_t event_index) = nullptr;

    // Funções do jogo substituídas por implementações nativas, por nome
    // (ex.: "osCreateThread" -> osCreateThread_recomp do librecomp). Elas não
    // são traduzidas; qualquer chamada a elas, direta ou por ponteiro, cai na
    // versão nativa.
    std::unordered_map<std::string, recomp_func_t*> native_functions;

    // Funções que nunca deveriam rodar (internas da libultra que mexem no
    // hardware). Não são traduzidas; se forem chamadas, o programa aborta
    // com o nome delas.
    std::unordered_set<std::string> forbidden_functions;
};

struct AotStats {
    size_t num_sections = 0;
    size_t num_functions = 0;
    size_t num_native = 0;    // substituídas por implementações nativas
    size_t num_forbidden = 0; // marcadas como proibidas
    size_t code_size = 0;
    uint64_t codegen_microseconds = 0;
};

struct AotFunction {
    std::string name;
    uint32_t vram;
    uint32_t size; // tamanho original na ROM
    recomp_func_t* func;
};

struct AotSection {
    std::string name;
    uint32_t rom;
    uint32_t vram;
    uint32_t size;
    std::vector<AotFunction> functions;
};

class AotProgram {
public:
    AotProgram();
    ~AotProgram();
    AotProgram(const AotProgram&) = delete;
    AotProgram& operator=(const AotProgram&) = delete;

    recomp_func_t* find(uint32_t vram) const;
    recomp_func_t* find(const std::string& name) const;
    const AotStats& stats() const { return stats_; }
    const std::vector<AotSection>& sections() const { return sections_; }
    const std::vector<std::string>& failed_functions() const { return failed_; }

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    std::unordered_map<uint32_t, recomp_func_t*> by_vram_;
    std::unordered_map<std::string, recomp_func_t*> by_name_;
    std::vector<AotSection> sections_;
    AotStats stats_;
    std::vector<std::string> failed_;

    friend bool recompile_program(std::span<const uint8_t>, const std::filesystem::path&, const AotHooks&,
                                  AotProgram&, std::string&);
};

// Recompila todas as funções listadas em `symbols_toml` (formato do
// N64Recomp: [[section]] com rom/vram/size e functions = [...]).
// `rom_z64` deve estar em ordem .z64 (ver m64::load_rom).
// O programa recompilado passa a ser o ativo: é nele que os ganchos padrão
// procuram funções e trampolins nativos, então só um fica ativo por vez.
bool recompile_program(std::span<const uint8_t> rom_z64, const std::filesystem::path& symbols_toml,
                       const AotHooks& hooks, AotProgram& out, std::string& error);

inline bool recompile_program(std::span<const uint8_t> rom_z64, const std::filesystem::path& symbols_toml,
                              AotProgram& out, std::string& error) {
    return recompile_program(rom_z64, symbols_toml, AotHooks{}, out, error);
}

} // namespace m64
