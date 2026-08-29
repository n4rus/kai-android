# Store Listing — Kai

## Short description (80 chars max)
Offline AI — private, local 3B model, encrypted history, no cloud.

## Full description (EN, ~2000 chars)

Kai — Your offline AI companion. No cloud, no monthly fees.

**Private by design:**
- Runs 100% on-device (llama.cpp + Vulkan `n_gpu_layers=99`, dual slots, 75s timeout + cancel)
- Chat history encrypted with your password (PBKDF2 AES/CBC)
- No analytics, no ads. Your data never leaves your phone unless you tap Export.

**Smart & local:**
- Default ★ Recommended 3B (llama3.2:3b) — fast, reliable. 0.5B too dumb, 7B too slow — 3B is the sweet spot. Also coder:3b and gemma2:2b.
- VFE / tau physics: curiosity-aware temperature `T' = T×(1+α·g)` — Kai explores when surprised, consolidates when confident.
- Persistent memory, knowledge base, skills, agent loop (write → shell → fix), tools: /pdf /browse /search /img /ls /battery

**One-time $4.99, free all models:**
- v1: all models free. v2/v3: one-time $4.99 unlocks programs, all models stay free (Billing 6.1.0 managed product, not subscription).

**Kai PC live (optional):**
- Pair with your PC (`python3 tools/kai_pc_server.py`) for desktop context via TLS + token, or use 100% offline.

Free, offline, yours.

---
PT:

Kai — Sua IA offline. Sem nuvem, sem mensalidade.

Privada por design: 100% no aparelho (llama.cpp + Vulkan), histórico criptografado com senha.

3B ★ Recomendado (llama3.2:3b) — rápido e confiável. VFE/tau: temperatura curiosa `T'`.

$4.99 único, todos os modelos gratuitos (v2/v3).

## Data Safety (Play Console)

- Data collection: Email (account, optional) — collected, encrypted in transit, user can delete via In-app Delete. No other data collected.
- Data sharing: No data shared with third parties (Firebase Auth only if you enable it — declare it then).
- Security: Data encrypted in transit (TLS), data encrypted at rest (AES).
- No location, no contacts, no analytics.

## Content rating

Questionnaire: No violence, no sexual content, no profanity by default (LLM may generate — select "User-generated content"). Target: Everyone.

## Encryption declaration

Export compliance: uses standard AES/CBC, PBKDF2, TLS — exempt (standard cryptography). Answer "Yes, exempt."

## Graphics needed

- Icon: 512x512 (use mipmap/ic_launcher, export PNG, no alpha)
- Feature graphic: 1024x500 (dark #0B1026 bg, “Kai — Offline AI” text, no transparency)
- Screenshots: at least 2 phone (16:9 or 9:16, e.g., 1080x1920) — Chat + VFE + Themes. 1 tablet 7" if possible. 1 Wear/ChromeOS optional.
- Privacy policy URL: host PRIVACY_POLICY.md as https://.../privacy.html

## Pricing

- Free app with one managed in-app product: `kai_v2_v3` $4.99 one-time, type Managed product, not subscription.

## Track

- Internal testing → Closed → Production. Start with Internal (your A36 RQGYC026B0T).
