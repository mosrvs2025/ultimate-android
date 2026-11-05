#!/usr/bin/env bash
# Fetches Cactus Needle's prebuilt Android engine (Apache-2.0) into app/src/main/cpp/needle/<abi>/, where CMake links it.
# The engine is a binary, so it is downloaded at build time rather than committed. Without it the app still builds:
# the bridge compiles as a stub and Local AI says the engine isn't included.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="https://huggingface.co/Cactus-Compute/needle3/resolve/main"
for pair in "arm64-v8a:android-arm64" "armeabi-v7a:android-armv7"; do
  abi="${pair%%:*}"; folder="${pair##*:}"
  out="app/src/main/cpp/needle/$abi"
  mkdir -p "$out"
  for f in libneedle.a needle.h; do
    [ -s "$out/$f" ] || curl -fsSL "$BASE/$folder/$f" -o "$out/$f"
  done
  echo "needle engine for $abi: $(du -h "$out/libneedle.a" | cut -f1)"
done
