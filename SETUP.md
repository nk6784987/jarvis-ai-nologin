# JARVIS - setup required for real operation

No login, no Firebase, no cloud account. Everything is stored on the phone. Nothing is simulated:
if a step below is missing, that feature shows an explicit "not configured / permission required" state.

## 1. AI providers (in-app: Quick Actions -> API)
Add one or more providers (OpenAI-compatible / Gemini / Anthropic). Keys are validated, models discovered,
response-tested and latency-measured. Keys live only in Android Keystore-backed EncryptedSharedPreferences.
Without a provider JARVIS cannot answer - it says so instead of faking a reply.

## 2. Web search (same screen)
Brave Search API key, or Google Programmable Search (API key + cx).

## 3. Device control
Enable Settings -> Accessibility -> JARVIS. Android < 11 uses the MediaProjection consent flow.
Other permissions (mic, contacts, phone, SMS, media/all-files, notifications, exact alarms) are requested on demand.

## Unrestricted Mode (Settings)
Off by default. When ON: confirmation prompts are skipped (each auto-approved action is logged in chat) and agent
step / retry / repeat limits are about 4x higher.

## What no setting can change
- Android platform rules: runtime permissions, Accessibility consent, secure (FLAG_SECURE) screens, lock screen, background-UI limits.
- Payment / OTP / UPI-PIN / password screens are never driven by the agent.
- WhatsApp has no send API (UI automation only, verified from the screen); email has no "sent" callback.
