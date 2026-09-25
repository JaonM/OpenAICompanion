#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root/harness"
cargo build --release
cargo run --bin uniffi-bindgen --features cli -- \
  generate --library target/release/libharness.dylib --language swift \
  --out-dir "$project_root/ios/Generated"
sed -i '' 's/[[:blank:]]*$//' \
  "$project_root/ios/Generated/harness.swift" \
  "$project_root/ios/Generated/harnessFFI.h"
perl -0pi -e 's/\n+\z/\n/' "$project_root/ios/Generated/harnessFFI.h"
cp "$project_root/ios/Generated/harnessFFI.modulemap" \
  "$project_root/ios/Generated/module.modulemap"
