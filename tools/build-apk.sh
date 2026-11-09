#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
./gradlew :app:assembleDebug --no-daemon
mkdir -p releases
cp app/build/outputs/apk/debug/app-debug.apk releases/InnerRecord-Android-v1.0.0-debug.apk
printf '%s\n' "$project_dir/releases/InnerRecord-Android-v1.0.0-debug.apk"
