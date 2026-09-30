#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
vendor="$repo_root/ios/Vendor/llama.cpp"
version="v0.4.1"

if ! xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
    echo "需要完整 Xcode 和 iOS SDK；请先用 xcode-select 选择 Xcode。" >&2
    exit 1
fi
if [ ! -e "$vendor" ]; then
    if ! command -v cmake >/dev/null; then
        echo "需要 CMake 3.28 或更新版本。" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$vendor")"
    if ! git -c http.version=HTTP/1.1 clone --depth 1 --branch "$version" \
        https://github.com/ggml-org/llama.cpp.git "$vendor"; then
        rm -rf "$vendor"
        archive="$(mktemp)"
        trap 'rm -f "$archive"' EXIT
        curl -fL --retry 3 "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/$version" \
            -o "$archive"
        mkdir -p "$vendor"
        if ! tar -xzf "$archive" --strip-components=1 -C "$vendor"; then
            rm -rf "$vendor"
            exit 1
        fi
        printf '%s\n' "$version" > "$vendor/.source-version"
    fi
fi

if [ -d "$vendor/.git" ]; then
    actual="$(git -C "$vendor" describe --tags --exact-match HEAD 2>/dev/null || true)"
else
    actual="$(cat "$vendor/.source-version" 2>/dev/null || true)"
fi
if [ "$actual" != "$version" ]; then
    echo "llama.cpp 需要 $version，当前是 ${actual:-未标记的提交}。" >&2
    exit 1
fi

if [ -d "$vendor/build-apple/llama.xcframework" ]; then
    echo "llama.cpp XCFramework 已存在：$vendor/build-apple/llama.xcframework"
    exit 0
fi

if ! command -v cmake >/dev/null; then
    echo "需要 CMake 3.28 或更新版本。" >&2
    exit 1
fi

cd "$vendor"
./build-xcframework.sh ios-sim ios-device
test -d "$vendor/build-apple/llama.xcframework"
echo "已生成 $vendor/build-apple/llama.xcframework"
