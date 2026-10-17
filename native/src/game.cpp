#include "m64/game.hpp"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <vector>

#include <librecomp/game.hpp>
#include <librecomp/overlays.hpp>
#include <librecomp/rsp.hpp>
#include <ultramodern/error_handling.hpp>
#include <ultramodern/renderer_context.hpp>
#include <ultramodern/ultramodern.hpp>

#define XXH_INLINE_ALL
#include "xxHash/xxh3.h"

#include "m64/input.hpp"
#include "recompiler/context.h"

// Implementações do librecomp/ultramodern usadas pelo código gerado.
extern "C" {
recomp_func_t* get_function(int32_t vram);
void cop0_status_write(recomp_context* ctx, gpr value);
gpr cop0_status_read(recomp_context* ctx);
void switch_error(const char* func, uint32_t vram, uint32_t jtbl);
void do_break(uint32_t vram);
void pause_self(uint8_t* rdram);
void recomp_trigger_event(uint8_t* rdram, recomp_context* ctx, uint32_t event_index);
}

namespace m64 {

// Gerado pelo CMake a partir das fontes do runtime (m64_native_table.cpp).
extern const std::pair<const char*, recomp_func_t*> kRuntimeNativeTable[];
extern const size_t kRuntimeNativeTableSize;

namespace {

std::atomic<uint64_t> g_vi_count{0};

// Renderizador que só confirma o trabalho recebido. Mantém o jogo andando
// sem tela; o RT64 entra no lugar dele no app.
class NullRenderer final : public ultramodern::renderer::RendererContext {
public:
    NullRenderer() {
        setup_result = ultramodern::renderer::SetupResult::Success;
        chosen_api = ultramodern::renderer::GraphicsApi::Auto;
    }
    bool valid() override { return true; }
    bool update_config(const ultramodern::renderer::GraphicsConfig&,
                       const ultramodern::renderer::GraphicsConfig&) override {
        return true;
    }
    void enable_instant_present() override {}
    void send_dl(const OSTask*) override {}
    void send_dummy_workload(uint32_t) override {}
    void update_screen() override {}
    void shutdown() override {}
    uint32_t get_display_framerate() const override { return 60; }
    float get_resolution_scale() const override { return 1.0f; }
};

RunOptions g_run_options;

std::unique_ptr<ultramodern::renderer::RendererContext> create_render_context(uint8_t* rdram,
                                                                              ultramodern::renderer::WindowHandle,
                                                                              bool) {
    if (g_run_options.create_renderer != nullptr) {
        return g_run_options.create_renderer(rdram, g_run_options.window);
    }
    return std::make_unique<NullRenderer>();
}

// Microcódigo de áudio ainda não integrado: a tarefa termina sem gerar som.
RspExitReason silent_audio_ucode(uint8_t*, uint32_t) {
    return RspExitReason::Broke;
}

RspUcodeFunc* get_rsp_microcode(const OSTask* task) {
    if (task->t.type == M_AUDTASK) {
        return silent_audio_ucode;
    }
    std::fprintf(stderr, "[m64] tarefa de RSP sem microcódigo: tipo %u\n", task->t.type);
    return nullptr;
}

void poll_input() {}

bool get_input(int controller_num, uint16_t* buttons, float* x, float* y) {
    if (controller_num != 0) {
        return false;
    }
    const ControllerState state = controller(0).get();
    *buttons = state.buttons;
    // O runtime espera o analógico em -1..1.
    *x = float(state.stick_x) / 80.0f;
    *y = float(state.stick_y) / 80.0f;
    return true;
}

void set_rumble(int, bool) {}

ultramodern::input::connected_device_info_t get_connected_device_info(int controller_num) {
    if (controller_num == 0) {
        return {ultramodern::input::Device::Controller, ultramodern::input::Pak::None};
    }
    return {ultramodern::input::Device::None, ultramodern::input::Pak::None};
}

void queue_samples(int16_t*, size_t) {}
size_t get_frames_remaining() { return 0; }
void set_frequency(uint32_t) {}

void on_vi() {
    g_vi_count.fetch_add(1, std::memory_order_relaxed);
}

void message_box(const char* msg) {
    std::fprintf(stderr, "[m64] %s\n", msg);
}

recomp::SaveType to_recomp(SaveType type) {
    switch (type) {
        case SaveType::None: return recomp::SaveType::None;
        case SaveType::Eep4k: return recomp::SaveType::Eep4k;
        case SaveType::Eep16k: return recomp::SaveType::Eep16k;
        case SaveType::Sram: return recomp::SaveType::Sram;
        case SaveType::Flashram: return recomp::SaveType::Flashram;
    }
    return recomp::SaveType::None;
}

// Tabelas que o librecomp consulta durante todo o jogo; precisam viver até o fim.
std::vector<std::vector<FuncEntry>> g_func_entries;
std::vector<SectionTableEntry> g_section_table;

void register_sections(const AotProgram& program) {
    g_func_entries.clear();
    g_section_table.clear();
    g_func_entries.reserve(program.sections().size());
    for (size_t i = 0; i < program.sections().size(); i++) {
        const AotSection& section = program.sections()[i];
        auto& entries = g_func_entries.emplace_back();
        for (const AotFunction& func : section.functions) {
            entries.push_back(FuncEntry{func.func, func.vram - section.vram, func.size});
        }
        g_section_table.push_back(SectionTableEntry{
            .rom_addr = section.rom,
            .ram_addr = section.vram,
            .size = section.size,
            .funcs = entries.data(),
            .num_funcs = entries.size(),
            .relocs = nullptr,
            .num_relocs = 0,
            .index = i,
        });
    }
    recomp::overlays::register_overlays(
        {.code_sections = g_section_table.data(),
         .num_code_sections = g_section_table.size(),
         .total_num_sections = g_section_table.size()},
        {.table = nullptr, .len = 0});
}

} // namespace

GameDescription sm64_description(const KnownRom& rom) {
    return GameDescription{
        .game_id = "sm64." + std::string(rom.id),
        .display_name = std::string(rom.description),
        .entrypoint = 0x80246000,
        .save_type = SaveType::Eep4k,
    };
}

const std::unordered_map<std::string, recomp_func_t*>& runtime_native_functions() {
    static const std::unordered_map<std::string, recomp_func_t*> table = [] {
        std::unordered_map<std::string, recomp_func_t*> out;
        for (size_t i = 0; i < kRuntimeNativeTableSize; i++) {
            out.emplace(kRuntimeNativeTable[i].first, kRuntimeNativeTable[i].second);
        }
        return out;
    }();
    return table;
}

AotHooks runtime_hooks(const std::unordered_map<std::string, recomp_func_t*>& extra_native) {
    AotHooks hooks{};
    hooks.get_function = get_function;
    hooks.cop0_status_write = cop0_status_write;
    hooks.cop0_status_read = cop0_status_read;
    hooks.switch_error = switch_error;
    hooks.do_break = do_break;
    hooks.pause_self = pause_self;
    hooks.trigger_event = recomp_trigger_event;

    hooks.native_functions = runtime_native_functions();
    for (const auto& [name, func] : extra_native) {
        hooks.native_functions[name] = func;
    }

    // Mesma regra do recomp estático: essas funções da libultra não são
    // traduzidas. Sem implementação nativa, chamá-las é erro.
    for (const auto* list : {&N64Recomp::reimplemented_funcs, &N64Recomp::ignored_funcs}) {
        for (const std::string& name : *list) {
            if (!hooks.native_functions.contains(name)) {
                hooks.forbidden_functions.insert(name);
            }
        }
    }
    return hooks;
}

bool run_game(const GameDescription& game, const LoadedRom& rom, const AotProgram& program,
              const RunOptions& options, std::string& error) {
    recomp_func_t* entry = program.find(game.entrypoint);
    if (entry == nullptr) {
        error = "a tabela de símbolos não tem função no ponto de entrada";
        return false;
    }

    const std::filesystem::path stored_rom = options.data_dir / (game.game_id + ".z64");
    if (!std::filesystem::exists(stored_rom)) {
        error = "ROM não encontrada em " + stored_rom.string();
        return false;
    }

    g_run_options = options;
    g_vi_count = 0;
    register_sections(program);

    recomp::register_config_path(options.data_dir);
    const std::u8string game_id(game.game_id.begin(), game.game_id.end());
    recomp::GameEntry entry_desc{
        .rom_hash = XXH3_64bits(rom.data.data(), rom.data.size()),
        .internal_name = rom.info.internal_name,
        .display_name = game.display_name,
        .game_id = game_id,
        .mod_game_id = "",
        .save_type = to_recomp(game.save_type),
        .is_enabled = true,
        .entrypoint_address = gpr(int32_t(game.entrypoint)),
        .entrypoint = entry,
    };
    if (!recomp::register_game(entry_desc)) {
        error = "o runtime recusou o registro do jogo";
        return false;
    }

    static const recomp::rsp::callbacks_t rsp_callbacks{.get_rsp_microcode = get_rsp_microcode};
    static const ultramodern::renderer::callbacks_t renderer_callbacks{.create_render_context = create_render_context};
    static const ultramodern::audio_callbacks_t audio_callbacks{
        .queue_samples = queue_samples,
        .get_frames_remaining = get_frames_remaining,
        .set_frequency = set_frequency,
    };
    static const ultramodern::input::callbacks_t input_callbacks{
        .poll_input = poll_input,
        .get_input = get_input,
        .set_rumble = set_rumble,
        .get_connected_device_info = get_connected_device_info,
    };
    static const ultramodern::events::callbacks_t events_callbacks{.vi_callback = on_vi, .gfx_init_callback = nullptr};
    static const ultramodern::error_handling::callbacks_t error_callbacks{.message_box = message_box};

    recomp::Configuration config{};
    config.project_version = recomp::Version{0, 1, 0};
    // Não há janela SDL; o handle só é repassado ao renderizador.
    config.window_handle = reinterpret_cast<ultramodern::renderer::WindowHandle>(options.window ? options.window
                                                                                                : &g_run_options);
    config.rsp_callbacks = rsp_callbacks;
    config.renderer_callbacks = renderer_callbacks;
    config.audio_callbacks = audio_callbacks;
    config.input_callbacks = input_callbacks;
    config.events_callbacks = events_callbacks;
    config.error_handling_callbacks = error_callbacks;

    // start_game antes de start: a thread do jogo começa assim que o runtime sobe.
    recomp::start_game(game_id, "");
    recomp::start(config);
    return true;
}

void stop_game() {
    ultramodern::quit();
}

uint64_t vi_count() {
    return g_vi_count.load(std::memory_order_relaxed);
}

} // namespace m64
