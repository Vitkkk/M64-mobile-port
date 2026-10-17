// Renderizador RT64 (Vulkan) ligado ao runtime.
#pragma once

#include <cstdint>
#include <memory>

namespace ultramodern::renderer {
class RendererContext;
}

namespace m64 {

// Cria o renderizador RT64 desenhando em `window` (ANativeWindow* no
// Android). Compatível com RunOptions::create_renderer.
std::unique_ptr<ultramodern::renderer::RendererContext> create_rt64_renderer(uint8_t* rdram, void* window);

} // namespace m64
