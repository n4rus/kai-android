# Privacy Policy — Kai — Offline AI Companion

**Effective Date: Oct 20, 2026**
**Package: com.axiom.kai**

Kai is **offline-first** and **account-free**. Your privacy is the core feature.

## 1. Data We Collect

**Kai does not collect, transmit, or share personal data.**

There is no account, no sign-up, no email, no login, no analytics, no advertising SDK, and no crash reporting. We have no servers and no backend.

### 1.1 Chat History & Memory
- All messages, memories, knowledge, and chat history are stored **only on your device** in `kai.db` (Room).
- They are never uploaded to us or any third party.
- Optional export to `/Download/kai_*.json` is user-initiated only.
- Android cloud backup is **disabled** (`allowBackup="false"`), so app data is not copied to Google Drive.

### 1.2 Models
- Models are downloaded on demand from GitHub / Hugging Face via Android DownloadManager. Those hosts see your IP address as part of a normal HTTPS request, per their own policies.
- Inference runs entirely on-device with llama.cpp.

### 1.3 Optional network features (off by default)
- **Kai PC bridge (LAN only):** if you configure it, prompts and selected files are sent to the PC address you enter (TLS + Bearer token). This is your own machine; nothing reaches us.
- **Remote LLM API keys (optional):** if you add a Gemini/DeepSeek/OpenAI/Qwen/Anthropic key, your prompts are sent directly to that provider under your own account and their privacy policy. Keys are stored locally.

### 1.4 Purchases
- Google Play Billing handles the optional `kai_pro` purchase. Payment data is processed by Google Play; Kai never sees or stores it.

## 2. Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`: model download, optional Kai PC bridge, optional remote LLM APIs.
- `POST_NOTIFICATIONS` (Android 13+): background generation progress notification.
- `FOREGROUND_SERVICE`, `WAKE_LOCK`: keep generation running while the screen is off.
- `WRITE_EXTERNAL_STORAGE` (maxSdk 28): legacy export to Download on Android 9 and below.
- No `READ_CONTACTS`, `LOCATION`, `MICROPHONE`, `CAMERA`.

## 3. Third-Party Services

- **Google Play Billing 8.3.0** — optional one-time `kai_pro` purchase. https://policies.google.com/privacy
- **GitHub / Hugging Face** — model file downloads. https://docs.github.com/site-policy/privacy-policies and https://huggingface.co/privacy
- No advertising networks, no analytics providers, no data brokers, no Firebase.

## 4. Data Retention & Deletion

- All data lives on your device. Delete individual chats in-app, or uninstall Kai to remove everything.
- We hold no copy and cannot recover your data.

## 5. Children's Privacy

Not directed to children under 13. No data is collected from anyone.

## 6. Security

- Chat data is stored in the app-private Room database, protected by Android's app sandbox.
- Kai PC bridge: self-signed TLS + Bearer token, LAN only.
- Data is not end-to-end encrypted at rest beyond OS app sandboxing; do not store secrets you would not keep in any local app.

## 7. Contact

Lucas Eduardo Jaguszewski da Silva (n4rus) — com.axiom.kai — https://github.com/n4rus/kai-android — lucasejs@live.com

**Public URL (Play Console → Store settings → Privacy policy):**
https://n4rus.github.io/kai-android/privacy.html

The hosted page is `privacy.html` (and `docs/privacy.html`) in this repository.
