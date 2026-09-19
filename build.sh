#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then
  echo 'Set ANDROID_SDK_ROOT to your Android SDK directory.' >&2
  exit 1
fi
build_tools="$sdk/build-tools/36.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
for tool in javac java jar; do command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }; done
[[ -f "$android_jar" && -x "$build_tools/d8" ]] || { echo 'Install Android SDK platform 36 and build-tools 36.0.0.' >&2; exit 1; }
mkdir -p build/classes build/dex
# 单测统一交给 runtests.sh —— 那里集中维护"被测类清单"，
# 并且在 -cp 上挂了 android.jar（纯逻辑类之间会互相引用，例如
# Memo/ReaderCmd 复用 VoicePager.normalize）。这里再维护一份必然会漂。
bash runtests.sh
javac -encoding UTF-8 -source 8 -target 8 -cp "$android_jar" -d build/classes src/com/turboio/addon/*.java
jar cf build/turboio-addon.jar -C build/classes .
"$build_tools/d8" --lib "$android_jar" --min-api 29 --output build/dex build/turboio-addon.jar
echo 'Built build/dex/classes.dex (original addon only; no API keys).'
