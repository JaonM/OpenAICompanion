#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v xcodebuild >/dev/null 2>&1 ||
   ! xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
  echo "需要完整 Xcode 和 iOS Simulator SDK；请先用 xcode-select 选择 Xcode。" >&2
  exit 1
fi
if ! command -v rustup >/dev/null 2>&1; then
  echo "需要 Rust 工具链。" >&2
  exit 1
fi

case "$(uname -m)" in
  arm64) simulator_target="aarch64-apple-ios-sim"; host_target="aarch64-apple-darwin" ;;
  x86_64) simulator_target="x86_64-apple-ios"; host_target="x86_64-apple-darwin" ;;
  *) echo "不支持的 Mac 架构。" >&2; exit 1 ;;
esac
if ! rustup target list --installed | grep -qx "$simulator_target"; then
  echo "缺少 Rust 目标；请运行：rustup target add $simulator_target" >&2
  exit 1
fi
if ! rustup target list --installed | grep -qx "$host_target"; then
  echo "UniFFI 生成需要 Rust 目标；请运行：rustup target add $host_target" >&2
  exit 1
fi

"$project_root/scripts/ios-llama.sh"
xcodebuild \
  -project "$project_root/ios/OpenAICompanion.xcodeproj" \
  -target OpenAICompanion \
  -configuration Debug \
  -sdk iphonesimulator \
  "ARCHS=$(uname -m)" \
  ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=NO \
  build
