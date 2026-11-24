# Audit independen ronde 7 — parser Livin, tes SSO, konsistensi bukti

Tanggal: 24 November 2026 (WIB). Auditor: agen independen berkonteks segar, **read-only** (tidak
mengubah kode/commit). Lingkup: backend `5df6be6` + `c98527b`, Android `4eeffd6`, dan dokumen bukti
Gmail live. Tidak ada data pribadi (email, token, ID pesan, nominal, merchant) yang dicetak atau
disimpan.

## 1. Hasil uji

| Uji | Hasil |
|---|---|
| `./scripts/go.sh test ./internal/gmail -count=1` | **PASS** (≈3 detik) |
| `./scripts/go.sh test ./... -count=1` | **PASS** (10 paket) |
| Probe parser manual (klon sementara, dihapus) | Dijalankan untuk membuktikan/menyanggah temuan |

## 2. Temuan

| Prioritas | Temuan | Status |
|---|---|---|
| **P0** | Tidak ada temuan identitas/PAN/reference sebagai merchant; tidak ada ReDoS (regex linear, loop join O(n)) | **bersih** |
| **P1-1** | `findLabel` menerima baris field sebagai merchant bila label diikuti baris `Nominal …`, `Tanggal …`, `No. Referensi …`, atau `pkl …` → baris itu berstatus `PARSED` | **diperbaiki** di backend `e3b84b4`: penjaga `looksLikeFieldValue` + batas panjang label + regresi `TestParseLivinStackedLabelRejectsFieldLines` |
| **P1-2** | Angka bukti live tidak rekonsiliasi: jumlah diimpor per bulan 769 + 79 duplikat Sep = 848, sedangkan total 914 | **diperbaiki di dokumen**: 769 hasil run final + 145 baris sudah tersimpan dari percobaan live pertama (Nov 38, Okt 28, Sep 79) = 914; lihat [GMAIL_LIVE_IMPORT](GMAIL_LIVE_IMPORT_2026-11-24.md) §3 |
| P2-3 | `WIT` diganti sebelum `WITA` sehingga WITA rusak; WITA/WIT diparse sebagai WIB | **diperbaiki** di `e3b84b4`: urutan token terpanjang + lokasi UTC+8/UTC+9; regresi `TestParseLivinWitaTimezone` |
| P2-4 | Tanggal dan waktu tidak bersebelahan (ada baris referensi di antara) → waktu hilang, event 00:00 | **diperbaiki** di `e3b84b4`: penggabungan tetangga ±2 baris; regresi `TestParseLivinDateTimeWithInterveningField` |
| P2-5 | Bulan `Nop`/`Peb` tidak dikenali → transaksi nyata dilewati | **diperbaiki** di `e3b84b4`: alias bulan pendek; regresi `TestParseLivinShortIndonesianMonths` |
| P2-6 | Email promosi QRIS berisi baris "Merchant"+nominal+tanggal bisa diparse (pre-existing) | **backlog non-blocking**: filter template promosi perlu bukti tambahan sebelum diubah agar tidak memblokir transaksi sah |
| P3-7 | Layout `… WIB` di `dateLayouts` dead code | **diperbaiki** (entri dihapus, token waktu di-strip terpisah) |
| P3-8 | `parseIDRAmount` memotong (truncate) walau komentar menyebut round; label merchant tidak dibatasi | **diperbaiki**: `math.Round` + batas 160 karakter |
| P3-9 | Tes SSO `4eeffd6` masih menyisakan baris mati `if (!configured) assertFalse(...)` | **diperbaiki**: baris dihapus; pemetaan client ID → `ENABLED` tetap diuji |

Tidak ada temuan pada: overstatement gate (G36 NOT RUN dan G29/G37 OPEN benar), akuntansi gate
38 = 29 PASS / 4 PARTIAL / 5 NOT RUN, regresi perilaku lama, dan false-positive identifier.

## 3. Verifikasi setelah perbaikan

- Backend `e3b84b4`: `vet`/`build`/`test ./... -count=1` (10 paket) + `-race`
  auth/gmail/httpapi/store **PASS**; runtime loopback dibangun ulang dari `e3b84b4`
  (`vcs.modified=false`) dan `/readyz=200`.
- Android: tes SSO terpilih PASS; gate Android final tetap 678 tes/88 suite/0 gagal.
- Harness kontrak QA (§9, provider fake, port terisolasi) diulang pada backend `7bb16f0`:
  **PASS=82, FAIL=0, exit 0** — parser `gmail-livin-v2` tidak mengubah perilaku kontrak HTTP.
- Preflight `scripts/verify/uat_readiness.sh` pada runtime `7bb16f0`: **exit 0** (nonce SSO 200,
  rute status 401 tanpa token, HP + `adb reverse` OK); GCP Rp0 tetap manual/owner.
- Gate tetap **29 PASS / 4 PARTIAL / 5 NOT RUN**; G25 tetap PASS-live-owner, G36 dan G29/G37 tetap
  NOT RUN/OPEN.

## 4. Audit ulang ronde 2 (24 Nov 2026, sesi terpisah, read-only)

Lingkup: HEAD `7bb16f0` + WIP hardening. Uji bersih: **183 PASS / 0 FAIL** (`internal/gmail`,
`internal/httpapi`), `vet`/`test ./...` PASS.

| Prioritas | Temuan | Status |
|---|---|---|
| **P1** | Baris field dari template proyek sendiri (`Merchant PAN: …`, `Customer PAN: …`) masih bisa menjadi merchant pada jalur stacked; jalur inline juga belum kena guard | **diperbaiki** `e19477b`: guard berbasis nilai (prefix field, pola `Label: nilai` generik, token identifier ≥6 digit) diterapkan pada **kedua** jalur; regresi `TestParseLivinStackedLabelRejectsPANAndFieldLines` + `TestParseLivinInlineLabelRejectsPANLine` |
| P2 | Merchant sah dengan kata bulan + digit (`Warung Bu Des 2`) ditolak | **diperbaiki**: deteksi tanggal butuh tahun 4 digit; regresi `TestParseLivinMerchantWithShortMonthWord` |
| P2 | Join tanggal–waktu menjangkau ±2 baris tanpa syarat | **diperbaiki**: jangkauan maksimum 3 baris hanya bila semua baris di antaranya field/separator; regresi `TestParseLivinIgnoresDistantTime` + `TestParseLivinTimeAcrossThreeFieldLines` |
| P2 | Amount tanpa `Rp` (`150.000,00`) lolos sebagai label | **diperbaiki**: koma diperlakukan sebagai pemisah identifier; masuk regresi PAN |
| P3 | Label stacked diikuti baris pemisah (`------`) → merchant hilang | **diperbaiki**: baris separator dilewati saat mengambil nilai; regresi `TestParseLivinBareLabelSkipsSeparatorLine` |
| P3 | Zona pada baris terpisah (`… pkl 09:30:45` lalu `WITA`) diabaikan | **diperbaiki**: token zona terdekat dipakai saat parse; regresi `TestParseLivinSeparateZoneLine` |

Kontradiksi dokumen yang dilaporkan juga ditutup: (a) counter duplikat Nov/Okt 0 dijelaskan
(baris lama tidak dipindai ulang), (b) angka pass pertama vs run final dipisahkan, (c) bukti 914
dinyatakan berasal dari build `c98527b` dan hardening hanya rebuild runtime, (d) klaim PAN
dipersempit ke guard yang benar-benar teruji (`e19477b`).

Gate backend setelah perbaikan: `e19477b` vet/build/test/race **PASS**; runtime loopback
dibangun ulang dari `e19477b` (`vcs.modified=false`, `/readyz=200`).
