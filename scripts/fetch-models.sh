#!/usr/bin/env bash
# Fetches models the full editions use, at build time (they are binaries, so they are never committed):
# - MediaPipe Hand Landmarker (Apache-2.0), for camera-assisted Echo calibration.
# - Cactus Needle's engine, via fetch-needle.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/src/vision/assets
out=app/src/vision/assets/hand_landmarker.task
[ -s "$out" ] || curl -fsSL "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task" -o "$out"
echo "hand model: $(du -h "$out" | cut -f1)"
# - MediaPipe Gesture Recognizer (Apache-2.0): hand signs for Air control; it carries its own hand landmarker.
g=app/src/vision/assets/gesture_recognizer.task
[ -s "$g" ] || curl -fsSL "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task" -o "$g"
echo "gesture model: $(du -h "$g" | cut -f1)"
# - MediaPipe Face Landmarker (Apache-2.0), with blendshapes: face expressions (eyebrows, smile, wink...) for Air control.
fm=app/src/vision/assets/face_landmarker.task
[ -s "$fm" ] || curl -fsSL "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task" -o "$fm"
echo "face model: $(du -h "$fm" | cut -f1)"
./scripts/fetch-needle.sh
