// Carregamento e validação da ROM fornecida pelo usuário.
//
// Nenhum byte da ROM faz parte deste projeto: o usuário importa o próprio
// dump e este módulo apenas normaliza a ordem dos bytes e confere o hash.
#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace m64 {

enum class RomByteOrder {
    BigEndian,   // .z64 (nativa), magic 80 37 12 40
    ByteSwapped, // .v64, magic 37 80 40 12
    LittleEndian,// .n64, magic 40 12 37 80
    Unknown,
};

enum class RomVersion {
    US,
    JP,
    EU,
    Shindou,
    CN,
};

struct KnownRom {
    RomVersion version;
    std::string_view id;         // "us", "jp", ...
    std::string_view description;
    std::string_view sha1;       // SHA-1 da ROM em ordem .z64
};

// Hashes publicados pelo projeto de decompilação (n64decomp/sm64, arquivos *.sha1).
// Apenas o hash é guardado aqui; ele identifica a ROM sem conter nada dela.
inline constexpr std::array<KnownRom, 5> kKnownRoms{{
    {RomVersion::US, "us", "Super Mario 64 (USA)", "9bef1128717f958171a4afac3ed78ee2bb4e86ce"},
    {RomVersion::JP, "jp", "Super Mario 64 (Japan)", "8a20a5c83d6ceb0f0506cfc9fa20d8f438cafe51"},
    {RomVersion::EU, "eu", "Super Mario 64 (Europe)", "4ac5721683d0e0b6bbb561b58a71740845dceea9"},
    {RomVersion::Shindou, "sh", "Super Mario 64 Shindou Edition (Japan)", "3f319ae697533a255a1003d09202379d78d5a2e0"},
    {RomVersion::CN, "cn", "Super Mario 64 (China, iQue)", "2e1db2780985a1f068077dc0444b685f39cd90ec"},
}};

RomByteOrder detect_byte_order(std::span<const uint8_t> data);

// Converte a ROM para a ordem .z64 (big-endian) in-place.
// Retorna false se o formato não for reconhecido.
bool normalize_to_z64(std::vector<uint8_t>& data);

std::array<uint8_t, 20> sha1(std::span<const uint8_t> data);
std::string sha1_hex(std::span<const uint8_t> data);

const KnownRom* identify_rom(std::string_view sha1_hex_digest);

struct RomInfo {
    RomByteOrder original_order = RomByteOrder::Unknown;
    std::string sha1;
    std::string internal_name; // título no cabeçalho (0x20, 20 bytes)
    const KnownRom* known = nullptr;
};

struct LoadedRom {
    std::vector<uint8_t> data; // sempre em ordem .z64
    RomInfo info;
};

enum class RomError {
    None,
    TooSmall,
    UnknownFormat,
    UnsupportedRom, // ROM válida de N64, mas não é uma versão suportada
};

const char* rom_error_message(RomError error);

// Normaliza e identifica uma ROM. `data` é consumido.
// Em caso de erro, `out.info` ainda é preenchido o quanto for possível.
RomError load_rom(std::vector<uint8_t>&& data, LoadedRom& out);

} // namespace m64
