#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_directory="$(mktemp -d)"
trap 'rm -rf "$test_directory"' EXIT
# Compile the production Foundation adapters without loading Metal or a model.
python3 - "$project_root" "$test_directory" <<'PY'
import pathlib, sys
root, output = map(pathlib.Path, sys.argv[1:])
source = (root / 'ios/OpenAICompanion/LocalMLXBridge.swift').read_text()
start = source.index('private func nativeTools(')
end = source.index('\nprivate enum MLXFailure', start)
tests = (root / 'ios/Tests/MLXValueTests.swift').read_text()
(output / 'main.swift').write_text('import Foundation\nenum ToolCallFormat { case qwen35, xmlFunction, json }\nenum MLXFailure: Error { case message(String) }\n' + source[start:end] + tests)
PY
swift -module-cache-path "$test_directory/cache" "$test_directory/main.swift"
