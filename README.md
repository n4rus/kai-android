# Kai-Android — Adaptive On-Device LLM Hub

> Companion to [Axiom Horizon / Kai Fusion](https://github.com/<you>/axiom_horizon) — runs local GGUF inference via Rust (`kai-bridge`) → ARM64 JNI → Kotlin Compose. Multi-model picker + live VFE meter + g_ij novelty surface. Fully offline.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Rust](https://img.shields.io/badge/rust-%23000000.svg?style=flat&logo=rust&logoColor=white)](rust-bridge/)
[![Android](https://img.shields.io/badge/Android-API26%2B-green)](app/)

This is the **clean public repo** for the Android app. The parent `axiom_horizon` stays private/local until store-ready (40GB history stripped here). See `MANUAL.md` in parent for full VFE/physics derivation.

## What It Does (30s)

- Loads GGUF from `~/.ollama/models` style blobs sideloaded to `filesDir/models/` (no download on build)
- Same `KaiFusionConfig` as desktop (`dim`, `MHA|MLA|Linear`, `MoE`) — swap `qwen2.5:0.5b` ↔ `llama3:8b` mid-chat
- Live **VFE meter** (`surprise + KL vs 173-vector attractor`) + **adaptive temp** `T' = T*(1+alpha*curvature)` + **g_ij novelty banner**

## Build (Linux)

```bash
cargo install cargo-ndk && rustup target add aarch64-linux-android
cd rust-bridge && cargo ndk -t arm64-v8a build --release
cd .. && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

See `android/README.md` in parent for full slice plan (2 weeks → Play Store demo).

## References

Parent manual cites Opencode Go/Zen, DeepSeek V4 Ultra, MiniMax M3, Claude, Kimi K3 — see `MANUAL.md §20` there.

## License

MIT — AxiomTree
