#include "m64/input.hpp"

namespace m64 {

InputState& controller(int port) {
    static InputState ports[4];
    return ports[port & 3];
}

} // namespace m64
