#!/usr/bin/env bash
# Fetches Cactus Needle's prebuilt Android engine (Apache-2.0) into app/src/main/cpp/needle/<abi>/, where CMake links it.
# The engine is a binary, so it is downloaded at build time rather than committed. Without it the app still builds:
# the bridge compiles as a stub and Local AI says the engine isn't included.
#
# Pinned to one revision of the Hugging Face repo, with checksums: the repo's main branch dropped the Android binaries
# on 2026-11-05, and pinning keeps every build on the exact engine that was tested.
set -euo pipefail
cd "$(dirname "$0")/.."
REV=d6c7d6a2e9655505e0f8e23339356dd1705900eb
BASE="https://huggingface.co/Cactus-Compute/needle3/resolve/$REV"
sha() {
  case "$1" in
    android-arm64/libneedle.a) echo 7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0 ;;
    android-armv7/libneedle.a) echo 9f559a287b201c213e5c753d3cd1076a1849c73f94b445c85d11e4969422a135 ;;
    */needle.h) echo a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99 ;;
  esac
}
for pair in "arm64-v8a:android-arm64" "armeabi-v7a:android-armv7"; do
  abi="${pair%%:*}"; folder="${pair##*:}"
  out="app/src/main/cpp/needle/$abi"
  mkdir -p "$out"
  for f in libneedle.a needle.h; do
    [ -s "$out/$f" ] || curl -fsSL "$BASE/$folder/$f" -o "$out/$f"
    echo "$(sha "$folder/$f")  $out/$f" | sha256sum -c --quiet - || { echo "Checksum mismatch for $out/$f"; rm -f "$out/$f"; exit 1; }
  done
  echo "needle engine for $abi: $(du -h "$out/libneedle.a" | cut -f1)"
done
