#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
vendor="$project_root/ios/Vendor/llama.cpp"
version="v0.4.1"

if [[ ! -e "$vendor" ]]; then
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

if [[ -d "$vendor/.git" ]]; then
  actual="$(git -C "$vendor" describe --tags --exact-match HEAD 2>/dev/null || true)"
else
  actual="$(cat "$vendor/.source-version" 2>/dev/null || true)"
fi
if [[ "$actual" != "$version" ]]; then
  echo "llama.cpp requires $version, found ${actual:-an unmarked checkout}." >&2
  exit 1
fi
echo "$vendor"
