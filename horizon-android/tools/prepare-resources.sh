#!/usr/bin/env bash
# Prepara, no PC, os recursos do jogo a partir da SUA cópia do Minecraft: Java
# Edition e gera um .zip para importar no Zenith (Ajustes → Recursos do jogo).
#
# Uso:
#   horizon-android/tools/prepare-resources.sh --server-jar ~/Downloads/server.jar \
#       [--minecraft-dir ~/.minecraft] [--out horizon-resources.zip]
#
# Precisa de: Rust (stable), Java 25 (JAVA_HOME ou no PATH), o Minecraft 26.3
# instalado pelo launcher oficial, Prism ou MultiMC (para o client.jar e os sons),
# e o server.jar 26.3 que você baixou da Mojang. Nada é baixado por este script:
# o horizon-prepare confere cada arquivo contra os hashes fixados do Horizon.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
HZ="$HERE/third_party/horizon"

SERVER_JAR=""
MC_DIR=""
OUT="$PWD/horizon-resources.zip"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --server-jar) SERVER_JAR="$2"; shift 2 ;;
        --minecraft-dir) MC_DIR="$2"; shift 2 ;;
        --out) OUT="$(realpath -m "$2")"; shift 2 ;;
        -h|--help) sed -n 2,13p "$0"; exit 0 ;;
        *) echo "opção desconhecida: $1" >&2; exit 2 ;;
    esac
done
[[ -n "$SERVER_JAR" ]] || { echo "falta --server-jar (o server.jar 26.3 da sua cópia)" >&2; exit 2; }
SERVER_JAR="$(realpath "$SERVER_JAR")"

if [[ ! -f "$HZ/Cargo.toml" ]]; then
    git -C "$REPO" submodule update --init --depth 1 horizon-android/third_party/horizon
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PREPARED="$WORK/preparado"

# --root: o horizon-prepare lê o research-lock.json da raiz do Horizon (os hashes fixados).
ARGS=(--root "$HZ" --prepare --output "$PREPARED" --server-jar "$SERVER_JAR" --complete)
[[ -n "$MC_DIR" ]] && ARGS+=(--minecraft-dir "$MC_DIR")

echo "== Compilando o horizon-prepare"
cargo build --release --manifest-path "$HZ/Cargo.toml" -p horizon-prepare
echo "== Preparando (alguns minutos; roda o gerador de dados do vanilla com o seu jar)"
"$HZ/target/release/horizon-prepare" "${ARGS[@]}"
"$HZ/target/release/horizon-prepare" --root "$HZ" --check --output "$PREPARED"

echo "== Compactando"
rm -f "$OUT"
(cd "$PREPARED" && zip -qr "$OUT" .)
ls -lh "$OUT"
echo "Pronto. Copie $(basename "$OUT") para o celular e importe em Ajustes → Recursos do jogo."
echo "Não compartilhe este arquivo: ele é feito da sua cópia do jogo."
