#!/usr/bin/env bash
# Downloads model files that are too large for git and verifies them against the committed *.sha256.
# Idempotent: a file that is already present with the right hash is not downloaded again.
# Usage (from any directory): scripts/download-models.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# Pinned commit of https://huggingface.co/BAAI/bge-m3: "main" may change and silently alter token counts
BGE_M3_REVISION=5617a9f61b028005a4858fdac845db406aefb181

download() {
    local url=$1 target=$2
    local checksum="$target.sha256"

    if [[ -f "$target" ]] && sha256sum --check --status "$checksum"; then
        echo "OK (cached): $target"
        return
    fi

    # Download next to the target and move only after verification: a broken file never takes its place
    local tmp="$target.part"
    trap 'rm -f "$tmp"' EXIT
    curl --fail --location --silent --show-error --retry 3 --output "$tmp" "$url"
    if ! sed "s|  $target\$|  $tmp|" "$checksum" | sha256sum --check --status; then
        echo "Checksum mismatch: $url" >&2
        exit 1
    fi
    mv "$tmp" "$target"
    trap - EXIT
    echo "OK (downloaded): $target"
}

download "https://huggingface.co/BAAI/bge-m3/resolve/$BGE_M3_REVISION/tokenizer.json" \
         models/bge-m3/tokenizer.json
