// Adaptador entre o ultramodern e o RT64. Baseado no rt64_render_context.cpp
// do Zelda 64: Recompiled (GPL-3.0), sem texturas, interface e mods.
#include "m64/rt64_renderer.hpp"

#include <algorithm>
#include <atomic>
#include <cstdio>

#define HLSL_CPU
#include "hle/rt64_application.h"

#include <ultramodern/config.hpp>
#include <ultramodern/renderer_context.hpp>

namespace m64 {

namespace {

// Memória do RSP e registradores do RDP/MI que o RT64 espera ler. Com HLE
// (display lists interpretadas pelo RT64) eles só precisam existir.
uint8_t g_dmem[0x1000];
uint8_t g_imem[0x1000];
uint32_t g_mi_intr_reg = 0;
uint32_t g_dpc_start_reg = 0;
uint32_t g_dpc_end_reg = 0;
uint32_t g_dpc_current_reg = 0;
uint32_t g_dpc_status_reg = 0;
uint32_t g_dpc_clock_reg = 0;
uint32_t g_dpc_bufbusy_reg = 0;
uint32_t g_dpc_pipebusy_reg = 0;
uint32_t g_dpc_tmem_reg = 0;
uint8_t g_dummy_rom_header[0x40];

void check_interrupts() {}

std::atomic<uint64_t> g_display_lists{0};
std::atomic<uint64_t> g_screen_updates{0};

ultramodern::renderer::SetupResult map_setup_result(RT64::Application::SetupResult result) {
    using R = ultramodern::renderer::SetupResult;
    switch (result) {
        case RT64::Application::SetupResult::Success: return R::Success;
        case RT64::Application::SetupResult::DynamicLibrariesNotFound: return R::DynamicLibrariesNotFound;
        case RT64::Application::SetupResult::InvalidGraphicsAPI: return R::InvalidGraphicsAPI;
        case RT64::Application::SetupResult::GraphicsAPINotFound: return R::GraphicsAPINotFound;
        case RT64::Application::SetupResult::GraphicsDeviceNotFound: return R::GraphicsDeviceNotFound;
    }
    return R::GraphicsDeviceNotFound;
}

class Rt64Renderer final : public ultramodern::renderer::RendererContext {
public:
    Rt64Renderer(uint8_t* rdram, void* window, const std::filesystem::path& data_dir) {
        RT64::Application::Core core{};
        core.window = static_cast<decltype(core.window)>(window);
        core.checkInterrupts = check_interrupts;
        core.HEADER = g_dummy_rom_header;
        core.RDRAM = rdram;
        core.DMEM = g_dmem;
        core.IMEM = g_imem;
        core.MI_INTR_REG = &g_mi_intr_reg;
        core.DPC_START_REG = &g_dpc_start_reg;
        core.DPC_END_REG = &g_dpc_end_reg;
        core.DPC_CURRENT_REG = &g_dpc_current_reg;
        core.DPC_STATUS_REG = &g_dpc_status_reg;
        core.DPC_CLOCK_REG = &g_dpc_clock_reg;
        core.DPC_BUFBUSY_REG = &g_dpc_bufbusy_reg;
        core.DPC_PIPEBUSY_REG = &g_dpc_pipebusy_reg;
        core.DPC_TMEM_REG = &g_dpc_tmem_reg;

        ultramodern::renderer::ViRegs* vi = ultramodern::renderer::get_vi_regs();
        core.VI_STATUS_REG = &vi->VI_STATUS_REG;
        core.VI_ORIGIN_REG = &vi->VI_ORIGIN_REG;
        core.VI_WIDTH_REG = &vi->VI_WIDTH_REG;
        core.VI_INTR_REG = &vi->VI_INTR_REG;
        core.VI_V_CURRENT_LINE_REG = &vi->VI_V_CURRENT_LINE_REG;
        core.VI_TIMING_REG = &vi->VI_TIMING_REG;
        core.VI_V_SYNC_REG = &vi->VI_V_SYNC_REG;
        core.VI_H_SYNC_REG = &vi->VI_H_SYNC_REG;
        core.VI_LEAP_REG = &vi->VI_LEAP_REG;
        core.VI_H_START_REG = &vi->VI_H_START_REG;
        core.VI_V_START_REG = &vi->VI_V_START_REG;
        core.VI_V_BURST_REG = &vi->VI_V_BURST_REG;
        core.VI_X_SCALE_REG = &vi->VI_X_SCALE_REG;
        core.VI_Y_SCALE_REG = &vi->VI_Y_SCALE_REG;

        RT64::ApplicationConfiguration app_config;
        app_config.useConfigurationFile = false;
        // Sem isso o RT64 usa $HOME/.rt64, e no Android HOME é /data, onde o
        // app não pode escrever.
        app_config.detectDataPath = false;
        app_config.dataPath = data_dir / "rt64";
        app_ = std::make_unique<RT64::Application>(core, app_config);

        // Padrões pensados para celular: resolução inteira da tela, sem MSAA,
        // proporção original (4:3) e Vulkan.
        auto& user = app_->userConfig;
        user.graphicsAPI = RT64::UserConfiguration::GraphicsAPI::Vulkan;
        user.resolution = RT64::UserConfiguration::Resolution::WindowIntegerScale;
        user.downsampleMultiplier = 1;
        user.aspectRatio = RT64::UserConfiguration::AspectRatio::Original;
        user.extAspectRatio = RT64::UserConfiguration::AspectRatio::Original;
        user.antialiasing = RT64::UserConfiguration::Antialiasing::None;
        user.refreshRate = RT64::UserConfiguration::RefreshRate::Original;
        user.internalColorFormat = RT64::UserConfiguration::InternalColorFormat::Standard;
        user.displayBuffering = RT64::UserConfiguration::DisplayBuffering::Triple;
        user.developerMode = false;
        app_->enhancementConfig.f3dex.forceBranch = true;
        app_->enhancementConfig.textureLOD.scale = true;

        setup_result = map_setup_result(app_->setup(0));
        chosen_api = ultramodern::renderer::GraphicsApi::Vulkan;
        if (setup_result != ultramodern::renderer::SetupResult::Success) {
            std::fprintf(stderr, "[m64] RT64 não inicializou (código %d)\n", int(setup_result));
            app_ = nullptr;
        } else {
            const auto& caps = app_->device->getCapabilities();
            std::fprintf(stderr,
                         "[m64] RT64 inicializado (Vulkan): scalarBlockLayout=%d descriptorIndexing=%d "
                         "sampleLocations=%d presentWait=%d triangleFan=%d uma=%d maxTexture=%llu\n",
                         caps.scalarBlockLayout, caps.descriptorIndexing, caps.sampleLocations, caps.presentWait,
                         caps.triangleFan, caps.uma, (unsigned long long)caps.maxTextureSize);
        }
    }

    ~Rt64Renderer() override = default;

    bool valid() override { return app_ != nullptr; }

    bool update_config(const ultramodern::renderer::GraphicsConfig&,
                       const ultramodern::renderer::GraphicsConfig&) override {
        return false;
    }

    void enable_instant_present() override {
        app_->enhancementConfig.presentation.mode = RT64::EnhancementConfiguration::Presentation::Mode::PresentEarly;
        app_->updateEnhancementConfig();
    }

    void send_dl(const OSTask* task) override {
        if (g_display_lists.fetch_add(1) == 0) {
            std::fprintf(stderr, "[m64] primeira display list recebida\n");
        }
        app_->state->rsp->reset();
        app_->interpreter->loadUCodeGBI(task->t.ucode & 0x3FFFFFF, task->t.ucode_data & 0x3FFFFFF, true);
        app_->processDisplayLists(app_->core.RDRAM, task->t.data_ptr & 0x3FFFFFF, 0, true);
    }

    void send_dummy_workload(uint32_t fb_address) override {}

    void update_screen() override {
        if (g_screen_updates.fetch_add(1) == 0) {
            std::fprintf(stderr, "[m64] primeira atualização de tela\n");
        }
        app_->updateScreen();
    }

    void shutdown() override {
        if (app_ != nullptr) {
            app_->end();
        }
    }

    uint32_t get_display_framerate() const override {
        return app_->presentQueue->ext.sharedResources->swapChainRate;
    }

    float get_resolution_scale() const override {
        constexpr int kReferenceHeight = 240;
        const int height = int(app_->sharedQueueResources->swapChainHeight);
        if (height <= 0) {
            return 1.0f;
        }
        return std::max(float((height + kReferenceHeight - 1) / kReferenceHeight), 1.0f);
    }

private:
    std::unique_ptr<RT64::Application> app_;
};

} // namespace

std::unique_ptr<ultramodern::renderer::RendererContext> create_rt64_renderer(uint8_t* rdram, void* window,
                                                                            const std::filesystem::path& data_dir) {
    return std::make_unique<Rt64Renderer>(rdram, window, data_dir);
}

RendererStats rt64_renderer_stats() {
    return RendererStats{g_display_lists.load(), g_screen_updates.load()};
}

} // namespace m64
