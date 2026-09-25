#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
platform="${1:-}"
architecture="${2:-}"
output="${3:-}"

if [[ -z "$output" || -z "$architecture" ]]; then
  echo "Usage: $0 <iphoneos|iphonesimulator> '<arm64|x86_64|arm64 x86_64>' <output-lib-path>" >&2
  exit 2
fi

if [[ -x "$HOME/.cargo/bin/cargo" ]]; then
  export PATH="$HOME/.cargo/bin:$PATH"
fi
if ! command -v cargo >/dev/null || ! command -v rustup >/dev/null; then
  echo "Install the Rust toolchain before building the iOS app." >&2
  exit 1
fi
read -r -a architectures <<< "$architecture"
libraries=()
for arch in "${architectures[@]}"; do
  case "$platform:$arch" in
    iphoneos:arm64) rust_target="aarch64-apple-ios" ;;
    iphonesimulator:arm64) rust_target="aarch64-apple-ios-sim" ;;
    iphonesimulator:x86_64) rust_target="x86_64-apple-ios" ;;
    *) echo "Unsupported iOS platform/architecture: $platform/$arch" >&2; exit 2 ;;
  esac
  if ! rustup target list --installed | grep -qx "$rust_target"; then
    echo "Missing Rust target. Run: rustup target add $rust_target" >&2
    exit 1
  fi
  cargo build --manifest-path "$project_root/harness/Cargo.toml" --release --target "$rust_target"
  libraries+=("$project_root/harness/target/$rust_target/release/libharness.a")
done

mkdir -p "$(dirname "$output")"
if [[ "${#libraries[@]}" -eq 1 ]]; then
  cp "${libraries[0]}" "$output"
else
  lipo -create "${libraries[@]}" -output "$output"
fi
