# REAL-UAT handoff — 22 November 2026

Status: **backend/DB/app ready for owner login; REAL-UAT data flow not yet PASS.**
No secret, URL, token, Gmail address, merchant, email body or raw credential is stored here.

## Verified

- Private env loaded from `/Users/user/.config/pam/real-uat.env`; `DATABASE_URL` is
  `libsql://…` and `DATABASE_AUTH_TOKEN` is a JWT-shaped 371-byte token (not printed).
- Go API `production` started on `127.0.0.1:18090`; `/readyz` returned 200.
- Migrations applied successfully against the new Turso database.
- Synthetic auth smoke: register 201, login 201, categories 200 (11 rows), summary 200.
- Android REAL-UAT build uses `http://10.0.2.2:18090`; APK installed over the same
  `com.pam.recap.debug` package without uninstall/clear-data.
- App was logged out to the Welcome screen for owner login. Local synthetic QA data is not
  used as REAL-UAT evidence.

## Owner handoff

1. In the open emulator app choose **Buat akun**, register with your own PAM email/password.
   The invite code is the value of `REGISTRATION_INVITE_CODE` in the private env file.
2. Grant Gmail consent, then **Hubungkan Gmail** and enter the App Password directly in the UI.
3. Tell the SE when the account and Gmail link are ready; SE will run read-only import and
   verify Turso rows, list/detail/summary, category, logout/login/restart persistence.
4. For a quick UI smoke only, the synthetic account `uat-smoke@example.test` /
   `SyntheticPass123!` exists in the REAL-UAT database. Do not use it for owner data.

## Not proven

- Real Livin email import to Turso and UI readback.
- A link→logout→login A→restart / A→B→A lifecycle in the REAL-UAT APK.
- Real Ollama analysis output/latency.
- CR-003 category provenance/reclassification and S2–S4 remaining scope.

## Gmail link failure diagnosis — 22 November 2026

Owner's first Gmail link attempt returned “Periksa Koneksi Anda”. Backend was healthy;
Android log showed `imap_validation_failed code=NETWORK`. The emulator had no DNS resolver
(`net.dns1` empty), so `imap.gmail.com` could not resolve even though host TCP and the App
Password were valid. The AVD was restarted with `-dns-server 8.8.8.8,1.1.1.1`.

Verified after restart: emulator resolves/connects `imap.gmail.com:993`; private host IMAP
login with the owner-provided App Password succeeded. The app is left on **Kotak masuk →
Hubungkan Gmail** for the owner to submit the App Password in the UI. The main Google password
is not accepted/required by PAM and is not stored.
