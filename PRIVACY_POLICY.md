# Privacy Policy — Kai — Offline AI Companion

**Effective Date: Aug 29, 2026**
**Package: com.axiom.kai**

Kai is **offline-first**. Your privacy is the core feature.

## 1. Data We Collect

### 1.1 Account (if you create one)
- Email, username, recovery email, password hash (SHA-256 + salt, stored locally on device via `kai_accounts.xml`).
- If Firebase Auth is configured (Play Store release), email/password are also sent to Firebase Authentication (Google) to send the real verification email. See https://firebase.google.com/support/privacy.
- Google Sign-In: if you use Google Sign-In, we receive your Google email and display name via `play-services-auth`. If Firebase is configured, an ID token is sent to Firebase Auth. No Google data is sent elsewhere.

### 1.2 Chat History & Memory
- All messages, memories, and chat history are stored **only on your device** in `kai.db` (Room) and `kai.db.backup`. If you create a password, they are AES/CBC encrypted with PBKDF2 derived key. They are **never sent to our servers**. Play Store Data Safety: "Data not collected for this type."
- Optional export to `/Download/kai_*.json` is user-initiated only.

### 1.3 Device / Diagnostics
- No analytics, no advertising SDK. No data shared with third parties.
- Model downloads use GitHub/Hugging Face URLs via Android DownloadManager — your IP is visible to those hosts per their policies.

## 2. Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`: model download, Kai PC live bridge (LAN only, TLS + Bearer token), Firebase Auth (if configured), Gemini/Billing if you enable them.
- `WRITE_EXTERNAL_STORAGE` (maxSdk 28): legacy export to Download on Android 9 and below.
- No `READ_CONTACTS`, `LOCATION`, `MICROPHONE` (mic only if you grant for voice input — audio stays on device).

## 3. Third-Party Services

- Firebase Authentication (optional, only when `google-services.json` is real): email verification and password reset. https://firebase.google.com/support/privacy
- Google Sign-In: https://policies.google.com/privacy
- Play Billing 6.1.0: `v2`/`v3` $4.99 one-time in-app product (no subscription).
- Hugging Face / GitHub for model files.

## 4. Data Retention & Deletion

- Account → `⚙️ → Account → Delete` permanently deletes the local account. If Firebase is configured, it also deletes the Firebase user.
- Uninstalling the app deletes all local databases. Backups are not retained.

## 5. Children's Privacy

Not directed to children under 13. No age gate, but no data collected from children.

## 6. Security

- Passwords: SHA-256 + 16-byte salt. Chat: AES/CBC PKCS5 + random IV + `ENC:` prefix, key via PBKDF2WithHmacSHA256 10k iterations, 256-bit.
- Kai PC bridge: self-signed TLS + Bearer token, LAN only.

## 7. Contact

AxiomTree — com.axiom.kai — https://github.com/n4rus/kai-android

Host this file at `https://n4rus.github.io/kai-android/privacy.html` or your domain and paste URL into Play Console → Store settings → Privacy policy.
