#include <cstring>

#include "m64/rom.hpp"
#include "test.hpp"

namespace {

std::span<const uint8_t> bytes(const char* s) {
    return {reinterpret_cast<const uint8_t*>(s), std::strlen(s)};
}

// ROM sintética: só o magic do cabeçalho e um título inventado.
std::vector<uint8_t> fake_rom_z64() {
    std::vector<uint8_t> rom(0x2000);
    for (size_t i = 0; i < rom.size(); i++) {
        rom[i] = uint8_t(i * 7);
    }
    rom[0] = 0x80;
    rom[1] = 0x37;
    rom[2] = 0x12;
    rom[3] = 0x40;
    std::memcpy(&rom[0x20], "TESTE SINTETICO     ", 20);
    return rom;
}

} // namespace

TEST(sha1_test_vectors) {
    CHECK(m64::sha1_hex(bytes("")) == "da39a3ee5e6b4b0d3255bfef95601890afd80709");
    CHECK(m64::sha1_hex(bytes("abc")) == "a9993e364706816aba3e25717850c26c9cd0d89d");
    CHECK(m64::sha1_hex(bytes("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")) ==
          "84983e441c3bd26ebaae4aa1f95129e5e54670f1");
    std::string million(1000000, 'a');
    CHECK(m64::sha1_hex(bytes(million.c_str())) == "34aa973cd4c4daa4f61eeb2bdbad27316534016f");
}

TEST(detects_and_normalizes_all_byte_orders) {
    const std::vector<uint8_t> z64 = fake_rom_z64();

    std::vector<uint8_t> v64 = z64;
    for (size_t i = 0; i < v64.size(); i += 2) std::swap(v64[i], v64[i + 1]);
    std::vector<uint8_t> n64 = z64;
    for (size_t i = 0; i < n64.size(); i += 4) {
        std::swap(n64[i], n64[i + 3]);
        std::swap(n64[i + 1], n64[i + 2]);
    }

    CHECK(m64::detect_byte_order(z64) == m64::RomByteOrder::BigEndian);
    CHECK(m64::detect_byte_order(v64) == m64::RomByteOrder::ByteSwapped);
    CHECK(m64::detect_byte_order(n64) == m64::RomByteOrder::LittleEndian);

    CHECK(m64::normalize_to_z64(v64) && v64 == z64);
    CHECK(m64::normalize_to_z64(n64) && n64 == z64);
}

TEST(unknown_rom_is_reported_but_described) {
    m64::LoadedRom rom;
    CHECK(m64::load_rom(fake_rom_z64(), rom) == m64::RomError::UnsupportedRom);
    CHECK(rom.info.internal_name == "TESTE SINTETICO");
    CHECK(rom.info.sha1.size() == 40);
    CHECK(rom.info.known == nullptr);
}

TEST(rejects_garbage) {
    m64::LoadedRom rom;
    CHECK(m64::load_rom(std::vector<uint8_t>(16), rom) == m64::RomError::TooSmall);
    CHECK(m64::load_rom(std::vector<uint8_t>(0x2000, 0xAB), rom) == m64::RomError::UnknownFormat);
}

TEST(known_hashes_are_identified) {
    const m64::KnownRom* us = m64::identify_rom("9bef1128717f958171a4afac3ed78ee2bb4e86ce");
    CHECK(us != nullptr && us->version == m64::RomVersion::US);
    CHECK(m64::identify_rom("0000000000000000000000000000000000000000") == nullptr);
}

#include "m64/input.hpp"

TEST(input_state_round_trips) {
    m64::InputState state;
    state.set({uint16_t(m64::kButtonA | m64::kButtonZ), -80, 79});
    const m64::ControllerState got = state.get();
    CHECK(got.buttons == (m64::kButtonA | m64::kButtonZ));
    CHECK(got.stick_x == -80);
    CHECK(got.stick_y == 79);
}
