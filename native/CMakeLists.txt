cmake_minimum_required(VERSION 3.20)
project(m64port LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 17)

option(M64_BUILD_CLI "Ferramenta de linha de comando para testar ROM/AOT no PC" ON)
option(M64_BUILD_TESTS "Testes unitários" ON)

# N64Recomp (MIT): frontend de análise MIPS + backend LiveRecomp (sljit).
# EXCLUDE_FROM_ALL evita compilar as ferramentas de PC que não usamos.
add_subdirectory(third_party/N64Recomp EXCLUDE_FROM_ALL)

set(N64RECOMP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/N64Recomp)

add_library(m64core STATIC
    src/rom.cpp
    src/aot.cpp
    src/input.cpp
    # Context::from_symbol_file (leitor da tabela de símbolos) mora no
    # config.cpp do executável do N64Recomp, não nas bibliotecas dele.
    ${N64RECOMP_DIR}/src/config.cpp
)
target_include_directories(m64core PUBLIC include PRIVATE ${N64RECOMP_DIR}/src)
target_compile_features(m64core PUBLIC cxx_std_20)
target_link_libraries(m64core PUBLIC LiveRecomp N64Recomp PRIVATE fmt tomlplusplus::tomlplusplus)

if(M64_BUILD_CLI)
    add_executable(m64aot cli/m64aot.cpp)
    target_link_libraries(m64aot PRIVATE m64core)
endif()

if(M64_BUILD_TESTS)
    enable_testing()
    add_executable(m64_tests tests/test_main.cpp tests/test_rom.cpp tests/test_aot.cpp)
    target_link_libraries(m64_tests PRIVATE m64core)
    add_test(NAME m64_tests COMMAND m64_tests)
endif()
