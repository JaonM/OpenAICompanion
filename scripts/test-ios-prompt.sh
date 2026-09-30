#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_directory="$(mktemp -d)"
trap 'rm -f "$test_directory/LocalLlamaPromptTests"; rmdir "$test_directory"' EXIT

clang -fobjc-arc -Wall -Wextra -Werror -framework Foundation \
  -I "$project_root/ios/OpenAICompanion" \
  "$project_root/ios/OpenAICompanion/LocalLlamaPrompt.m" \
  "$project_root/ios/Tests/LocalLlamaPromptTests.m" \
  -o "$test_directory/LocalLlamaPromptTests"
"$test_directory/LocalLlamaPromptTests"
