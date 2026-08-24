#!/bin/bash
# Bench: VFE / tok/J on device (after dev.sh)
set -e
echo "=== Kai bench ==="
adb shell "cat /proc/meminfo | head -5" 2>&1 | head -5
echo "APK size:" && ls -lh app/build/outputs/apk/debug/app-debug.apk 2>&1 | head -2
echo "Rust so:" && ls -lh rust-bridge/target/aarch64-linux-android/release/libkai_bridge.so 2>&1 | head -2
echo "Run: adb shell am start -n com.axiom.kai/.MainActivity"
echo "Then: picker → qwen2.5:0.5b → download → VFE card should be green ✓"
