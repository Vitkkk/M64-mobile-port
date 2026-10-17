#include "m64/aot.hpp"

#include <chrono>
#include <cstdio>
#include <sstream>

#include "recompiler/context.h"
#include "recompiler/live_recompiler.h"
#include "syms_locate.hpp"

namespace m64 {

namespace {

// O código gerado chama os ganchos sem ponteiro de contexto, então o
// programa ativo fica num global.
const AotProgram* g_active_program = nullptr;

// Trampolins: vram da função substituída -> implementação nativa.
struct Trampoline {
    recomp_func_t* native; // nulo = função proibida
    std::string name;
};
std::unordered_map<uint32_t, Trampoline> g_trampolines;

// Corpo MIPS que substitui uma função nativa: `syscall` seguido de `nop`.
// O N64Recomp traduz `syscall` numa chamada ao syscall_handler seguida de
// retorno, e o handler despacha pelo endereço da instrução. Assim a troca
// funciona sem nenhuma mudança no N64Recomp e vale também para chamadas por
// ponteiro (jalr), que chegam na função traduzida pelo endereço dela.
constexpr uint32_t kSyscall = 0x0000000C;

uint32_t to_rom_word(uint32_t w) {
    // As palavras do Context ficam na ordem da ROM (big-endian) e o
    // recompilador as inverte ao decodificar.
    return __builtin_bswap32(w);
}

void trampoline_handler(uint8_t* rdram, recomp_context* ctx, int32_t instruction_vram) {
    auto it = g_trampolines.find(uint32_t(instruction_vram));
    if (it == g_trampolines.end()) {
        std::fprintf(stderr, "[m64aot] syscall inesperado em 0x%08X\n", uint32_t(instruction_vram));
        std::abort();
    }
    if (it->second.native == nullptr) {
        std::fprintf(stderr, "[m64aot] chamada a função proibida %s (0x%08X)\n", it->second.name.c_str(),
                     uint32_t(instruction_vram));
        std::abort();
    }
    it->second.native(rdram, ctx);
}

recomp_func_t* default_lookup_function(int32_t vram) {
    recomp_func_t* func = g_active_program ? g_active_program->find(uint32_t(vram)) : nullptr;
    if (func == nullptr) {
        std::fprintf(stderr, "[m64aot] função não encontrada em 0x%08X\n", uint32_t(vram));
        std::abort();
    }
    return func;
}

void default_switch_error(const char* func, uint32_t vram, uint32_t jtbl) {
    std::fprintf(stderr, "[m64aot] jump table fora dos limites em %s (0x%08X, tabela 0x%08X)\n", func, vram, jtbl);
    std::abort();
}

void default_do_break(uint32_t vram) {
    std::fprintf(stderr, "[m64aot] instrução break em 0x%08X\n", vram);
    std::abort();
}

// Só o bit FR (modo de registradores de ponto flutuante) importa fora do runtime.
void default_cop0_status_write(recomp_context* ctx, gpr value) {
    constexpr uint32_t kFR = 0x04000000;
    ctx->status_reg = uint32_t(value);
    ctx->mips3_float_mode = (ctx->status_reg & kFR) != 0;
    ctx->f_odd = ctx->mips3_float_mode ? &ctx->f1.u32l : &ctx->f0.u32h;
}

gpr default_cop0_status_read(recomp_context* ctx) {
    return gpr(int32_t(ctx->status_reg));
}

void default_pause_self(uint8_t*) {
    std::fprintf(stderr, "[m64aot] pause_self sem runtime\n");
    std::abort();
}

void default_trigger_event(uint8_t*, recomp_context*, uint32_t event_index) {
    std::fprintf(stderr, "[m64aot] evento %u sem runtime\n", event_index);
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
        g_trampolines.clear();
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
                       const AotHooks& hooks, AotProgram& out, std::string& error) {
    static const bool initialized = (N64Recomp::live_recompiler_init(), true);
    (void)initialized;

    // Seções cuja posição na ROM depende dos assets são localizadas antes.
    std::filesystem::path symbols_path = symbols_toml;
    const std::filesystem::path resolved_path = symbols_toml.string() + ".resolved";
    bool used_resolved = false;
    if (!resolve_located_sections(rom_z64, symbols_toml, resolved_path, used_resolved, error)) {
        return false;
    }
    if (used_resolved) {
        symbols_path = resolved_path;
    }

    N64Recomp::Context context{};
    std::vector<uint8_t> rom_copy(rom_z64.begin(), rom_z64.end());
    const bool loaded = N64Recomp::Context::from_symbol_file(symbols_path, std::move(rom_copy), context, true);
    if (used_resolved) {
        std::error_code ignored;
        std::filesystem::remove(resolved_path, ignored);
    }
    if (!loaded) {
        error = "falha ao ler a tabela de símbolos: " + symbols_toml.string();
        return false;
    }

    // Troca o corpo das funções nativas/proibidas pelo trampolim. As
    // palavras originais continuam na ROM; só a tradução muda.
    std::unordered_map<uint32_t, Trampoline> trampolines;
    std::vector<uint32_t> original_sizes;
    original_sizes.reserve(context.functions.size());
    size_t num_native = 0, num_forbidden = 0;
    for (N64Recomp::Function& func : context.functions) {
        original_sizes.push_back(uint32_t(func.words.size() * sizeof(uint32_t)));
        recomp_func_t* native = nullptr;
        if (auto it = hooks.native_functions.find(func.name); it != hooks.native_functions.end()) {
            native = it->second;
            num_native++;
        } else if (hooks.forbidden_functions.contains(func.name)) {
            num_forbidden++;
        } else {
            continue;
        }
        func.words = {to_rom_word(kSyscall), 0};
        trampolines[func.vram] = Trampoline{native, func.name};
    }

    auto& impl = *out.impl_;
    impl.section_addresses.clear();
    for (const auto& section : context.sections) {
        impl.section_addresses.push_back(int32_t(section.ram_addr));
    }

    N64Recomp::LiveGeneratorInputs inputs{};
    inputs.get_function = hooks.get_function ? hooks.get_function : default_lookup_function;
    inputs.cop0_status_write = hooks.cop0_status_write ? hooks.cop0_status_write : default_cop0_status_write;
    inputs.cop0_status_read = hooks.cop0_status_read ? hooks.cop0_status_read : default_cop0_status_read;
    inputs.switch_error = hooks.switch_error ? hooks.switch_error : default_switch_error;
    inputs.do_break = hooks.do_break ? hooks.do_break : default_do_break;
    inputs.pause_self = hooks.pause_self ? hooks.pause_self : default_pause_self;
    inputs.trigger_event = hooks.trigger_event ? hooks.trigger_event : default_trigger_event;
    inputs.syscall_handler = trampoline_handler;
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
    out.sections_.clear();
    for (size_t s = 0; s < context.sections.size(); s++) {
        const auto& section = context.sections[s];
        out.sections_.push_back(AotSection{section.name, section.rom_addr, section.ram_addr, section.size, {}});
    }
    for (size_t i = 0; i < context.functions.size(); i++) {
        const N64Recomp::Function& func = context.functions[i];
        recomp_func_t* compiled = impl.output.functions[i];
        // Se dois símbolos dividem o mesmo endereço, vale o primeiro.
        out.by_vram_.emplace(func.vram, compiled);
        out.by_name_.emplace(func.name, compiled);
        out.sections_[func.section_index].functions.push_back(
            AotFunction{func.name, func.vram, original_sizes[i], compiled});
    }

    out.stats_ = AotStats{
        .num_sections = context.sections.size(),
        .num_functions = context.functions.size(),
        .num_native = num_native,
        .num_forbidden = num_forbidden,
        .code_size = impl.output.code_size,
        .codegen_microseconds =
            uint64_t(std::chrono::duration_cast<std::chrono::microseconds>(end - start).count()),
    };

    g_active_program = &out;
    g_trampolines = std::move(trampolines);
    return true;
}

} // namespace m64
