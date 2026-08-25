#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out_dir="$project_root/kmp/src/jvmMain/kotlin"

cd "$project_root/harness"

cargo build --release

cargo run \
  --features cli \
  --bin uniffi-bindgen \
  -- generate \
  --library target/release/libharness.dylib \
  --language kotlin \
  --out-dir "$out_dir"
