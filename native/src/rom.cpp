#include "m64/rom.hpp"

#include <algorithm>
#include <cstring>

namespace m64 {

namespace {

constexpr size_t kMinRomSize = 0x1000; // cabeçalho + IPL3

} // namespace

RomByteOrder detect_byte_order(std::span<const uint8_t> data) {
    if (data.size() < 4) {
        return RomByteOrder::Unknown;
    }
    const uint8_t a = data[0], b = data[1], c = data[2], d = data[3];
    if (a == 0x80 && b == 0x37 && c == 0x12 && d == 0x40) {
        return RomByteOrder::BigEndian;
    }
    if (a == 0x37 && b == 0x80 && c == 0x40 && d == 0x12) {
        return RomByteOrder::ByteSwapped;
    }
    if (a == 0x40 && b == 0x12 && c == 0x37 && d == 0x80) {
        return RomByteOrder::LittleEndian;
    }
    return RomByteOrder::Unknown;
}

bool normalize_to_z64(std::vector<uint8_t>& data) {
    switch (detect_byte_order(data)) {
        case RomByteOrder::BigEndian:
            return true;
        case RomByteOrder::ByteSwapped:
            for (size_t i = 0; i + 1 < data.size(); i += 2) {
                std::swap(data[i], data[i + 1]);
            }
            return true;
        case RomByteOrder::LittleEndian:
            for (size_t i = 0; i + 3 < data.size(); i += 4) {
                std::swap(data[i], data[i + 3]);
                std::swap(data[i + 1], data[i + 2]);
            }
            return true;
        case RomByteOrder::Unknown:
            break;
    }
    return false;
}

// SHA-1 (FIPS 180-4). Implementado aqui para não depender de bibliotecas
// de criptografia no Android; é usado só para identificar a ROM.
std::array<uint8_t, 20> sha1(std::span<const uint8_t> data) {
    uint32_t h[5] = {0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u};

    auto rol = [](uint32_t x, int n) { return (x << n) | (x >> (32 - n)); };

    auto process_block = [&](const uint8_t* block) {
        uint32_t w[80];
        for (int i = 0; i < 16; i++) {
            w[i] = (uint32_t(block[i * 4]) << 24) | (uint32_t(block[i * 4 + 1]) << 16) |
                   (uint32_t(block[i * 4 + 2]) << 8) | uint32_t(block[i * 4 + 3]);
        }
        for (int i = 16; i < 80; i++) {
            w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; i++) {
            uint32_t f, k;
            if (i < 20) {
                f = (b & c) | (~b & d);
                k = 0x5A827999u;
            } else if (i < 40) {
                f = b ^ c ^ d;
                k = 0x6ED9EBA1u;
            } else if (i < 60) {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8F1BBCDCu;
            } else {
                f = b ^ c ^ d;
                k = 0xCA62C1D6u;
            }
            uint32_t temp = rol(a, 5) + f + e + k + w[i];
            e = d;
            d = c;
            c = rol(b, 30);
            b = a;
            a = temp;
        }
        h[0] += a;
        h[1] += b;
        h[2] += c;
        h[3] += d;
        h[4] += e;
    };

    const size_t full_blocks = data.size() / 64;
    for (size_t i = 0; i < full_blocks; i++) {
        process_block(data.data() + i * 64);
    }

    // Padding: 0x80, zeros, tamanho em bits (big-endian, 64 bits).
    uint8_t tail[128] = {};
    const size_t rem = data.size() % 64;
    std::memcpy(tail, data.data() + full_blocks * 64, rem);
    tail[rem] = 0x80;
    const size_t tail_len = (rem < 56) ? 64 : 128;
    const uint64_t bit_len = uint64_t(data.size()) * 8;
    for (int i = 0; i < 8; i++) {
        tail[tail_len - 1 - i] = uint8_t(bit_len >> (i * 8));
    }
    process_block(tail);
    if (tail_len == 128) {
        process_block(tail + 64);
    }

    std::array<uint8_t, 20> out{};
    for (int i = 0; i < 5; i++) {
        out[i * 4] = uint8_t(h[i] >> 24);
        out[i * 4 + 1] = uint8_t(h[i] >> 16);
        out[i * 4 + 2] = uint8_t(h[i] >> 8);
        out[i * 4 + 3] = uint8_t(h[i]);
    }
    return out;
}

std::string sha1_hex(std::span<const uint8_t> data) {
    static constexpr char kHex[] = "0123456789abcdef";
    const auto digest = sha1(data);
    std::string out;
    out.reserve(40);
    for (uint8_t byte : digest) {
        out.push_back(kHex[byte >> 4]);
        out.push_back(kHex[byte & 0xF]);
    }
    return out;
}

const KnownRom* identify_rom(std::string_view sha1_hex_digest) {
    for (const KnownRom& rom : kKnownRoms) {
        if (rom.sha1 == sha1_hex_digest) {
            return &rom;
        }
    }
    return nullptr;
}

const char* rom_error_message(RomError error) {
    switch (error) {
        case RomError::None:
            return "OK";
        case RomError::TooSmall:
            return "Arquivo pequeno demais para ser uma ROM de N64.";
        case RomError::UnknownFormat:
            return "Formato não reconhecido (esperado .z64, .v64 ou .n64).";
        case RomError::UnsupportedRom:
            return "ROM de N64 válida, mas não é uma versão suportada de Super Mario 64.";
    }
    return "Erro desconhecido.";
}

RomError load_rom(std::vector<uint8_t>&& data, LoadedRom& out) {
    out = LoadedRom{};
    out.info.original_order = detect_byte_order(data);

    if (data.size() < kMinRomSize) {
        return RomError::TooSmall;
    }
    if (!normalize_to_z64(data)) {
        return RomError::UnknownFormat;
    }

    out.data = std::move(data);
    out.info.sha1 = sha1_hex(out.data);

    // Título interno: 20 bytes ASCII em 0x20, sem espaços à direita.
    std::string name(reinterpret_cast<const char*>(out.data.data() + 0x20), 20);
    name.erase(std::find(name.begin(), name.end(), '\0'), name.end());
    while (!name.empty() && name.back() == ' ') {
        name.pop_back();
    }
    out.info.internal_name = std::move(name);

    out.info.known = identify_rom(out.info.sha1);
    if (out.info.known == nullptr) {
        return RomError::UnsupportedRom;
    }
    return RomError::None;
}

} // namespace m64
