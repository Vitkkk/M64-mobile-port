// Seções da tabela de símbolos cuja posição na ROM só se descobre com a ROM.
//
// No SM64, o código do goddard (a cabeça do Mario na tela de título) fica na
// ROM depois dos gráficos comprimidos, então a posição dele depende dos
// assets e não sai de uma compilação só do código. Para essas seções, a
// tabela traz `m64_locate = { offset, size, sha1 }`: o SHA-1 de um trecho da
// seção (nunca os bytes em si). O app procura esse trecho na ROM do usuário
// e preenche `rom`.
#pragma once

#include <cstdint>
#include <filesystem>
#include <span>
#include <string>

namespace m64 {

// Lê `symbols_toml` e, se houver seções com m64_locate, grava uma cópia com
// `rom` preenchido em `resolved_out` e devolve true com `used_resolved`.
// Sem seções a localizar, não grava nada e devolve true.
bool resolve_located_sections(std::span<const uint8_t> rom_z64, const std::filesystem::path& symbols_toml,
                              const std::filesystem::path& resolved_out, bool& used_resolved, std::string& error);

} // namespace m64
