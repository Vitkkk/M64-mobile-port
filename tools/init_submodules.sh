#!/usr/bin/env bash
# Baixa só os submódulos que o build usa. O RT64 tem outros (pacotes de
# Windows, XeSS, diálogos de arquivo) que o port não precisa.
set -euo pipefail
cd "$(dirname "$0")/.."

git submodule update --init native/third_party/N64ModernRuntime native/third_party/rt64
git -C native/third_party/N64ModernRuntime submodule update --init --recursive

RT64_MODULES=(implot hlslpp xxHash imgui im3d dxc stb ddspp zstd re-spirv plume mupen64plus-core)
for m in "${RT64_MODULES[@]}"; do
    git -C native/third_party/rt64 submodule update --init --recursive "src/contrib/$m"
done
