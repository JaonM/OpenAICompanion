#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checkout="${1:?Usage: test-ios-mlx-parser.sh /path/to/mlx-swift-lm}"
test_directory="$(mktemp -d)"
trap 'rm -rf "$test_directory"' EXIT
# Test the installed SDK parser on the host without building its Metal model stack.
python3 - "$checkout" "$project_root" "$test_directory" <<'PY'
from pathlib import Path
import sys
checkout, root, output = map(Path, sys.argv[1:])
tool = checkout / 'Libraries/MLXLMCommon/Tool'
files = ['Tool.swift', 'Value.swift', 'ToolCall.swift', 'ToolParameter.swift',
         'ToolSchemaValidator.swift', 'ToolArgumentNormalization.swift',
         'Parsers/ParserUtilities.swift', 'Parsers/JSONPrefixScanner.swift',
         'Parsers/StructuredTextScanner.swift', 'Parsers/PythonLiteralParser.swift',
         'Parsers/JSONToolCallParser.swift', 'Parsers/QwenXMLPayloadScanner.swift',
         'Parsers/Qwen35ToolCallParser.swift']
# These two shared declarations live beside unrelated token/GPU adapters.
source = (tool / 'ToolCallFormat.swift').read_text().split('// MARK: - ToolCallFormat Enum')[0]
source += 'struct JSONLeadingObjectScanner {' + (tool / 'ToolCallProcessor.swift').read_text().split('struct JSONLeadingObjectScanner {', 1)[1]
source += '\n'.join((tool / name).read_text() for name in files)
source += (root / 'ios/Tests/MLXParserTests.swift').read_text()
(output / 'main.swift').write_text(source)
PY
swift -package-name CompanionAcceptance -module-cache-path "$test_directory/cache" "$test_directory/main.swift"
