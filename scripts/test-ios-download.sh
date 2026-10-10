#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_directory="$(mktemp -d)"
trap 'rm -rf "$test_directory"' EXIT
python3 - "$project_root" "$test_directory" <<'PY'
from pathlib import Path
import sys
root, output = map(Path, sys.argv[1:])
source = (root / 'ios/OpenAICompanion/LocalMLXBridge.swift').read_text()
start = source.index('// Owns one pinned artifact transfer')
end = source.index('private func downloadMLXModel(', start)
gguf = source[source.index('@_cdecl("oc_model_download")'):]
tests = (root / 'ios/Tests/ModelDownloadTests.swift').read_text()
(output / 'main.swift').write_text('import Foundation\nimport CryptoKit\nprivate enum MLXFailure: Error { case message(String) }\n' + source[start:end] + gguf + tests)
PY
swift -swift-version 6 -module-cache-path "$test_directory/cache" "$test_directory/main.swift"
