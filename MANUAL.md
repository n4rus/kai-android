# Kai — User Manual

**Package `com.axiom.kai` • `1.0.2-bg-service` • Android `26+` • Offline-first**

This manual covers **install → daily use → advanced** for Kai on Android (A36 `RQGYC026B0T` tested). For build/dev see `README.md`. For privacy see `PRIVACY_POLICY.md`.

---

## 1. Install

**Play Store (when published):** Search `Kai — Offline AI Companion` → Install.

**Sideload (dev):**
```bash
adb -s RQGYC026B0T install -r app/build/outputs/apk/debug/app-debug.apk # 27M debug
# or bundle → Play Internal testing: app/build/outputs/bundle/release/app-release.aab 28M
```
First launch: black `Theme.Kai.DarkLaunch` → `Max` memory auto-migration (`MIGRATION_1_2` adds `latencyMs`, backs up `kai.db` to `kai.db.backup` never wiped).

**Requirements:** `Android 8+` (API 26), `3GB` free (for `3B`), `INTERNET` for first model download only, `POST_NOTIFICATIONS` grant for background (prompted).

---

## 2. First Run

1. **Permissions:** Allow `Notifications` when prompted (keeps "Kai is thinking…" when locked).
2. **Model:** Top `model` chip → picker shows `★ Recommended` (`llama3.2:3b`, `coder:3b`, `gemma2:2b`) — `0.5B` is `FAST_TAG` but too dumb, `7B` is slow. Free tier has `3B` free; Pro early `axiom:3b` + `nomic-embed`. Tap `⬇` to download (via `DownloadManager` `1.9G`, resume via `.part`, `syncExternalToInternal`, `loadInRust`).
3. **Ad stripe (free):** `50dp` banner between `VFE` and chat, `5s` then auto-hides, `Pro` skips. `AdMob` test `ca-app-pub-394...Σ3347` `6300978111` — will use real before release.
4. **Try:** Suggestions `Hi, I'm Kai` → tap one or type + `Send`. `VFE` meter shows `surprise+KL` + `g→T'`.

---

## 3. Chat

### Input
- `📷` pick image → if `kai-pc:live` sends base64 to PC SigLIP, else local EXIF `Tools.kt:imageInfo`
- `📎` pick file → `Tools.kt:readFile` (`pdfbox-android` for `.pdf`) or sends to PC inbox `kai_phone_inbox/`
- `OutlinedTextField` `maxLines 4` + `Send` (`enabled = input.isNotBlank() && !generating` `MainActivity.kt:121`) — disabled while thinking to avoid freeze
- `Enter` sends, history is `SelectionContainer` copyable, `LazyColumn` auto-scrolls to last `LaunchedEffect(messages.size)`

### Models
- Top bar: `Chat | Terminal` tabs (disabled while generating to avoid freeze), `☰` history, `model` picker, `⚙️` config
- **Auto-router** `ModelRouter.kt:276` — `code → coder:3b`, `deep reasoning (>220 chars or explain/why) → biggest 3B`, `VFE/tau → fast 3B` `ModelManager.kt:287`, else `FAST_TAG llm3.2:3b`
- **Background:** `KaiForegroundService.kt:1` `PARTIAL_WAKE_LOCK` + `NotificationChannel kai_generation` `1701` `Ongoing` `"Kai is thinking…"` `Tap to return` + `Stop` (`kai_cancel` `lib.rs:38`). Start on `_isGenerating=true` `ChatViewModel.kt:393` `startBg`, stop on `false`. **Lock phone or switch app → answer continues** (don't swipe-kill). Unlock → answer there.

### VFE / Curvature / Temp
- Header `MainActivity.kt:502` two `Card`s: `VFE` `LinearProgress v/5` `explore/consolidate` + `ggufLabel`, `Curvature g → T'`
- **Tap to hide:** Row `clickable { vfeCollapsed=true }` `MainActivity.kt:511` — only hides (`showPhysics` stays `true` persisted), not turn off. When hidden, `👁 Show VFE / Curvature` button appears `MainActivity.kt:506`. Menu `⚙️ → ⚡ VFE ON/OFF` still controls persisted `show_physics` `kai_prefs`.
- Per-message footer `MainActivity.kt:614` `model · time · VFE · g · T` `clickable { vfeCollapsed=!vfeCollapsed }` same
- **Fast-path** `ChatViewModel.kt:498` `Regex \bvfe\b|\btau\b` → direct bilingual answer `VFE=surprise+KL, T' = T×(1+α·g)` `~0ms` `direct`, bypasses LLM (`127s` bug fixed), `ModelRouter` also forces fast `3B`

### Streaming & Performance
- `rust-bridge/src/lib.rs:264` `n_ctx 1024` `new_tokens 192` `threads 2-6` `Vulkan n_gpu 99` (fallback CPU) → `~30s` vs old `120s`
- `StreamingGenerate.kt:8` batches `16` tokens `16ms` `delay` to avoid recomposition storm (was `8/24ms` freeze)
- `ChatInputBar` isolated (reads only `input/generating`), `75s` `KaiBridge.cancel` `AgentLoop.kt:89`

---

## 4. Memory & Knowledge

- **Memory:** Say `remember I like Rust` or `my name is Lucas` → `MemoryEngine:130` `extractFact` → `kai.db memories` `768-d` if `nomic` downloaded else `128-d` hash `MemoryDb.kt:254`. `contextBlock` auto-injected each turn.
- **Knowledge:** `Tools.kt` `/ingest https://example.com` → fetch+store via `Knowledge.ingestUrl` into `kai_knowledge.db`, `/recall <q>` → `cosine 768` search, also auto-recall each turn `ChatViewModel.kt:584`
- **Skills:** `Skills.kt:8` keyword+cosine (`pdf`, `search`, `img` etc.)
- **Agent:** `AgentLoop.kt:13` `isAgentTask` (`implement/build/create...`) → decompose `→ write workspace/file → shell → fix` `75s` per step, streams into `onStep`

---

## 5. Tools — Function Calling

Kai is **tool-aware** `Tools.kt:407` `FUNCTION CALLING` — `YOU CAN CALL THEM YOURSELF: output "/search <query>" on own line`.

**Available (say or let Kai emit):**
- `browse example.com` / `/browse <url>` — fetch + strip HTML
- `/search <query>` — DuckDuckGo `lite/html`
- `/ingest <url>`, `/recall <q>`
- `/pdf file.pdf` (also `/cat`), `/img path.jpg`
- `/ls`, `/cat path`, `/write name.txt: content`
- `> cmd` / `/shell cmd` (sandbox `filesDir/workspace`)
- `/apps [filter]`, `/openapp <name>`, `/url <url>`
- `/alarm HH:MM label`, `/event Title @ yyyy-MM-dd HH:mm`
- `/battery`, `/note <text>`

**How it works:** `Tools.tryToolFromModel` `Tools.kt:407` scans model output for tool lines, `ChatViewModel.kt:674` executes `tryTool` and injects `Tool observation:\n…` then follow-up generation uses results to answer goal (one extra turn). `Tools.tryTool` `Tools.kt:333` also handles user `/` commands directly.

**Examples:**
- `/search weather São José` → `🔎 Results` → Kai summarizes
- `/pdf /sdcard/Download/paper.pdf` → extracts `20` pages
- `> ls -R` → `workspace` listing

---

## 6. Account & Encryption

**Create:** `⚙️ → 🔐` → `Create Account` → `Username/Email/Recovery email/Password + Confirm Password` `MainActivity.kt:860` `AccountManager.kt:152` → `SHA-256+salt` + `PBKDF2 AES/CBC ENC:` `encKey` `isEncryptedMode`, `strongPassword 8+1 caps+1 special`, confirmation email `sendConfirmationEmail` `AccountManager.kt:68` — local inbox + `KaiPcClient.sendEmail` (dev SMTP) + `FirebaseHelper.createAccountAndSendVerification` when `google-services.json` real (`kai-android-c291c`).

**Login:** `⚙️ → 🔐` → `Login` → `G #4285F4 OutlinedButton "Sign in with Google"` `MainActivity.kt:831` `GoogleAuthManager.kt:20` `requestEmail` (+ `requestIdToken` for Firebase) → `handleResult` creates/links local `kai_accounts.xml` `GOOGLE:` hash + `Firebase signInWithGoogle` if configured → `GeminiClient.setLoggedIn`. Or `Email/Password` `AccountManager.login`.

**Logged in:** `MainActivity.kt:800` `Logged in as uname` `History encrypted ✓`, `📧 View Email` opens local inbox `AccountManager.getConfirmationEmail`, `Delete` (red) → confirmation `Are you sure?` → `deleteAccount` `AccountManager.kt:213` + `Firebase delete`, `Logout`, `Forgot password?` → `requestRecovery` → email `kai://recovery?token=…` + `resetPassword`.

---

## 7. Billing — Pro

**Pro** `BillingManager.kt:10` `PRO kai_pro $4.99 one-time` (`non-consumable`, `hasPro`) + legacy `v2/v3 30/90d` also grant pro. Picker `MainActivity.kt:476` if `requiresPro` (`axiom:3b`, `nomic`) and `!hasPro` → `🔒 Pro early access` toast. `Get Kai Pro — $4.99 one-time (remove ads + early access)` `MainActivity.kt:488` `launchPurchase(PRO)`, `AdStripe` skips when `hasPro`.

**Play Console:** Create `Managed product` `kai_pro` `$4.99` `one-time`, not subscription. `Billing 6.1.0`.

---

## 8. Kai PC Live (Optional)

Encrypted LAN bridge `KaiPcClient.kt:23` `TLS self-signed` + `Bearer token`.

**PC:** `python3 axiom_horizon/tools/kai_pc_server.py --port 8443 --token kai-secret-123 [--smtp-host ... --smtp-user ... --smtp-pass ... --from ...]` → `https://0.0.0.0:8443` `health/live/send-email`

**Phone:** `⚙️ → 🖥️` → `192.168.100.208:8443` + `kai-secret-123` (`kai_pc.xml`) `isConfigured`, `autoDiscover` `KaiPcClient:44` + `adb reverse tcp:8443`. picker `kai-pc:live` → `send` `KaiPcClient.send` `POST /` `type text/file/image` base64, `fetchLive` `/live`.

**Email dev:** `KaiPcClient.sendEmail` `POST /send-email` `{to,subject,html,text}` → `kai_pc_server.py:103` `smtplib` `MIMEMultipart`.

---

## 9. Settings (⚙️)

- `✦ Google Sign-In & API Keys` → `Sign in with Google` / `Sign out` `GoogleAuthManager`, `Gemini 1.5 flash/pro` API key `GeminiClient`
- `🎨 Theme: Dark/White` `getThemeMode` `kai_theme.xml` `0/1` `ThemeVersion`
- `⚡ VFE ON/OFF` `show_physics` `kai_prefs`, tap cards to hide (see §3)
- `🌐 Language: en/pt` `Lang.kt` `version` live recomposition
- `🔐 Login/Create` + `💾 Export chats to Download` `getExternalFilesDir` + `Tool` export `MemoryDbKtDarwinSyncExport`
- `🖥️ Kai PC`

---

## 10. Troubleshooting

| Issue | Fix |
|-------|-----|
| `No model loaded` `lib.rs:381` | picker → `⬇` `★ 3B` (free) `1.9G`, wait `syncExternalToInternal`, `lastGgufInfo` `✓` |
| `tap picker to load` `ChatViewModel.kt:187` | file downloaded but not `loadInRust` — tap `Loading…` |
| `VFE/tau tools` | ask `explain vfe and tau` → direct `T' = T×(1+α·g)` |
| Freeze while thinking | `16` batch + `generating` guard `MainActivity.kt:558` `if(generating) return`; don't spam taps |
| `120s` still | ensure `n_gpu 99` Vulkan loaded (`adb logcat KaiBridge`), else `CPU` slower — use `2B` or wait `192` tok `lib.rs:279` |
| Background stops | `POST_NOTIFICATIONS` granted? Don't swipe-kill; `KaiForegroundService` notification must stay |
| `Google error 10` | `SHA1` mismatch `EE:A9:BE:6F…` — re-download `google-services.json` with correct `SHA1/SHA256` |
| `Recovery email not found` | use `recoveryEmail` or `email`, check `kai_accounts.xml` via `run-as cat shared_prefs/kai_accounts.xml` |
| `no GGUF — picker` | `.part` stale `cleanStaleDownloads` `ChatViewModel.kt:50`, resume via `directFallback` |

**Logs:** `adb -s RQGYC026B0T shell logcat -d | grep -E "KaiBridge|ChatViewModel|AccountManager|Billing|AdMob"`

**DB headless:** `adb shell run-as com.axiom.kai cat databases/kai.db` + `sqlite3` `select role,substr(text,1,60),model from messages`

---

## 11. Privacy

See `PRIVACY_POLICY.md` — offline-first, `kai.db` local + `AES`, `Firebase` only when `google-services.json` real, no analytics, host as `https://n4rus.github.io/kai-android/privacy.html`.

---

## 12. Versions

`1.0.2-bg-service` `v5` `AAB 28M` `APK 27M` `minSdk 26` `targetSdk 34` `Firebase BoM 32.7.4` `Billing 6.1.0` `Ads 23.0.0` `Room 2.6.1` `pdfbox 2.0.27.0` `play-services-auth 20.7.0` `NDK 26.1` `llama-cpp-2 0.1.154` `vulkan`. Previous `1.0.1-pro-ads-vfe` `v4`, `1.0.0` `v3`.

License MIT — `github.com/n4rus/kai-android`
