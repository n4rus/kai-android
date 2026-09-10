# Kai — Offline AI Companion (Android)

> **Private • Offline • Encrypted • No monthly fees**
> Runs `llama.cpp` via Rust (`kai-bridge`) → `ARM64/x86_64 JNI` → `Kotlin Compose`. Multi-model picker + live VFE meter + persistent memory + agent tools. Fully offline-first, with optional encrypted Kai PC bridge.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android)](app/)
[![Rust](https://img.shields.io/badge/Rust-000000?logo=rust)](rust-bridge/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin)](app/src/main/java/com/axiom/kai/)
[![Play Store](https://img.shields.io/badge/Play_Store-1.0.2--bg--service-41454A?logo=googleplay)](STORE_LISTING.md)

**Package:** `com.axiom.kai` • **Current:** `1.0.2-bg-service` (`versionCode 5`) • **AAB 28M** • **Min SDK 26** • **NDK 26.1** • **Compose Material3**

This is the **clean public repo** for the Android app. The parent `axiom_horizon` (40GB history) stays private until store-ready.

---

## 30s Demo

```
1. Install → open Kai (black launch)
2. Model picker (top) → download ★ Recommended 3B (llama3.2:3b / coder:3b / gemma2:2b) — 5s ad stripe (free) then auto-hides
3. Ask anything: "explain VFE" → instant direct answer (no 127s). VFE/Curvature cards → tap to hide, 👁 Show to restore
4. Lock phone or switch app while Kai thinks → notification "Kai is thinking…" → answer continues in background
5. Say "remember I like Rust" → later recall, encrypted history, export to Download
```

---

## Features

### Offline LLM
- **GGUF via `rust-bridge/src/lib.rs:66` `kai_load_gguf_slot`** — `llama.cpp` `LlamaModelParams::with_n_gpu_layers(99)` Vulkan (fallback CPU), `n_ctx 1024`, `new_tokens 192`, `threads 2-6`, `CANCEL_FLAGS` per slot
- **Dual slots** `SLOTS[0]=FAST / [1]=DEEP` — `ModelRouter` picks `coder:3b` for code, `gemma2/llama3.2` for deep, `llama3.2:3b` default (`ModelManager.kt:285` `FAST_TAG`)
- **Streaming** `StreamingGenerate.kt:8` — Rust generates full, Kotlin batches `16` tokens `16ms` to avoid recomposition storm
- **Catalog** `ModelManager.kt:20` — `kai-pc:live` (remote), `gemini:flash/pro`, `deepseek/gpt/qwen/claude` (API keys), local `0.5B/3B/7B/8B/9B`, `axiom:3b` + `nomic-embed:768` (Pro early access)

### Physics — VFE / Curvature / g_ij
- `VFE = surprise + KL` `Soul.kt` + `kai_calculate_vfe` `lib.rs:126`
- `T' = T×(1+α·curvature)` `lib.rs:132` — high VFE → explore, low → consolidate
- Meter `MainActivity.kt:502` `VFE` `LinearProgress` + `g→T'` card, tap `vfeCollapsed` to hide (not turn off), `showPhysics` persisted `kai_prefs`

### Memory & Knowledge
- `MemoryDb.kt:254` `TextEmbed` — `768-d nomic` via `KaiBridge.embed` reflection if `nomic-embed GGUF` downloaded, else `128-d hash` fallback, `cosineJson` mismatch handling
- `KnowledgeDb.kt:18` `kai_knowledge.db` — `/ingest <url>` → fetch+store, `/recall <q>` + auto-recall each turn
- `MemoryEngine` `remember / my name is` → `extractFact` → `kai.db memories`

### Agent & Tools (`Tools.kt:10`)
**14 tools, LLM can call itself** `Tools.kt:407` `FUNCTION CALLING` (`/search <q>` → system executes → follow-up generation `ChatViewModel.kt:674`):
`browse`, `search` (DuckDuckGo), `ingest/recall`, `pdf` (`pdfbox-android`), `img` (local EXIF, full vision via Kai PC), `ls/cat/write`, `shell` (`> cmd`), `apps/openapp/url`, `alarm/event`, `battery`, `note`, `account/encryption`, `export` (`⚙️→💾`), `Kai PC` (`KaiPcClient.kt`)

### Account & Security
- `AccountManager.kt:80` `createAccount` — `username/email/recoveryEmail + password/confirm` → `SHA-256+salt` + `PBKDF2 AES/CBC ENC:` + `encKey`, `strongPasswordError` `8+1 caps+1 special`
- `GoogleAuthManager.kt:20` `GoogleSignIn` `requestEmail` (+ `requestIdToken` for Firebase), `FirebaseHelper.kt:13` `isConfigured` check, `createAccountAndSendVerification` real email when `google-services.json` is real (`kai-android-c291c`), else local inbox + Kai PC SMTP `KaiPcClient.sendEmail`
- `deleteAccount` `AccountManager.kt:213` with confirmation dialog `MainActivity.kt:615`
- `login` + `Google Sign-In` button `MainActivity.kt:831` `OutlinedButton G #4285F4`

### Billing & Ads
- `BillingManager.kt:10` `PRO kai_pro $4.99 one-time` (non-consumable, `hasPro`) + legacy `v2/v3 30/90d` also grant pro
- `ModelManager.kt:11` `requiresPro` (`axiom:3b`, `nomic` early) — picker `MainActivity.kt:476` locks for free
- `AdStripe` `MainActivity.kt:99` `AdMob 23.0.0` `ca-app-pub-394...~3347` test banner `6300978111` `50dp` between `VFE` and chat, `5s` `delay` auto-hide, `hasPro` skip, `MobileAds.initialize`
- Free: all `3B` free, Pro removes ads + early releases

### Background Continuation
- `KaiForegroundService.kt:1` `FOREGROUND_SERVICE_DATA_SYNC` + `PARTIAL_WAKE_LOCK 10min`, `NotificationChannel kai_generation`, `Start` on `_isGenerating=true` `ChatViewModel.kt:393` `startBg`, `Stop` on `false`, `POST_NOTIFICATIONS` request `MainActivity.kt:204`, survives lock/app switch (don't swipe-kill)

### UI
- `Kai` label `strings.xml:3` `AndroidManifest.xml:10`, `Theme.Kai.DarkLaunch` `themes.xml` `windowBackground #FF000000`
- `KaiTheme` `ui/theme/Theme.kt:13` `Dark NearlyBlack #141414` + `Light`, `ThemeVersion` live
- `Lang.kt:1` `en/pt` `version`, `ChatViewModel` bilingual direct `VFE` fast-path `ChatViewModel.kt:498` `Regex \bvfe\b|\btau\b`
- `MainActivity.kt:629` `ChatInputBar` isolated, `LazyColumn` `rememberLazyListState` scroll to last, `SelectionContainer`, `Top tabs` `Chat/Terminal`, `History ☰`, `Model picker`, `Download progress`, `AdStripe`

---

## Architecture

```
User → Compose (MainActivity.kt:127 KaiScreen)
       ├─ ChatViewModel.kt (StateFlow messages/model/isGenerating, Room kai.db + backup, send pipeline)
       ├─ StreamingGenerate.kt → KaiBridge.kt → rust-bridge/src/lib.rs (JNI) → llama.cpp (Vulkan/CPU)
       ├─ MemoryDb.kt / KnowledgeDb.kt / Skills.kt / AgentLoop.kt / Tools.kt
       ├─ AccountManager.kt / GoogleAuthManager.kt / FirebaseHelper.kt / BillingManager.kt / KaiPcClient.kt
       └─ KaiForegroundService.kt (background)
```

**Key files:** `app/src/main/java/com/axiom/kai/*` (`22` `kt`, `6045` lines) + `rust-bridge/src/lib.rs:531`

---

## Build (Linux)

**Prereqs:** `Android Studio` + `NDK 26.1` + `CMake 3.22.1` + `Rust stable` + `cargo-ndk`

```bash
# 1. Rust (outside opencode env — unset OPENCODE_* to avoid cargo-ndk panic)
env -u LS_COLORS -u OPENCODE -u OPENCODE_PID -u OPENCODE_CONFIG_CONTENT -u OPENROUTER_API_KEY \
  cargo ndk -t arm64-v8a -t x86_64 -p 26 build --release  # → app/src/main/jniLibs/

# 2. Firebase (for real email — replace placeholder)
# Firebase Console → Add project kai-android → Add Android com.axiom.kai + SHA1 EE:A9:BE:6F:... + SHA256 3E:16:FA:... → download google-services.json → replace app/google-services.json → Enable Auth → Email/Password + Google

# 3. Signing (already has kai-upload.jks)
cat keystore.properties # storeFile=kai-upload.jks storePassword=kaiKai123 keyAlias=kai-upload

# 4. Gradle
./gradlew assembleDebug                    # → app/build/outputs/apk/debug/app-debug.apk 27M
./gradlew bundleRelease                    # → app/build/outputs/bundle/release/app-release.aab 28M (signReleaseBundle)
adb -s RQGYC026B0T install -r app/build/outputs/apk/debug/app-debug.apk
```

**Test device:** `A36 RQGYC026B0T SM-A366E` `files/models/` has `llama3.2-3b 1.8G` etc. Headless check: `adb shell run-as com.axiom.kai ls/cat databases + logcat`.

---

## Project Structure

```
kai-android/
  app/
    src/main/
      AndroidManifest.xml (Kai, DarkLaunch, AdMob, POST_NOTIFICATIONS, ForegroundService)
      java/com/axiom/kai/
        MainActivity.kt (ChatInputBar, AdStripe, KaiScreen, physics, picker)
        ChatViewModel.kt (send, VFE fast-path, streaming, agent, persistence)
        KaiBridge.kt (JNI), StreamingGenerate.kt, KaiForegroundService.kt
        ModelManager.kt (Catalog ★ 3B, Router FAST_TAG llm3.2:3b, delete)
        MemoryDb.kt/KnowledgeDb.kt (128→768 transpose), Skills.kt, AgentLoop.kt
        Tools.kt (14 tools + tryToolFromModel + toolsPrompt), Soul.kt, Lang.kt
        AccountManager.kt (local+Firebase), GoogleAuthManager.kt, FirebaseHelper.kt
        BillingManager.kt (PRO $4.99), KaiPcClient.kt (TLS+token, /send-email)
        ui/theme/Theme.kt
      res/values/{strings.xml, themes.xml} mipmap-anydpi/...
    build.gradle.kts (compileSdk 34, versionCode 5, Firebase BoM 32.7.4, Billing 6.1.0, Ads 23.0.0, Room 2.6.1)
  rust-bridge/src/lib.rs (dual slots, cancel, VFE, render chat, Vulkan n_gpu 99, n_ctx 1024, new_tokens 192)
  app/google-services.json (placeholder → real kai-android-c291c)
  kai-upload.jks + keystore.properties
  PRIVACY_POLICY.md / STORE_LISTING.md / MANUAL.md (this repo)
  README.md / LICENSE (MIT)
```

---

## Usage

See **`MANUAL.md`** for full user manual (install, models, chat, VFE, memory, tools, account, Pro, Kai PC, troubleshooting).

---

## Privacy & Store

- **Privacy Policy:** `PRIVACY_POLICY.md` — host as `https://n4rus.github.io/kai-android/privacy.html` for Play Console
- **Store Listing:** `STORE_LISTING.md` — short/full EN/PT, data safety, graphics (512 icon, 1024×500 feature, screenshots), content rating
- **Data Safety:** Email optional, encrypted in transit/at rest, no sharing except Firebase Auth when configured
- **Encryption:** `STANDARD` exempt (`AES/CBC`, `PBKDF2`, `TLS`)

---

## FAQ

**Why 3B default?** `0.5B` too dumb, `7B` too slow on `A36` (`120s` → `30s` with `1024/192`). `3B Q4_K_M` is sweet spot (`ModelManager.kt:48`).

**Ads?** Free shows `50dp` banner `5s` then hides (`AdStripe`), Pro one-time `$4.99` removes + early `axiom:3b/nomic`.

**Background?** `KaiForegroundService` keeps CPU + notification while thinking, survives lock/switch (don't swipe-kill). `POST_NOTIFICATIONS` granted.

**PC SMTP vs Firebase?** Dev: `tools/kai_pc_server.py --smtp-*` → `KaiPcClient.sendEmail`. Prod: Firebase `sendEmailVerification`/`sendPasswordResetEmail` when `google-services.json` real.

---

## References

Parent `axiom_horizon` cites `Opencode Zen`, `DeepSeek`, `MiniMax M3`, `Claude` — see `PLANNING.md`.

## License

MIT — AxiomTree — `github.com/n4rus/kai-android`
