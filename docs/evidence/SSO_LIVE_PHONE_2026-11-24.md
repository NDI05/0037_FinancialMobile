# Bukti SSO live di HP fisik — 24 November 2026 (agregat)

Status: **login Google nyata + penerbitan sesi PAM + binding identitas terverifikasi** pada
backend UAT loopback, dari aplikasi `com.pam.recap.debug` di HP fisik pemilik.
Dokumen ini **tidak** membuktikan tombol **Run** literal Android Studio, onboarding Gmail
(tautkan/skip), maupun GCP Rp0 — ketiganya tetap gate terpisah/gate pemilik.

## 1. Rentang waktu dan jejak server (log backend `127.0.0.1:18090`)

Sesi login terjadi 24 November 2026 pukul 21:49–21:50 WIB (semua agregat; nilai token/nonce,
email, dan ID tidak dicatat di sini):

| Waktu WIB | Kejadian (agregat) |
|---|---|
| 21:49:50 | `GET` nonce SSO → `200` (rute tanpa nama pada log; nonce berlaku 600 dtk) |
| 21:49:57 | `POST` autentikasi Google → `200` dalam ~200 ms; nonce **dikonsumsi** pada 21:49:57,29 (consume-before-verify); baris `google_identities` pertama (issuer `https://accounts.google.com`) dibuat 21:49:57,50 |
| 21:49:57 | `GET /v1/me` → `200` (sesi PAM diterbitkan) |
| 21:50:18 | `DELETE /v1/sessions` → `204` (logout aplikasi) |
| 21:50:22 | `GET` nonce SSO → `200` (sesi login kedua) |
| 21:50:26 | `POST` autentikasi Google → `200` (≈2 ms); nonce kedua dikonsumsi; `GET /v1/me` → `200`, lalu aplikasi memuat `/v1/gmail/status`, `/v1/transactions`, `/v1/categories`, `/v1/import-jobs`, `/v1/summaries` |

Rute SSO tidak tercatat dengan nama pada middleware log (`{unknown}`), sehingga korelasi dilakukan
lewat tabel `google_sso_nonces` (2 nonce dibuat, keduanya `consumed_at` terisi sebelum
`/v1/me` sukses), tabel `google_identities`, dan urutan request aplikasi di atas.

## 2. Bukti perangkat (HP fisik)

- `dumpsys activity` saat pemeriksaan: `topResumedActivity` =
  `com.pam.recap.debug/com.pam.recap.MainActivity` (aplikasi terlihat/aktif).
- `mLastPausedActivity` = `androidx.credentials.playservices.HiddenActivity` — yaitu activity
  picker **Credential Manager** yang dipakai alur "Masuk dengan Google" baru saja ditutup,
  konsisten dengan login Google di aplikasi (bukan browser eksternal).
- `adb reverse tcp:18090 tcp:18090` aktif sehingga semua request berasal dari aplikasi di HP ini.

## 3. Integritas data setelah login (agregat DB)

| Pemeriksaan | Hasil |
|---|---|
| Jumlah user | 1 (tidak ada akun baru/merge otomatis) |
| Transaksi | 914 baris, `count(distinct source_ref_hash)` = 914 → **0 duplikat** setelah login |
| Identitas Google | 1 baris (unik per issuer+sub); dibuat tepat saat login pertama |
| Koneksi Gmail | 1 (koneksi pilot lama tetap; tidak ada consent baru pada sesi ini) |
| Sesi | `auth_sessions` aktif setelah login ulang; `sessions` refresh lama dicabut saat logout |
| Error jalur auth | tidak ada `401`/`409` pada alur login di jendela waktu ini |

## 4. Yang belum dibuktikan (tetap gate pemilik)

- **Tombol Run literal Android Studio**: `dumpsys package com.pam.recap.debug` menunjukkan
  `lastUpdateTime` tetap 24 Nov 19:28:04 → tidak ada instalasi ulang sejak 19:28; aplikasi yang
  dipakai adalah build UAT terpasang sebelumnya. Karena itu G36 tetap **PARTIAL**, bukan PASS.
- **Onboarding Gmail (tautkan/skip) + consent `gmail.readonly`** pada sesi ini tidak teramati
  (koneksi pilot sudah tertaut sejak G25).
- **Email Livin baru tersimpan saat aplikasi tertutup**: delta poll berjalan normal
  (`failures=0`) tetapi tidak ada event baru pada jendela uji ini.
- **GCP Rp0 (G29/G37)**, merge/deploy/SRS (G24), dan drill hapus retensi (G14) tidak berubah.

## 5. Kesimpulan gate

Login Google nyata di HP + penerbitan sesi PAM + binding identitas **terbukti** dan tidak
menghasilkan duplikasi data; sisa sub-butir G36 (Run literal, onboarding Gmail, persistensi email
saat app tertutup) belum diuji. Gate dihitung **PARTIAL** — bukan PASS — sampai pemilik menekan
**Run** dan melengkapi sub-butir tersebut.
