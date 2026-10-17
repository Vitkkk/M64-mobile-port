#include "m64/aot.hpp"

#include <chrono>
#include <cstdio>
#include <sstream>

#include "recompiler/context.h"
#include "recompiler/live_recompiler.h"

namespace m64 {

namespace {

// O código gerado chama estes callbacks sem ponteiro de contexto, então o
// programa ativo fica num global.
const AotProgram* g_active_program = nullptr;

recomp_func_t* lookup_function(int32_t vram) {
    recomp_func_t* func = g_active_program ? g_active_program->find(uint32_t(vram)) : nullptr;
    if (func == nullptr) {
        std::fprintf(stderr, "[m64aot] função não encontrada em 0x%08X\n", uint32_t(vram));
        std::abort();
    }
    return func;
}

void switch_error(const char* func, uint32_t vram, uint32_t jtbl) {
    std::fprintf(stderr, "[m64aot] jump table fora dos limites em %s (0x%08X, tabela 0x%08X)\n", func, vram, jtbl);
    std::abort();
}

void do_break(uint32_t vram) {
    std::fprintf(stderr, "[m64aot] instrução break em 0x%08X\n", vram);
    std::abort();
}

} // namespace

struct AotProgram::Impl {
    N64Recomp::LiveGeneratorOutput output;
    std::vector<int32_t> section_addresses;
};

AotProgram::AotProgram() : impl_(std::make_unique<Impl>()) {}

AotProgram::~AotProgram() {
    if (g_active_program == this) {
        g_active_program = nullptr;
    }
}

recomp_func_t* AotProgram::find(uint32_t vram) const {
    auto it = by_vram_.find(vram);
    return it == by_vram_.end() ? nullptr : it->second;
}

recomp_func_t* AotProgram::find(const std::string& name) const {
    auto it = by_name_.find(name);
    return it == by_name_.end() ? nullptr : it->second;
}

bool recompile_program(std::span<const uint8_t> rom_z64, const std::filesystem::path& symbols_toml,
                       AotProgram& out, std::string& error) {
    static const bool initialized = (N64Recomp::live_recompiler_init(), true);
    (void)initialized;

    N64Recomp::Context context{};
    std::vector<uint8_t> rom_copy(rom_z64.begin(), rom_z64.end());
    if (!N64Recomp::Context::from_symbol_file(symbols_toml, std::move(rom_copy), context, true)) {
        error = "falha ao ler a tabela de símbolos: " + symbols_toml.string();
        return false;
    }

    auto& impl = *out.impl_;
    impl.section_addresses.clear();
    for (const auto& section : context.sections) {
        impl.section_addresses.push_back(int32_t(section.ram_addr));
    }

    N64Recomp::LiveGeneratorInputs inputs{};
    inputs.switch_error = switch_error;
    inputs.do_break = do_break;
    inputs.get_function = lookup_function;
    inputs.reference_section_addresses = nullptr;
    inputs.local_section_addresses = impl.section_addresses.data();

    const auto start = std::chrono::steady_clock::now();

    N64Recomp::LiveGenerator generator{context.functions.size(), inputs};
    std::vector<std::vector<uint32_t>> static_funcs(context.sections.size());
    out.failed_.clear();
    for (size_t i = 0; i < context.functions.size(); i++) {
        std::ostringstream discard{};
        if (!N64Recomp::recompile_function_live(generator, context, i, discard, static_funcs, true)) {
            out.failed_.push_back(context.functions[i].name);
        }
    }

    if (!out.failed_.empty()) {
        error = "falha ao recompilar " + std::to_string(out.failed_.size()) + " função(ões), a primeira é " +
                out.failed_.front();
        return false;
    }

    impl.output = generator.finish();
    if (!impl.output.good) {
        error = "o gerador de código nativo (sljit) falhou";
        return false;
    }

    const auto end = std::chrono::steady_clock::now();

    out.by_vram_.clear();
    out.by_name_.clear();
    for (size_t i = 0; i < context.functions.size(); i++) {
        recomp_func_t* func = impl.output.functions[i];
        // Se dois símbolos dividem o mesmo endereço, vale o primeiro.
        out.by_vram_.emplace(context.functions[i].vram, func);
        out.by_name_.emplace(context.functions[i].name, func);
    }

    out.stats_ = AotStats{
        .num_sections = context.sections.size(),
        .num_functions = context.functions.size(),
        .num_failed = 0,
        .code_size = impl.output.code_size,
        .codegen_microseconds =
            uint64_t(std::chrono::duration_cast<std::chrono::microseconds>(end - start).count()),
    };

    g_active_program = &out;
    return true;
}

} // namespace m64
