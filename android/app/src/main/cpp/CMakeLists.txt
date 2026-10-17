cmake_minimum_required(VERSION 3.22)
project(m64port_android LANGUAGES C CXX)

set(M64_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native)
set(M64_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(M64_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${M64_ROOT} m64native)

add_library(m64port SHARED ${M64_ROOT}/android/jni_bridge.cpp)
target_link_libraries(m64port PRIVATE m64rt64 log android)
