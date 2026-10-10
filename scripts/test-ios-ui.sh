#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Own a fresh simulator; never erase or reuse the user's device/data.
runtime="$(xcrun simctl list runtimes --json | python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if r.get("isAvailable") and ".iOS-" in r["identifier"]]
if not runtimes: raise SystemExit("No available iOS Simulator runtime")
print(max(runtimes, key=lambda r: tuple(map(int, r["version"].split("."))))["identifier"])
')"
simulator="$(xcrun simctl create "Companion UI acceptance" com.apple.CoreSimulator.SimDeviceType.iPhone-16 "$runtime")"
cleanup() {
  xcrun simctl shutdown "$simulator" >/dev/null 2>&1 || true
  xcrun simctl delete "$simulator" >/dev/null 2>&1 || true
}
trap cleanup EXIT
xcrun simctl boot "$simulator"
xcrun simctl bootstatus "$simulator" -b
build_options=()
if [[ -n "${COMPANION_IOS_PACKAGES:-}" ]]; then
  build_options+=(-clonedSourcePackagesDirPath "$COMPANION_IOS_PACKAGES" -skipPackageUpdates)
fi
if [[ -n "${COMPANION_IOS_DERIVED_DATA:-}" ]]; then
  build_options+=(-derivedDataPath "$COMPANION_IOS_DERIVED_DATA")
fi
xcodebuild -project "$project_root/ios/OpenAICompanion.xcodeproj" -scheme OpenAICompanion \
  -parallel-testing-enabled NO -maximum-test-execution-time-allowance 120 -test-timeouts-enabled YES \
  -configuration Debug -destination "platform=iOS Simulator,id=$simulator" CODE_SIGNING_ALLOWED=NO \
  "${build_options[@]}" \
  -only-testing:OpenAICompanionUITests/OpenAICompanionUITests/testCrossDeviceConfigurationHelp \
  -only-testing:OpenAICompanionUITests/OpenAICompanionUITests/testMemoryClearConfirmationCanBeCancelledWithoutDeletingData \
  -only-testing:OpenAICompanionUITests/OpenAICompanionUITests/testSettingsNavigationAndDraftPreservation test
