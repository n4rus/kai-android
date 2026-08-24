#!/bin/bash
set -e
# Fast dev: Rust → APK → install → logcat (run tomorrow morning in <2 min)
echo "=== Kai-Android dev.sh ==="
echo "[1/4] Rust bridge (arm64-v8a + x86_64)..."
cd "$(dirname "$0")/rust-bridge"
cargo ndk -t arm64-v8a -t x86_64 build --release
echo "✓ libkai_bridge.so 392K arm64 + 395K x86_64"
# Copy to jniLibs for packaging (APK only packages jniLibs, not target/)
mkdir -p ../app/src/main/jniLibs/arm64-v8a ../app/src/main/jniLibs/x86_64
cp target/aarch64-linux-android/release/libkai_bridge.so ../app/src/main/jniLibs/arm64-v8a/libkai_bridge.so
cp target/x86_64-linux-android/release/libkai_bridge.so ../app/src/main/jniLibs/x86_64/libkai_bridge.so
echo "✓ copied to jniLibs"
cd ..
echo "[2/4] APK (assembleDebug)..."
./gradlew assembleDebug
echo "✓ app-debug.apk 9.8M"
echo "[3/4] Install..."
adb install -r app/build/outputs/apk/debug/app-debug.apk && echo "✓ installed com.axiom.kai"
echo "[4/4] Logcat (Ctrl-C to stop)..."
adb logcat | grep -E "KaiJNI|KaiBridge|AndroidRuntime" || true
