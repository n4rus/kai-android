# Store Listing — Kai (com.axiom.kai) — Vanilla 1

## App identity

- **App name (30 chars max):** `Kai — Offline AI`
- **Category:** Tools (secondary: Productivity)
- **Contact email:** lucasejs@live.com
- **Website (optional):** https://github.com/n4rus/kai-android
- **Privacy policy URL:** https://n4rus.github.io/kai-android/privacy.html
- **Ads:** **No** — no advertising SDK
- **Accounts:** **No** — no sign-up, no login, no email
- **In-app purchases:** Yes — `kai_pro`, one-time $4.99 (optional)

## Short description (80 chars max)

Offline AI — runs on your phone. No account, no ads, no cloud.

## Full description (EN, 4000 chars max)

Kai — your offline AI companion. Runs on your phone. No account, no cloud.

**Private by design**
- No sign-up, no login, no email address. Open the app and chat
- On-device inference with llama.cpp + Vulkan (n_gpu_layers=99) on supported GPUs
- No ads, no analytics, no trackers, no crash reporting
- Your chats never leave your phone unless you export them yourself
- Android cloud backup disabled — app data stays on the device

**Smart & local**
- Recommended 3B model (llama3.2:3b) — fast and reliable on modern phones
- Also supports qwen2.5-coder:3b (code), gemma2:2b (small), qwen2.5:0.5b (low RAM)
- VFE / tau physics: curiosity-aware temperature — Kai explores when surprised and consolidates when confident
- Persistent memory, knowledge base, skills, and a multi-step agent loop
- Tools: /search /browse /pdf /ls /battery and more (enabled per tool)
- Background generation keeps running when the screen is locked (Android 8+)
- Kai PC bridge (optional): pair with your desktop over TLS so Kai can see local files

**Free and open source**
- Free: all core chat, local models, memory, and tools
- No ads, ever
- Optional Kai Pro (one-time $4.99): early-access experimental models and a direct way to support a solo developer. No subscription, no recurring charge
- Purchase is tied to your Google account and can be restored on any device
- Source code (MIT): https://github.com/n4rus/kai-android

**Made for offline**
- Works in airplane mode with a downloaded model
- No account required
- Models download from Hugging Face / GitHub when you choose

Kai is built by a solo developer in Brazil.

---

## PT-BR (opcional, para o listing em português)

Kai — sua IA offline. Roda no seu celular, sem conta e sem nuvem obrigatória.

- Inferência local com llama.cpp + Vulkan
- Sem conta, sem anúncios, sem analytics; seus dados ficam no aparelho
- Modelo 3B recomendado, roda até em aparelhos modestos
- Memória persistente, ferramentas e agente com múltiplos passos
- Geração continua com a tela bloqueada
- Grátis e sem anúncios; Kai Pro opcional $4.99 único (modelos experimentais + apoio ao dev)
- Código aberto (MIT): github.com/n4rus/kai-android

## Data Safety (Play Console answers)

**No data collected, no data shared.**

- Data collection: **No**
- Data sharing: **No**
- Account creation: **No**
- Ads: **No**
- Analytics: **No**
- Data encrypted in transit: **N/A** (no data leaves the app; optional user-configured LAN/API calls use HTTPS/TLS)
- Data deletion request: **N/A** — user deletes chats in-app or uninstalls; the developer holds no data
- Independent security review: No

Optional user-initiated features (not developer collection): Kai PC bridge sends prompts to the user's own PC on LAN; adding a third-party API key sends prompts directly to that provider under the user's account.

## Content rating questionnaire

- Violence: No (user-generated AI text may contain any topic)
- Sexuality: No
- Profanity: No
- Controlled substances: No
- User-generated content: **Yes** (AI chat)
- User interaction / sharing: No public sharing
- Ads present: **No**
- Target audience: 13+ (LLM may produce mature text; no child-directed design)

## Pricing

- App: Free
- Managed product ID: **`kai_pro`** — $4.99, one-time, non-consumable
- Legacy IDs `kai_v2_30d`, `kai_v2_90d`, `kai_v3_30d`, `kai_v3_90d` remain recognized for restore only — do not create them as active products

## Graphics

- **App icon 512x512:** `play-icon-512.png` (generated from `kai_logo_earth.png`)
- **Feature graphic 1024x500, no alpha:** `feature-graphic-1024x500.png` (generated from `kai_background.png`)
- **Screenshots:** at least 2 phone screenshots (9:16, 1080x1920 recommended)
- 7" tablet screenshot (optional, improves ranking)

## Release track plan

1. Internal testing (upload AAB `1.1.0-vanilla1` / 8, add `lucasejs@live.com`, install from Play link)
2. Verify chat + Pro purchase/restore on A36 (RQGYC026B0T)
3. Closed testing if Play requires it for new personal accounts (12 testers / 14 days)
4. Production rollout 20% → 50% → 100%
