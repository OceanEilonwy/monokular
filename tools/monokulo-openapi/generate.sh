#!/usr/bin/env bash
# Writes Monokulo's OpenAPI document at one commit to a file, for the
# Android client's contract test. Monokulo generates it from its code and
# real exchanges against an in-process test engine (`cargo xtask api
# openapi`) and never commits it, so neither does this repo.
#
#   generate.sh SHA OUT_FILE CHECKOUT_DIR
#
# CHECKOUT_DIR keeps the fetched source and its cargo target between runs.
# Needs only Rust: rustup installs the toolchain Monokulo's
# rust-toolchain.toml names by itself. MONOKULO_REPO picks where to fetch
# from (a local clone is quicker); CARGO_TARGET_DIR, as ever, where cargo
# builds.
set -euo pipefail

sha=$1 out=$2 dir=$3
repo=${MONOKULO_REPO:-https://github.com/OceanEilonwy/monokulo.git}

mkdir -p "$dir"
cd "$dir"
[ -d .git ] || git init -q
if [ "$(git rev-parse -q --verify HEAD 2>/dev/null)" != "$sha" ]; then
    git fetch -q --depth 1 "$repo" "$sha"
    git checkout -q --force FETCH_HEAD
fi

# Debug info is most of a build's size and isn't needed here.
export CARGO_PROFILE_DEV_DEBUG=0
cargo xtask api openapi --out "$out"
