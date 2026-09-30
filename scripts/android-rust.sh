#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk_root="${ANDROID_NDK_HOME:-}"
if [[ -z "$ndk_root" && -n "$sdk_root" ]]; then
  ndk_root="$(find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -1)"
fi
if [[ ! -d "$ndk_root/toolchains/llvm/prebuilt" ]]; then
  echo "Android NDK is required. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
  exit 1
fi
if ! command -v cargo >/dev/null || ! command -v rustup >/dev/null; then
  echo "Install Rust and rustup before building the Android native library." >&2
  exit 1
fi

case "$(uname -s):$(uname -m)" in
  Darwin:arm64) ndk_host=darwin-x86_64 ;;
  Darwin:x86_64) ndk_host=darwin-x86_64 ;;
  Linux:x86_64) ndk_host=linux-x86_64 ;;
  Linux:aarch64) ndk_host=linux-x86_64 ;;
  *) echo "Unsupported NDK host" >&2; exit 1 ;;
esac
toolchain="$ndk_root/toolchains/llvm/prebuilt/$ndk_host/bin"
if [[ ! -d "$toolchain" ]]; then
  echo "NDK toolchain not found: $toolchain" >&2
  exit 1
fi

build_target() {
  local rust_target="$1" abi="$2" clang_prefix="$3"
  if ! rustup target list --installed | grep -qx "$rust_target"; then
    echo "Missing Rust target. Run: rustup target add $rust_target" >&2
    exit 1
  fi
  local linker="$toolchain/${clang_prefix}26-clang"
  if [[ ! -x "$linker" ]]; then
    echo "NDK linker not found: $linker" >&2
    exit 1
  fi
  local target_env="${rust_target//-/_}"
  local target_env_upper
  target_env_upper="$(printf '%s' "$target_env" | tr '[:lower:]' '[:upper:]')"
  export "CC_${target_env}=$linker"
  export "AR_${target_env}=$toolchain/llvm-ar"
  export "CARGO_TARGET_${target_env_upper}_LINKER=$linker"
  cargo build --manifest-path "$project_root/harness/Cargo.toml" --release --target "$rust_target"
  local destination="$project_root/kmp/androidApp/src/main/jniLibs/$abi"
  mkdir -p "$destination"
  cp "$project_root/harness/target/$rust_target/release/libharness.so" "$destination/libharness.so"
}

build_target aarch64-linux-android arm64-v8a aarch64-linux-android
build_target x86_64-linux-android x86_64 x86_64-linux-android
