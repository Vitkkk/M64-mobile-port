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

# 5. O preparador no celular: o horizon-prepare como programa, e os lançadores java/javac que
#    iniciam o OpenJDK 25 para Android baixado pelo app. Um app só executa programas da pasta de
#    bibliotecas do APK, então todos vão como lib*.so (o Gradle extrai: useLegacyPackaging).
cargo ndk "${TARGETS[@]}" -P 28 build -p horizon-prepare "${PROFILE_ARGS[@]}"
case "$PROFILE" in release) DIR=release ;; dev) DIR=debug ;; *) DIR="$PROFILE" ;; esac
LLVM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
for abi in "${ABI_LIST[@]}"; do
    case "$abi" in
        arm64-v8a) TRIPLE=aarch64-linux-android ;;
        x86_64) TRIPLE=x86_64-linux-android ;;
        *) echo "ABI sem suporte: $abi" >&2; exit 1 ;;
    esac
    mkdir -p "$OUT/$abi"
    cp "$HZ/target/$TRIPLE/$DIR/horizon-prepare" "$OUT/$abi/libhorizon_prepare.so"
    for variant in java javac; do
        FLAGS=()
        [[ "$variant" == javac ]] && FLAGS=(-DJAVAC)
        "$LLVM/bin/${TRIPLE}28-clang" -O2 -fPIE -pie -Wall -Werror "${FLAGS[@]}" \
            -Wl,-z,max-page-size=16384 -o "$OUT/$abi/libzenith_$variant.so" \
            "$HERE/native/launcher/java_launcher.c" -ldl
    done
    cp "$LLVM/sysroot/usr/lib/$TRIPLE/libc++_shared.so" "$OUT/$abi/"
done

# 6. O kit do preparador (assets/prepare-kit.zip): o que o horizon-prepare lê da raiz do Horizon
#    para conferir os arquivos e compilar os exportadores no celular. Só código do Horizon (MIT) e
#    os hashes fixados; nada da Mojang.
python3 - "$HZ" "$HERE/app/src/main/assets/prepare-kit.zip" <<'PY'
import sys, zipfile
from pathlib import Path
root, out = Path(sys.argv[1]), Path(sys.argv[2])
out.parent.mkdir(parents=True, exist_ok=True)
files = [root / "research-lock.json", root / "crates/horizon-prepare/src/exporters.rs"]
files += sorted((root / "mods/horizon/src/test/java").rglob("*.java"))
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as kit:
    for f in files:
        info = zipfile.ZipInfo(f.relative_to(root).as_posix(), (2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        kit.writestr(info, f.read_bytes())
print(f"kit: {len(files)} arquivos -> {out}")
PY
find "$OUT" -name '*.so' -exec ls -lh {} \;
