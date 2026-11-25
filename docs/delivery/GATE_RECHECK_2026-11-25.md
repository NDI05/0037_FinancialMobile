# Verifikasi ulang gate rilis — 25 November 2026 (WIB)

Dokumen ini mencatat verifikasi ulang pasca-merge dari `main` kedua repo, termasuk perbaikan
nominal impor email yang dilaporkan pemilik pada hari yang sama. Baseline requirement tetap
SRS v0.7/CR-008. PASS hanya diberikan untuk perilaku yang benar-benar diuji pada build/lingkungan
yang disebut; sisanya PARTIAL atau NOT RUN.

## Snapshot repo dan build

| Item | Nilai |
|---|---|
| Android `main` | `cbb3db5` == `origin/main`, worktree bersih (kode app tetap `0cf7faf`; sesudahnya hanya dokumen + screenshot bukti) |
| Backend `main` (sebelum perbaikan hari ini) | `4473f38` == `origin/main` saat verifikasi gate |
| Backend `main` (setelah perbaikan nominal) | `d42d7c4` kode + `aa33fb8` dokumentasi + `68f1ba4` tes tambahan + `b6ce917` dokumentasi audit, semua dipush ke `origin/main` (runtime UAT aktif memakai binary `d42d7c4`) |
| APK debug yang dipakai smoke | `app-debug.apk` 47.890.601 B sha256 `5a2f71a1aaf823949a0bc2b62851e43e4f5addfb5167557a90e6f67f13c824e6`; terpasang di emulator-5556 (versionName `1.0.0-debug`, `lastUpdateTime` 2026-11-25 13:04:42) |
| Binary backend diverifikasi | `/tmp/pam-api-main-4473f38` 20.033.298 B sha256 `90411475967de2b8578a1acf22fd9bb65f6d93659cbd0d52a05207af1d8a9561`; binary perbaikan `/tmp/pam-api-d42d7c4` sha256 `4c88bc46d323825784f768a5fc99a2b3a5c0f4e5f2c4a09141bafae5758bc48f`; binary UAT loopback aktif sha256 `77b0963a33b3b846fd65d85125460710e7c3f206e6fd90b74f42c59759d654aa` |
| CI backend | `4473f38`: run `45534425758` **success**; `d42d7c4` + docs `aa33fb8`: run `45559414209` **success**; `68f1ba4`: run `45560831326` **success**; `b6ce917`: run `45560979882` **success** |
| CI Android | tidak ada workflow CI di repo Android (tetap seperti sebelumnya) |

## Tabel gate

| Gate | Status | Bukti (build/lingkungan) | Batas |
|---|---|---|---|
| Integritas repo pasca-merge | **PASS** | Android `main`=`origin`=`0cf7faf` bersih; backend `main`=`origin`, commit perbaikan `d42d7c4`/`aa33fb8` dipush; tidak ada force-push; SRS DOCX/PDF tidak diubah | — |
| FR-67 — backfill awal menahan antrean kategori; setelah selesai batch dibatasi | **PASS** | Runtime terisolasi `4473f38` (port 18091, DB `/tmp`, fake Gmail, classifier stub): `GATE_MONTHS 1/13`; enqueue `200 {"cached":0,"pending":4,"queued":0}`; drain `200 {"drained":0,"pending":4,"state":"not_due"}`; stub 0 panggilan saat backfill belum lengkap; `BACKFILL_COMPLETE_AT 50`; sesudahnya tepat **4** panggilan provider (batch `[2,2,1,2]`), sisa PENDING, poll berikutnya `not_due`, total stub stabil; `FINAL_TX_COUNT 4` | Uji runtime terisolasi, bukan HP pemilik; log `/tmp/pm25_fr67_e2e_final.log` |
| NFR-15 — tanggal masa depan ditolak; entri manual hari ini sebelum 12.00 WIB berhasil; retry identik menghasilkan satu transaksi | **PASS** | Binary resmi `90411475…` pada `127.0.0.1:18090`: future `2026-11-27T00:00:00+07:00` → **400** `outside_retention` (window `2025-11-25T00:00:00+07:00`…`2026-11-25T13:08:18+07:00`); hari ini date-only → **201** `tx_4f7d78467e4ab8b31d8035a091cc0ccf` (`eventAt 2026-11-24T17:00:00Z`); retry identik → **200** id sama `replayed true`; key berbeda → id baru; list tepat 1; cleanup **204** | Transaksi probe sintetis, bukan data pemilik |
| G14 — drill retensi/hapus/restore pada fixture DB terpisah | **PASS (fixture)** | Harness `/tmp/pam-g14/drill.py`, backend dibangun dari `git archive 4473f38`, DB/port terisolasi (18091/18092): **59/59** skenario lulus (termasuk restore, audit, eligible counts); laporan `/tmp/G14_DRILL_2026-11-25.md`; binary drill sha256 `d787663eb03a5c0ad910275f9ee2b03cb08fbc9f3e63a5d4c2f5a6b8185ee3c7` | Drill pada **data pemilik tetap NOT RUN** (butuh izin + salinan eksplisit) |
| G14 — drill pada data pemilik | **NOT RUN** | — | Hanya pemilik yang dapat memberi izin dan menyalin DB |
| G25 — impor email Livin live ke akun pilot | **PASS (bukti ronde sebelumnya)** | Evidence ronde live: backfill 12/12, 914 transaksi unik, 0 duplikat; rekonsiliasi 769 impor + 145 tersimpan = 914 (`docs/evidence/GMAIL_LIVE_IMPORT_2026-11-24.md`, `GMAIL_POLLING_UAT_PHONE_2026-11-24.md`) | Tidak ada email baru pada verifikasi ini; perilaku "email baru saat app tertutup" tetap G36 |
| Satuan nominal impor email (laporan pemilik 25 Nov) | **PASS (perbaikan + verifikasi)** | Commit `d42d7c4`: `go vet` PASS, `go test ./... -count=1` PASS 11 paket, tes parser/format + round-trip fixture + migrasi 022 PASS, E2E fake Gmail PASS (`2_500_000`/`999_000`/`12_345_678` minor utuh), DB UAT lokal: 915 baris EMAIL diskalakan sekali, 10 baris MANUAL utuh, `schema_migrations`=22, `/healthz`+`/readyz` 200. **Bukti UI APK** (emulator-5556, backend terisolasi, data sintetis): Transaksi menampilkan `Rp25.000`, `Rp9.990`, `Rp123.456,78`; Ringkasan KELUAR `Rp158.446,78` (jumlah tepat) | Diterapkan ke UAT lokal, bukan produksi; analisis lama perlu dibuat ulang; uji UI memakai data sintetis di emulator, bukan HP/data pemilik; detail + screenshot `docs/evidence/AMOUNT_UNIT_BUGFIX_2026-11-25.md` |
| Migrasi non-destruktif & rute pensiun | **PASS** | 23 file SQL = **22 versi** migrasi (001–021, dengan `003.triggers.sql` bagian dari versi 003, plus 022 aditif); `TestSchemaHasExactly21Migrations` PASS dengan asersi 22 versi; diff `migrations/db.go` tidak menghapus migrasi lama; 8 path modul pensiun tetap **404** semua method (`TestRetiredModulesAreNotRouted` PASS). Distribusi baris EMAIL pemilik pasca-022: `gmail-livin-v1`=914, `gmail-livin-v2`=1 (semua tercakup migrasi; tidak ada NULL/legacy yang terlewat di UAT) | Migrasi 022 adalah perbaikan data aditif, bukan penghapusan; distribusi versi parser adalah agregat UAT, bukan data pribadi |
| G36 — Run literal Android Studio, login/logout/relogin Google, Lewati/Tautkan Gmail + consent, email Livin baru saat app tertutup | **PARTIAL** | Login/logout/relogin Google di HP fisik sudah terbukti 24 Nov (G36 PARTIAL sebelumnya); smoke emulator hari ini: layar utama/Transaksi/Analisis/Pengaturan terbuka tanpa crash, tidak ada entri Anggaran/Lampiran/Administrasi, banner sesi berakhir muncul tanpa crash | HP pemilik terkunci (`NotificationShade`) sehingga **Run literal, onboarding Gmail pada akun uji, dan email baru saat app tertutup NOT RUN** — hanya pemilik yang bisa membuka kunci |
| G24 — deployment/kesiapan hosting | **NOT RUN** | Catatan kesiapan: `docs/delivery/DEPLOYMENT_READINESS_NOTE_2026-11-25.md` (HTTPS, polling 15 menit, backup/restore, rollback) | Deploy hanya atas instruksi terpisah dari pemilik |
| G29/G37 — biaya GCP Rp0 | **OPEN / NOT RUN** | `gcloud` tidak tersedia di mesin ini (exit 127); audit read-only sebelumnya `docs/evidence/GCP_READONLY_RP0_2026-11-24.md` | Verifikasi billing hanya lewat konsol pemilik |
| Kesiapan deploy (dokumen, bukan deploy) | **PASS (dokumen)** | `docs/delivery/DEPLOYMENT_READINESS_NOTE_2026-11-25.md`: checklist HTTPS, polling, backup, restore, rollback, env wajib | Tidak ada deployment yang dijalankan |
| OI-27 pemetaan akademik (CR-008) / OI-28 provisioning admin | **OPEN** | OI-27: pemetaan D.2/D.5/D.6/D.9 butuh pedoman asli + keputusan pemilik; OI-28: jalur undangan admin hilang, hanya `pam-admin -promote <email>` yang benar-benar ada (dokumen menyebut `bootstrap` — temuan audit P2) | Tidak ada default yang diambil; keputusan pemilik diperlukan sebelum klaim akademik/provisioning |
| Smoke emulator build final | **PARTIAL** | Emulator-5556 (APK `5a2f71a1…`) dengan backend terisolasi + akun sintetis: login email/password, Ringkasan/Transaksi menampilkan 3 transaksi dengan nominal benar, Analisis tersimpan terbuka, sheet Status hanya `GET /v1/gmail/status` (13/13 bulan), Pengaturan tanpa entri Anggaran/Lampiran/Administrasi; tidak ada crash (`logcat` bersih) | Data sintetis + fake Gmail di emulator, bukan HP/data pemilik; bukan pengganti UAT HP fisik/G36 |

## Tindakan yang benar-benar hanya bisa dilakukan pemilik

1. Buka kunci HP dan jalankan aplikasi lewat tombol **Run** Android Studio pada build final.
2. Selesaikan onboarding Gmail (Lewati/Tautkan) dan consent pada akun uji; kirim satu email Livin
   alami, lalu pastikan tepat satu transaksi masuk saat aplikasi tertutup (G36).
3. Putuskan OI-27 (pemetaan akademik CR-008) dan OI-28 (provisioning admin: pertahankan `pam-admin -promote`, perbaiki dokumen, atau buka jalur undangan) sebelum rilis; beri instruksi terpisah bila menginginkan deployment.
4. Buka konsol GCP untuk memastikan tagihan Rp0 (G29/G37).
5. Beri izin eksplisit + salinan DB bila menginginkan drill G14 pada data pemilik.
