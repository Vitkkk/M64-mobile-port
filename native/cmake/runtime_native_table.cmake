# Gera a tabela nome -> implementação nativa das funções da libultra que o
# N64ModernRuntime oferece (definidas como `extern "C" void X_recomp(uint8_t* rdram, recomp_context* ctx)`,
# ou com a macro RDRAM_ARG no lugar do primeiro parâmetro).
# Referências fortes garantem que o linker inclua essas funções.
function(m64_generate_runtime_native_table runtime_dir output)
    file(GLOB sources "${runtime_dir}/librecomp/src/*.cpp" "${runtime_dir}/ultramodern/src/*.cpp")
    set(names "")
    foreach(source ${sources})
        file(READ "${source}" content)
        # Ignora definições comentadas (ex.: setjmp/longjmp desativados no runtime).
        string(REGEX REPLACE "//[^\n]*" "" content "${content}")
        string(REGEX MATCHALL
            "extern \"C\" void [A-Za-z0-9_]+_recomp[ \t]*\\([ \t]*(uint8_t[ \t]*\\*[ \t]*rdram[ \t]*,|RDRAM_ARG)[ \t]*recomp_context[ \t]*\\*[ \t]*ctx[ \t]*\\)"
            matches "${content}")
        foreach(match ${matches})
            string(REGEX REPLACE "extern \"C\" void ([A-Za-z0-9_]+)_recomp.*" "\\1" name "${match}")
            list(APPEND names "${name}")
        endforeach()
    endforeach()
    list(REMOVE_DUPLICATES names)
    list(SORT names)

    set(decls "")
    set(entries "")
    foreach(name ${names})
        string(APPEND decls "void ${name}_recomp(uint8_t* rdram, recomp_context* ctx);\n")
        string(APPEND entries "    {\"${name}\", ${name}_recomp},\n")
    endforeach()
    list(LENGTH names count)

    set(text "// Gerado por cmake/runtime_native_table.cmake. Não editar.\n#include <cstddef>\n#include <utility>\n#include \"recomp.h\"\n\nextern \"C\" {\n${decls}}\n\nnamespace m64 {\nextern const std::pair<const char*, recomp_func_t*> kRuntimeNativeTable[] = {\n${entries}};\nextern const size_t kRuntimeNativeTableSize = ${count};\n}\n")
    file(CONFIGURE OUTPUT "${output}" CONTENT "${text}" @ONLY)
    message(STATUS "m64: ${count} funções nativas do runtime")
endfunction()
