// Captura stdout/stderr no Android. Lá o que o runtime e o RT64 imprimem é
// descartado; aqui vai para o logcat, para um arquivo (que sobrevive a um
// crash) e para um buffer com as últimas linhas, mostrado na tela.
#pragma once

#include <string>

namespace m64 {

void start_log_capture(const std::string& file_path);
std::string recent_log(size_t max_lines);

} // namespace m64
