# Kai-Android — Recursive Kai Chat: Planning & Build Log

**Mode:** Build entire app in one go **as if `you ↔️ Kai` recursively**, not `you ↔️ me`. The app *is* the recursion.

---

## 0. Premise — Talk With Me Recursively, But With Kai Instead

You talk to me (`opencode` + Kai). Kai talks to itself (`kai darwin` + VFE self-mod). This app collapses that loop into one screen: **you ↔️ Kai ↔️ Kai' ↔️ Kai'' …** on-device.

Every turn:
```
You: prompt
Kai:  VFE(surprise+KL) → curvature → T' → response + VFE meter update
Kai': (recursive self-prompt, VFE-gated) → "what would reduce VFE next?" → ghost message
You:  next prompt (or tap Kai' suggestion)
```

It is the `axiom_horizon` debug loop as a UI: fail → hypothesis → patch → re-run, but for conversation.

---

## 1. App Definition

**Name:** Kai-Android — Recursive Chat  
**Package:** `com.axiom.kai`  
**MinSdk 26, Compose Material3, Rust cdylib via JNI**  
**Public repo:** `kai-android` (352K scaffold, this planning is commit 2)

**Core loop (maps to `MANUAL.md §5-6`):**
- `g_ij = 1 - a_ij` → curvature → `T' = T*(1+alpha*curvature)` → sampling
- `VFE = surprise + KL(q||p_attractor)` → `tau` + meter + teacher hint
- Attractor (173 vectors, stubbed as 3 presets) → “good representation” prior

---

## 2. Architecture — One Screen, Three Layers

```
┌─────────────────────────────────────┐
│  Compose UI (MainActivity + Vm)     │  Chat list (LazyColumn), input, VFE gauge, curvature chip, model picker
└──────────────┬──────────────────────┘
               │  StateFlow<List<ChatMessage>>
               ▼
┌─────────────────────────────────────┐
│  Kotlin ViewModel (ChatViewModel)   │  history, VFE calc, recursion gate, DownloadManager for GGUF
└──────────────┬──────────────────────┘
               │  JNI
               ▼
┌─────────────────────────────────────┐
│  Rust kai-bridge cdylib             │  kai_calculate_vfe, kai_curvature_to_temp, kai_load_gguf, kai_generate (stub→real loader)
└─────────────────────────────────────┘
               │
               ▼  mmap
         ~/. (filesDir/models/*.gguf)
```

**File map (what we build in one go):**
```
rust-bridge/src/lib.rs        # add kai_generate, kai_chat (stub that echoes + VFE)
app/src/main/cpp/native-lib.cpp + CMakeLists.txt  # JNI for those
app/src/main/java/com/axiom/kai/
  ChatMessage.kt              # data class with role, text, vfe, curvature, ts
  KaiBridge.kt                # version, calculateVFE, curvatureToTemp, loadGguf, generate
  ChatViewModel.kt            # StateFlows, send(), recursiveSelfPrompt(), picker
  MainActivity.kt             # Scaffold + LazyColumn + input + meters + picker
  ui/theme/Theme.kt           # Material3
app/src/main/res/values/strings.xml, themes.xml
```

---

## 3. Data Model

```kotlin
data class ChatMessage(
  val id: String = UUID,
  val role: Role, // USER, KAI, KAI_RECURSIVE (ghost)
  val text: String,
  val vfe: Float? = null,        // surprise+KL for this turn
  val curvature: Float? = null,  // 0..1
  val model: String = "qwen2.5:0.5b",
  val ts: Long = now()
)
```

`KAI_RECURSIVE` messages are rendered ghosted, tappable to promote to `USER` next prompt.

---

## 4. Recursive Logic (The “Talk With Me Recursively”)

```kotlin
fun send(userText: String) {
  add(USER, userText)
  val curvature = estimateCurvature(userText, history) // stub: hash distance vs last 3
  val surprise = estimateSurprise(userText) // length + novelty
  val kl = distanceToAttractor(userText) // stub: 0.2..0.8
  val vfe = KaiBridge.calculateVFE(surprise, kl) // → Rust
  val temp = KaiBridge.curvatureToTemp(0.85f, curvature, 0.4f)

  val kaiText = KaiBridge.generate(userText, temp, vfe) // Rust stub: template + temp
  add(KAI, kaiText, vfe, curvature)

  // Recursion gate: if VFE high (>3.0) and not already recursive, Kai self-prompts
  if (vfe > 3.0f && recursionDepth < 2) {
    val selfPrompt = "What would reduce VFE about: '$userText'? (curvature $curvature)"
    val selfVfe = KaiBridge.calculateVFE(surprise*0.7f, kl*0.8f)
    val selfText = KaiBridge.generate(selfPrompt, temp*0.9f, selfVfe)
    add(KAI_RECURSIVE, "↻ $selfText", selfVfe, curvature*0.8f)
  }
}
```

This mirrors `MANUAL.md §8.4 Info Relay` — surprise routes to attractor/world-graph ghost.

---

## 5. UI — Single Screen

- **Top bar:** `Kai-Android` + version + `model picker dropdown` (qwen2.5:0.5b / 7b / llama3:8b)
- **Meters row:** VFE gauge (0..5, color green→red) + curvature chip (0..1, chip + `T'`)
- **Chat list:** `LazyColumn`, USER right/bubble blue, KAI left, RECURSIVE ghosted + “Use as prompt” button
- **Input row:** `TextField` + `Send` FAB. On send → `vm.send()`
- **Empty state:** “Talk with Kai recursively — type a thought, Kai answers, Kai' suggests the next question.”

No nav, no fragments — one `MainActivity` + `ChatViewModel`.

---

## 6. Build Steps (One Go — This Commit)

1. `rust-bridge` — add `kai_generate` (C string in/out, temp/vfe params), stub that returns templated Kai response with VFE echo
2. `native-lib.cpp` / `KaiBridge.kt` — expose `generate`, `loadGguf`, `version`
3. `ChatMessage.kt` + `ChatViewModel.kt` — StateFlows, history, VFE/curvature stubs, recursion gate, picker
4. `MainActivity.kt` — full Compose chat, meters, picker, ghost handling
5. `strings.xml` / `Theme.kt` — Material3
6. `./gradlew assembleDebug` smoke (no GGUF needed for stub)

---

## 7. Next After This Commit (Not In This Go)

- Wire `kai_load_gguf` to real `rust/kai-fusion/src/loader` (mmap `filesDir/models/*.gguf`)
- Replace `kai_generate` stub with real `model.generate()` streaming via JNI callback
- KV-cache + MLA toggle via `KaiFusionConfig`
- DownloadManager for GGUF + `attractor.rs` 173 vectors asset

---

## 8. Novelty Callout (for README)

This is not a chat wrapper — it is `g_ij→T` and `VFE→tau` as UI, with recursive self-prompt that is the `darwin` loop made visible. Star the parent `axiom_horizon` for the full `MANUAL.md §8` proof.

---

*Build log: scaffold 352K → this commit adds recursive chat. Next `adb install`.*
