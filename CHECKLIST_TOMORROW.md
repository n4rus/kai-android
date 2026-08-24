# Checklist — Tomorrow Morning Dev Speed (Execute Tonight)

**Goal:** Be able to `cargo ndk && ./gradlew assembleDebug && adb install` in <2 min tomorrow, with all blockers pre-cleared.

**Repos:** `kai-android` (public, 1.6M) + `axiom_horizon` (private, lean 500MB)

---

## Tonight (Automated — I do now)

- [x] `kai-android` scaffold + recursive chat + real GGUF mmap (0.3.0) + download picker + VFE meter
- [x] Gradle 8.2 wrapper fixed (`./gradlew --version` passes)
- [x] `kai-upload.jks` + `keystore.properties` + `signingConfigs.release` + `STORE_LISTING.md`
- [x] `GITHUB_TAGS.md` on both repos (12 topics each) for discovery
- [ ] `local.properties` with `sdk.dir` (auto-create)
- [ ] Android SDK `sdkmanager` install (if network allows)
- [ ] `cargo ndk -t arm64-v8a build --release` for `rust-bridge` (produces `libkai_bridge.so`)
- [ ] `axiom_horizon` `cargo test` (49 tests, 0 warnings) — verify fitness loop is green
- [ ] Fast dev scripts: `dev.sh` (one-cmd build+install), `bench.sh` (VFE bench), `ci.yml` stub
- [ ] Commit all + push-ready (no 40GB history in kai-android)

---

## Tomorrow Morning (You — 10 min)

- [ ] **1. Pull & verify:** `cd kai-android && git pull && ./gradlew --version && cargo check`
- [ ] **2. Device:** `adb devices` → POCO X3 connected, `adb install` → open `Kai-Android`
- [ ] **3. First GGUF:** Picker → `qwen2.5:0.5b` → `⬇` → wait DownloadManager → `✓` green → tap to load → VFE card turns green `✓`
- [ ] **4. Chat test:** Type “explain VFE in one sentence” → Kai answers with `VFE+T'` → ghost `↻` appears → tap ghost → recurse
- [ ] **5. Axiom:** `cd ../axiom_horizon/rust && cargo test` → 49 green → `kai darwin evolve --generations 1` smoke

---

## Play Store (When ready, 3-5 days internal, 2.5 weeks production)

- [ ] `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
- [ ] Play Console → Create app `com.axiom.kai` → $25 → Internal testing → Upload AAB → Store listing (copy `STORE_LISTING.md`) → Privacy URL → Submit
- [ ] Screenshots: chat + VFE + picker (use `adb screencap`)

---

## GitHub Discovery (Do tomorrow, 5 min)

- [ ] `cd kai-android && gh repo create kai-android --public --source=. --push` (or manual `git remote add origin`)
- [ ] `gh repo edit kai-android --add-topic rust --add-topic android --add-topic llm --add-topic gguf --add-topic llama-cpp --add-topic on-device-ai --add-topic edge-ai --add-topic local-llm --add-topic jetpack-compose --add-topic vfe --add-topic active-inference`
- [ ] Same for `axiom_horizon` with its 12 topics
- [ ] Pin `kai-android` to profile, link in `axiom_horizon/README.md`

---

## Fast Dev Scripts (to be created tonight)

- `dev.sh`: `cargo ndk && ./gradlew assembleDebug && adb install -r app-debug.apk && adb logcat | grep KaiJNI`
- `bench.sh`: `kai bench --baseline g0 --current gX` equivalent for Android (VFE/tok/J)
- `local.properties` auto-generated

---

## Blocker Clearance Log

- Gradle wrapper: fixed via `/tmp/minimal` (62K jar + 8.4K gradlew) — passes
- SDK: `local.properties` will point to `/home/l/Android/Sdk` or `/tmp/fake-sdk` fallback; real SDK needs `sdkmanager --licenses`
- Rust: `cargo ndk` needs `aarch64-linux-android` target — `rustup target add` tonight

---

*Last updated: 2026-08-24 03:10 BRT — execute `dev.sh` tomorrow and you’re coding in <2 min.*
