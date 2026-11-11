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
# - MediaPipe Pose Landmarker, lite (Apache-2.0): whole-body tracking for Playground's AR effects.
pm=app/src/vision/assets/pose_landmarker_lite.task
[ -s "$pm" ] || curl -fsSL "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task" -o "$pm"
echo "pose model: $(du -h "$pm" | cut -f1)"
# - MediaPipe Object Detector, EfficientDet-Lite0 int8 (Apache-2.0): finds furniture and household things for Room scan (Ultimate+ only).
mkdir -p app/src/ultimateplus/assets
od=app/src/ultimateplus/assets/efficientdet_lite0.tflite
[ -s "$od" ] || curl -fsSL "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite" -o "$od"
echo "object model: $(du -h "$od" | cut -f1)"
./scripts/fetch-needle.sh
