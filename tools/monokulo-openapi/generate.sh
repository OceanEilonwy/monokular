#!/usr/bin/env bash
# Writes Monokulo's OpenAPI document at one commit to a file, for the
# Android client's contract test. Monokulo generates it from its code and a
# test's real exchanges (`cargo xtask api openapi`) and never commits it, so
# neither does this repo.
#
#   generate.sh SHA OUT_FILE CHECKOUT_DIR
#
# CHECKOUT_DIR keeps the fetched source and its cargo target between runs.
# Needs what building Monokulo needs: Rust nightly with wasm32-unknown-unknown,
# and Node 24 or later. MONOKULO_REPO picks where to fetch from (a local clone is quicker);
# CARGO_TARGET_DIR, as ever, where cargo builds.
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

# Monokulo's build script bundles its POS app, from npm's lockfile (Node 24+).
npm ci --no-audit --no-fund --loglevel=error

# Debug info is most of a test build's size and isn't needed here.
export CARGO_PROFILE_DEV_DEBUG=0 CARGO_PROFILE_TEST_DEBUG=0
cargo xtask api openapi --out "$out"
