// Estado do controle de N64 alimentado pela tela de toque (ou gamepad).
// O runtime lê daqui quando o jogo consulta o controle.
#pragma once

#include <atomic>
#include <cstdint>

namespace m64 {

// Bits dos botões no formato do controle de N64.
enum N64Button : uint16_t {
    kButtonCRight = 0x0001,
    kButtonCLeft = 0x0002,
    kButtonCDown = 0x0004,
    kButtonCUp = 0x0008,
    kButtonR = 0x0010,
    kButtonL = 0x0020,
    kButtonDRight = 0x0100,
    kButtonDLeft = 0x0200,
    kButtonDDown = 0x0400,
    kButtonDUp = 0x0800,
    kButtonStart = 0x1000,
    kButtonZ = 0x2000,
    kButtonB = 0x4000,
    kButtonA = 0x8000,
};

struct ControllerState {
    uint16_t buttons = 0;
    int8_t stick_x = 0; // -80..80 é o alcance típico do analógico original
    int8_t stick_y = 0;
};

// Escrito pela thread de UI, lido pela thread do jogo. Empacotado em 32 bits
// para trocar o estado inteiro de forma atômica.
class InputState {
public:
    void set(const ControllerState& state) {
        packed_.store(uint32_t(state.buttons) | (uint32_t(uint8_t(state.stick_x)) << 16) |
                          (uint32_t(uint8_t(state.stick_y)) << 24),
                      std::memory_order_relaxed);
    }

    ControllerState get() const {
        const uint32_t v = packed_.load(std::memory_order_relaxed);
        return ControllerState{uint16_t(v), int8_t(v >> 16), int8_t(v >> 24)};
    }

private:
    std::atomic<uint32_t> packed_{0};
};

InputState& controller(int port);

} // namespace m64
