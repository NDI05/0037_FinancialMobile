# Serah-terima PM PAM — 25 November 2026 (WIB)

Ringkasan satu halaman untuk pemilik. Semua verifikasi otomatis sudah selesai; yang tersisa hanya
langkah yang secara eksplisit menjadi keputusan/aksi pemilik.

## Build dan lingkungan yang berlaku

| Item | Nilai |
| --- | --- |
| Android `main` | `7a41707` (kode terakhir `112c556`), `HEAD == origin`, worktree bersih |
| Backend `main` | `54618f9`, `HEAD == origin`, worktree bersih; CI `45609200445` + deploy-host `45609200337` success |
| APK terpasang | sha256 `54095330…`, 47.746.357 B (varian `debug`, host HTTPS) |
| Host UAT | `https://pam-70-153-86-163.sslip.io` — `/healthz` + `/readyz` **200**, TLS valid s.d. Feb 2027 |
| DB UAT | Turso `pam-azure-uat` — **876** transaksi `EMAIL`, **20** job sinkron COMPLETE, 1 koneksi Gmail |
| SRS baseline | DOCX `87617571…`, PDF `5d18570b…` (tidak diubah) |

## Sudah PASS (bukti ada di repo)

- Integritas `main` kedua repo, CI hijau, worktree bersih.
- Build UAT + APK di HP fisik; smoke Ringkasan, Transaksi, Pengaturan, Analisis, dan API publik.
- Anggaran/Lampiran/Administrasi **non-aktif** (rute 404, tidak muncul di UI).
- FR-67: antrean kategori tertahan sampai backfill selesai, lalu drain terbatas.
- NFR-15: tanggal masa depan 400, hari ini 201, retry idempoten.
- G14 fixture hapus/restore **59/59 PASS** pada binary `54618f9`.
- Polling Gmail otonom ±15–16 menit (job COMPLETE 19:25:53 WIB tanpa app aktif).
- Deploy host + CI/CD + rollback otomatis; aplikasi tetap jalan setelah kabel USB dicabut.
- Optimasi token Azure: plafon harian/bulanan aktif, pemakaian terukur jauh di bawah plafon.

Bukti utama: [verifikasi ulang gate](GATE_RECHECK_2026-11-25.md) · [log harian](../PROGRESS_DAILY.md).

## Sisa aksi pemilik

1. **G36 — onboarding Gmail + email Livin alami.** Detail: [runbook G36](OWNER_G36_RUNBOOK_2026-11-25.md).
   Ringkas: Run Android Studio → login akun Google uji → **Lewati** → **Tautkan Gmail + consent** →
   force stop → kirim 1 email Livin alami → tunggu ≤15 menit → buka app → pastikan **tepat +1
   transaksi** dengan nominal benar. Baseline verifikasi: 876 transaksi `EMAIL`.
2. **G29/G37 — Billing GCP.** Pastikan project OAuth tidak tertaut Billing (tanpa Pub/Sub/Cloud Run)
   dan tagihan **Rp0**. Kirim tangkapan halaman Billing/Quotas.
3. **OI-27 — keputusan pemetaan akademik.** Lihat [memo OI-27/OI-28](DECISION_MEMO_OI27_OI28_2026-11-25.md);
   OI-28 sudah diputuskan lewat CR-009.
4. **Deploy produksi & SRS resmi.** Merge branch kerja sudah selesai; deploy produksi dan revisi SRS
   resmi tetap keputusan pemilik.
5. **Opsional — G14 drill pada data pemilik** (butuh izin salinan DB terpisah; jangan pada DB live).

## Setelah pemilik melapor

Agent akan mengulang kueri agregat Turso yang sama dan menutup G36 bila hasilnya **+1 transaksi
`EMAIL`** dengan nominal sesuai email; bila +0/+2, gate tidak lulus dan tidak boleh diulang
berkali-kali. Tidak ada password/OTP/token/email asli/serial perangkat yang boleh dikirim ke chat.
