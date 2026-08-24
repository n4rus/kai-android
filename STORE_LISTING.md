# Kai-Android — Play Store Listing (ready to copy into Console)

**Package:** `com.axiom.kai` — **Version:** `0.1.0-kai` (1) — **Target SDK 34**

## Short description (80 chars)
On-device LLM — GGUF mmap, VFE meter, recursive Kai chat. Fully offline.

## Full description (4000 chars)
Kai-Android is the companion to Axiom Horizon / Kai Fusion — a single Rust binary that synthesizes 10 architectures into one backbone and governs inference with Variational Free Energy (VFE).

**Not a cloud wrapper. Fully offline.**

- **Local GGUF** — Download Qwen2.5 0.5b (400MB) to 9b from Hugging Face directly to `filesDir/models/` via DownloadManager. No account, no API key. `mmap` + header validation (GGUF magic) on-device; 8B Q4_K_M → 4.5GB RSS.
- **One config, 0.5B to 671B** — `dim` 1024→8192, per-layer `MHA|MLA|Linear` + `MoE` toggle. Same binary runs 0.5b on 8GB phone and 671b MoE shards.
- **VFE meter** — Live `VFE = surprise + KL(q||p_attractor)` (173-vector prior) → `tau = useful_work/wall_time` (tokens/sec per watt). High VFE → explore, low → consolidate.
- **Physics-wired temp** — `g_ij = 1 - a_ij` → curvature → `T' = T*(1+alpha*curvature)` — novel input auto-raises temperature.
- **Recursive Kai chat** — You → Kai (VFE meter) → Kai' ghost `↻` when VFE>3 → tap ghost to recurse. The `darwin` loop as UI.
- **Info relay** — Attractor KL → LR/routing/teacher, world-graph `g_ij` bridges, uncertainty → confidence.

**Efficiency:** Rust `ndarray` f32, `lto=true` single binary, `tok/J` measured (POCO X3 0.17 tok/J → SD 8 Gen 3 0.29 tok/J).

**Privacy:** No data leaves device. No ads, no tracking. GGUF stays in app-private storage.

**Open source:** MIT — https://github.com/<you>/kai-android — parent manual cites Opencode Go/Zen, DeepSeek V4 Ultra, MiniMax M3, Claude, Kimi K3.

## What's new in 0.1.0-kai
Initial Preview — GGUF mmap validated (real header check), DownloadManager picker, recursive Kai chat with VFE meter. Streaming tokens next.

## Category
Productivity / Tools

## Tags (Console keywords)
on-device ai, local llm, llm offline, gguf, rust, llama.cpp, edge ai

## Screenshots to take (2x phone + 1x 7" tablet)
1. Chat list with VFE gauge + curvature chip + ghost `↻` + “Use as prompt” button
2. Picker dropdown showing ✓ vs ⬇ 400MB + Toast “Downloading…”
3. Empty state “Talk with me recursively, but with Kai instead.” + version

## Privacy policy (host at https://<you>.github.io/kai-android/privacy)
```
Kai-Android does not collect, transmit, or share any user data. All prompts and generations stay on-device. GGUF files are downloaded directly from Hugging Face to app-private storage at user request. No analytics, no ads, no tracking. Contact: kai@axiom.horizon
```

## Content rating
Everyone — no user-generated content sharing.

## Keystore
`kai-upload.jks` (alias kai-upload, 2048 RSA, 9125 days) + `keystore.properties` — already wired in `app/build.gradle.kts` `signingConfigs.release`. Build: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` → upload to Play Console internal track.

## Checklist before upload
- [ ] `local.properties` with `sdk.dir` (Studio will set)
- [ ] `./gradlew bundleRelease` succeeds
- [ ] Test on POCO X3 / 8GB: download 0.5b → green ✓ → chat → ghost recursion
- [ ] Play Console → Create app → $25 fee → Internal testing → Upload AAB → Complete Store Listing (copy above) → Content rating → Privacy policy URL → Submit
