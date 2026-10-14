#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
simulator="${1:?Usage: test-ios-device-tools.sh <booted simulator UDID>}"
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
task_dir="$(mktemp -d /tmp/companion-device-tests.XXXXXX)"
trap 'rm -rf "$task_dir"' EXIT
xcrun --sdk iphonesimulator swiftc \
    -target "$(uname -m)-apple-ios17.0-simulator" -sdk "$sdk" \
    -module-cache-path "$task_dir/modules" \
    "$project_root"/ios/OpenAICompanion/Device*.swift \
    "$project_root/ios/Tests/DeviceToolsTests.swift" -o "$task_dir/device-tests"
xcrun simctl spawn "$simulator" "$task_dir/device-tests"
