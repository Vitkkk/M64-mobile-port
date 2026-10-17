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
#include <vector>

#include "recomp.h"

namespace m64 {

struct AotStats {
    size_t num_sections = 0;
    size_t num_functions = 0;
    size_t num_failed = 0;
    size_t code_size = 0;
    uint64_t codegen_microseconds = 0;
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
    const std::vector<std::string>& failed_functions() const { return failed_; }

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    std::unordered_map<uint32_t, recomp_func_t*> by_vram_;
    std::unordered_map<std::string, recomp_func_t*> by_name_;
    AotStats stats_;
    std::vector<std::string> failed_;

    friend bool recompile_program(std::span<const uint8_t>, const std::filesystem::path&, AotProgram&, std::string&);
};

// Recompila todas as funções listadas em `symbols_toml` (formato do
// N64Recomp: [[section]] com rom/vram/size e functions = [...]).
// `rom_z64` deve estar em ordem .z64 (ver m64::load_rom).
// O programa recompilado passa a ser o alvo das buscas de ponteiro de função
// feitas pelo código gerado (jalr), então só um programa fica ativo por vez.
bool recompile_program(std::span<const uint8_t> rom_z64, const std::filesystem::path& symbols_toml,
                       AotProgram& out, std::string& error);

} // namespace m64
