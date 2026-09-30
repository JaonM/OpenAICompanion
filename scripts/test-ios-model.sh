#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
model="${1:-}"
device="${2:-}"
if [[ ! -f "$model" || ! "$model" == *.gguf || -z "$device" ]]; then
    echo "用法：$0 <本地模型.gguf> <已启动模拟器的 UDID>" >&2
    exit 2
fi

app="$repo_root/ios/build/Debug-iphonesimulator/OpenAICompanion.app"
if [[ ! -d "$app" ]]; then
    echo "请先运行 ./scripts/ios-app-check.sh 构建模拟器 App。" >&2
    exit 1
fi

xcrun simctl install "$device" "$app"
container="$(xcrun simctl get_app_container "$device" com.openai.companion.ios data)"
models="$container/Library/Application Support/OpenAICompanion/Models"
mkdir -p "$models"
cp "$model" "$models/test-smollm2.gguf"

xcodebuild -project "$repo_root/ios/OpenAICompanion.xcodeproj" \
    -scheme OpenAICompanion -configuration Debug \
    -destination "platform=iOS Simulator,id=$device" \
    -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO \
    '-only-testing:OpenAICompanionUITests/OpenAICompanionUITests/testLocalModelGeneratesReply' test
