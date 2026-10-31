cmake_minimum_required(VERSION 3.22)
project(ppss55_probe CXX)
# Small launcher-side library: real Vulkan / CPU queries and a dlopen() test of the emulator core,
# so the launcher can report actual initialisation errors before a game is started.
add_library(ppss55_probe SHARED probe.cpp)
target_compile_features(ppss55_probe PRIVATE cxx_std_17)
target_link_libraries(ppss55_probe PRIVATE log dl)
