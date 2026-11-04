#!/usr/bin/env bash
# Compila o Horizon (Rust) para Android e coloca a biblioteca no app:
#   app/src/main/jniLibs/<abi>/libhorizon_android.so
#
# Uso:  horizon-android/tools/build-native.sh [--abi arm64-v8a,x86_64] [--profile release|fast|dev]
#
# Pré-requisitos: rustup com os alvos aarch64-linux-android (e x86_64-linux-android
# para o emulador), cargo-ndk (cargo install cargo-ndk) e o NDK em ANDROID_NDK_HOME
# (ou em $ANDROID_HOME/ndk/27.2.12479018).
#
# O que ele faz:
#  1. baixa o submódulo third_party/horizon (raso, só o commit fixado);
#  2. aplica patches/horizon-android.patch (uma vez; pula se já aplicado);
#  3. copia native/ para dentro do workspace do Horizon como crates/horizon-android,
#     para usar o mesmo Cargo.lock e os mesmos crates corrigidos do Horizon;
#  4. cargo ndk build -p horizon-android.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
HZ="$HERE/third_party/horizon"

ABIS="arm64-v8a"
PROFILE="release"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi) ABIS="$2"; shift 2 ;;
        --profile) PROFILE="$2"; shift 2 ;;
        -h|--help) sed -n 2,16p "$0"; exit 0 ;;
        *) echo "opção desconhecida: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    for c in "${ANDROID_HOME:-}/ndk/27.2.12479018" "${ANDROID_SDK_ROOT:-}/ndk/27.2.12479018"; do
        [[ -d "$c" ]] && export ANDROID_NDK_HOME="$c" && break
    done
fi
[[ -n "${ANDROID_NDK_HOME:-}" ]] || { echo "defina ANDROID_NDK_HOME (NDK 27.2.12479018)" >&2; exit 1; }
command -v cargo-ndk >/dev/null || { echo "instale o cargo-ndk: cargo install cargo-ndk" >&2; exit 1; }

# 1. Submódulo
if [[ ! -f "$HZ/Cargo.toml" ]]; then
    git -C "$REPO" submodule update --init --depth 1 horizon-android/third_party/horizon
fi

# 2. Patch (idempotente)
if git -C "$HZ" apply --check "$HERE/patches/horizon-android.patch" 2>/dev/null; then
    git -C "$HZ" apply "$HERE/patches/horizon-android.patch"
    echo "patch aplicado"
elif git -C "$HZ" apply --reverse --check "$HERE/patches/horizon-android.patch" 2>/dev/null; then
    echo "patch já estava aplicado"
else
    echo "o patch não se aplica ao submódulo (commit diferente?):" >&2
    git -C "$HZ" apply --check "$HERE/patches/horizon-android.patch"
    exit 1
fi

# 3. A crate de entrada dentro do workspace do Horizon
rm -rf "$HZ/crates/horizon-android"
mkdir -p "$HZ/crates/horizon-android"
cp -r "$HERE/native/Cargo.toml" "$HERE/native/src" "$HZ/crates/horizon-android/"

# 4. Compilação
OUT="$HERE/app/src/main/jniLibs"
TARGETS=()
IFS=',' read -ra ABI_LIST <<< "$ABIS"
for abi in "${ABI_LIST[@]}"; do TARGETS+=(-t "$abi"); done
case "$PROFILE" in
    release) PROFILE_ARGS=(--release) ;;
    dev) PROFILE_ARGS=() ;;
    *) PROFILE_ARGS=(--profile "$PROFILE") ;;
esac

cd "$HZ"
# API 28: AAudio (áudio do cpal) e Vulkan 1.1 existem a partir dela.
cargo ndk "${TARGETS[@]}" -P 28 -o "$OUT" build -p horizon-android "${PROFILE_ARGS[@]}"
find "$OUT" -name 'libhorizon_android.so' -exec ls -lh {} \;
