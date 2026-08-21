# LiveLingo Security Architecture

## Goal
Protect transcripts, meeting insights, translations, recordings, model context, and exported reports so that a compromised app package or copied application files do not reveal user data in plaintext.

## Storage model
- Default mode: local-only. No meeting content is uploaded unless the user explicitly enables a cloud/business sync feature.
- Private app storage only. No raw session data in shared/external storage.
- Sensitive session content is encrypted at rest using per-installation keys protected by Android Keystore.
- Each meeting/session is stored as an encrypted record. Audio recording is optional and disabled by default; transcript-only mode is preferred.
- Debug traces must never contain full transcript text in production builds. Production logging stores only event names, timings and error codes.

## Key hierarchy
- Android Keystore holds a non-exportable wrapping key.
- A random data-encryption key (DEK) is generated for LiveLingo's secure vault.
- DEK is wrapped by the Keystore key and persisted only in wrapped form.
- Session data is encrypted with AES-256-GCM using random 96-bit nonces.
- Authentication tags are verified before any plaintext is returned.
- Keys are never hardcoded in APK/resources/source code.

## App access protection
- Optional biometric/device-credential lock for Meetings and Library.
- Auto-lock after configurable inactivity and whenever the device is locked.
- Sensitive screens can set FLAG_SECURE to reduce screenshots/screen recording leakage.
- Notification text for background recording contains no transcript or meeting content.

## Data lifecycle
- Configurable retention: manual, 7/30/90 days, or company policy.
- Secure delete means encrypted session files are removed and their per-session keys/metadata are removed.
- Export creates a temporary decrypted file only after explicit user action; the temporary file is deleted after sharing where possible.
- Android backup is disabled for the secure vault so transcripts are not copied into ordinary cloud backups.

## Business / cloud mode (future)
If team sync is enabled later:
- Client-side encryption before upload.
- TLS in transit.
- Separate tenant/workspace keys.
- No server-side plaintext search by default.
- Enterprise policy for retention, remote wipe, SSO and audit events.
- Cloud AI processing must be an explicit policy choice, never a silent fallback from local mode.

## Threat model notes
This design protects against common data extraction from copied app files, casual device access and APK reverse engineering because the APK contains no data keys. It cannot promise protection if an attacker fully compromises an unlocked device/root environment while LiveLingo is actively decrypting data in memory. Business claims must describe this limitation accurately.

## Production checklist
1. Encrypted vault for transcripts/insights/session metadata.
2. No plaintext transcripts in SharedPreferences, logs, cache or external storage.
3. `android:allowBackup="false"` and explicit data-extraction rules.
4. Biometric/device credential lock option.
5. `FLAG_SECURE` option for sensitive business screens.
6. Redacted production diagnostics.
7. Automatic retention/deletion controls.
8. Security review before cloud sync is introduced.
