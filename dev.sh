#!/bin/bash
set -e
# Fast dev: Rust → APK → install → logcat (run tomorrow morning in <2 min)
echo "=== Kai-Android dev.sh ==="
echo "[1/4] Rust bridge (arm64-v8a)..."
cd "$(dirname "$0")/rust-bridge"
cargo ndk -t arm64-v8a build --release
echo "✓ libkai_bridge.so 392K"
cd ..
echo "[2/4] APK (assembleDebug)..."
./gradlew assembleDebug
echo "✓ app-debug.apk 8.3M"
echo "[3/4] Install..."
adb install -r app/build/outputs/apk/debug/app-debug.apk && echo "✓ installed com.axiom.kai"
echo "[4/4] Logcat (Ctrl-C to stop)..."
adb logcat | grep -E "KaiJNI|KaiBridge|AndroidRuntime" || true
