#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_resources="$project_root/kmp/appResources/macos-arm64"

cargo build --manifest-path "$project_root/harness/Cargo.toml" --release
"$project_root/scripts/generate-uniffi-kotlin.sh"
mkdir -p "$app_resources"
xcrun clang -dynamiclib -fobjc-arc -mmacosx-version-min=11.0 -framework Foundation -framework EventKit -framework AppKit \
  "$project_root/macos/CalendarBridge/CalendarBridge.m" -o "$app_resources/libcompanion_calendar.dylib"
cp "$project_root/harness/target/release/libharness.dylib" "$app_resources/libharness.dylib"

cd "$project_root/kmp"
export COMPANION_CALENDAR_LIBRARY_PATH="$app_resources/libcompanion_calendar.dylib"
export HARNESS_LIBRARY_PATH="$app_resources/libharness.dylib"
gradle_args=(-PkmpJvmToolchain=21)
if [[ -n "${COMPANION_BUILD_DIR:-}" ]]; then
  gradle_args+=("-PcompanionBuildDir=$COMPANION_BUILD_DIR")
fi

case "${1:-run}" in
  run)
    ./gradlew "${gradle_args[@]}" run
    ;;
  package)
    ./gradlew "${gradle_args[@]}" -Pcompose.desktop.packaging.checkJdkVendor=false packageDmg
    ;;
  *)
    echo "Usage: $0 [run|package]" >&2
    exit 2
    ;;
esac
