#include "syms_locate.hpp"

#include <fstream>
#include <sstream>

#include <toml++/toml.hpp>

#include "m64/rom.hpp"

namespace m64 {

namespace {

// As seções de código começam alinhadas em 16 bytes na ROM.
constexpr size_t kSectionAlignment = 16;

std::optional<uint32_t> find_section(std::span<const uint8_t> rom, uint32_t window_offset, uint32_t window_size,
                                     const std::string& sha1_hex_digest) {
    if (window_size == 0 || window_size > rom.size()) {
        return std::nullopt;
    }
    for (size_t start = 0; start + window_offset + window_size <= rom.size(); start += kSectionAlignment) {
        if (sha1_hex(rom.subspan(start + window_offset, window_size)) == sha1_hex_digest) {
            return uint32_t(start);
        }
    }
    return std::nullopt;
}

} // namespace

bool resolve_located_sections(std::span<const uint8_t> rom_z64, const std::filesystem::path& symbols_toml,
                              const std::filesystem::path& resolved_out, bool& used_resolved, std::string& error) {
    used_resolved = false;
    toml::table table;
    try {
        table = toml::parse_file(symbols_toml.u8string());
    } catch (const toml::parse_error& err) {
        error = "tabela de símbolos inválida: " + std::string(err.description());
        return false;
    }

    toml::array* sections = table["section"].as_array();
    if (sections == nullptr) {
        return true; // o leitor do N64Recomp reporta o erro com detalhes
    }

    bool any = false;
    for (toml::node& node : *sections) {
        toml::table* section = node.as_table();
        if (section == nullptr) {
            continue;
        }
        toml::table* locate = (*section)["m64_locate"].as_table();
        if (locate == nullptr) {
            continue;
        }
        any = true;
        const auto offset = (*locate)["offset"].value<uint32_t>();
        const auto size = (*locate)["size"].value<uint32_t>();
        const auto sha1 = (*locate)["sha1"].value<std::string>();
        const auto name = (*section)["name"].value<std::string>().value_or("?");
        if (!offset || !size || !sha1) {
            error = "m64_locate incompleto na seção " + name;
            return false;
        }
        const auto rom_start = find_section(rom_z64, *offset, *size, *sha1);
        if (!rom_start) {
            error = "não foi possível localizar a seção " + name +
                    " na ROM (a tabela de símbolos não corresponde a esta ROM)";
            return false;
        }
        section->insert_or_assign("rom", int64_t(*rom_start));
    }

    if (!any) {
        return true;
    }

    std::ofstream out{resolved_out};
    if (!out) {
        error = "não foi possível gravar " + resolved_out.string();
        return false;
    }
    out << table << "\n";
    used_resolved = true;
    return true;
}

} // namespace m64
