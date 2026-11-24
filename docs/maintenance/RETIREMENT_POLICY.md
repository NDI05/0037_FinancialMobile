# Kebijakan cleanup dan retirement PAM

Tanggal: **23 November 2026**. Berlaku untuk pembaruan berikutnya dan finalisasi delivery.
Status: instruksi engineering; bukan klaim cleanup seluruh repository sudah dilaksanakan.

## Tujuan

Setiap penggantian fitur meninggalkan satu jalur aktif yang jelas. Implementasi dan dokumen
lama ditangani berdasarkan kegunaannya, bukan dihapus massal karena lebih tua. Kebijakan
ini melengkapi mandat delivery dan dibaca melalui AGENTS.md.

## Keputusan per jenis artefak

| Artefak | Tindakan setelah pengganti terverifikasi |
|---|---|
| Kode/dependency/DI/config/aset obsolete | Telusuri pemakai, hapus melalui perubahan Git dan perbaiki referensi |
| Kode lama masih dipakai interop/rollback | Pertahankan sementara dengan alasan, dependency, kriteria retirement dan commit referensi |
| Runbook/README aktif | Perbarui jalur, startup/build/test, endpoint dan tautan sesuai implementasi aktual |
| Dokumen historis yang masih bernilai | Arsipkan ke `docs/archive/` dengan status/tanggal/pengganti; perbaiki tautan |
| Dokumen duplikat tanpa nilai historis unik | Hapus setelah memastikan versi canonical dan tautan penggantinya |
| SRS resmi | Tetap di lokasi saat ini; perubahan hanya pada draf CR sampai pemilik meminta revisi |
| Migrasi DB yang sudah diterapkan | Pertahankan urutan/checksum; evolusi lewat migrasi baru |
| Regression tests/fixture | Pertahankan perilaku yang masih relevan; jika implementasi pensiun, sediakan pengganti behavior test sebelum menghapus tes spesifik lama |
| Build/cache/log/screenshot sementara | Tidak diarsipkan ke source tree/Git; bersihkan hanya artefak yang diketahui aman dan tidak dipakai proses aktif |
| Secret/data/backup pengguna | Bukan target cleanup otomatis ini; jangan tampilkan atau hapus karena dianggap obsolete |

Jangan membuat folder kode `old`, `backup`, `final2`, atau menyalin backend mati ke dalam
arsip hanya agar terlihat rapi. Riwayat Git adalah penyimpanan default untuk kode yang
sudah dipensiunkan. Arsip dokumen bukan runtime fallback dan tidak boleh dirujuk sebagai
kontrak aktif tanpa penjelasan.

## Alur pada setiap perubahan

1. Tentukan implementasi/dokumen pengganti dan scope yang digantikan.
2. Cari referensi menggunakan `rg`: source/import, DI, Gradle/package dependencies,
   scripts, CI, README, test fixtures dan konfigurasi runtime. File tidak dipanggil dari
   satu layar saja belum membuktikan obsolete.
3. Verifikasi pengganti sesuai risiko: build/test/integration atau acceptance terkait.
   Jangan memensiunkan jalur lama hanya karena pengganti berhasil compile.
4. Hapus artefak obsolete atau arsipkan dokumen historis, lalu perbaiki tautan dan
   perintah. Jika masih diperlukan, catat alasan dan exit criteria konkret.
5. Jalankan pemeriksaan yang terdampak dan `git diff --check`; pastikan tidak ada broken
   imports/links/startup atau regression perilaku yang dibuang bersama tesnya.
6. Commit koheren dan langsung push sesuai AGENTS.md. Jangan stage WIP pihak lain.

Untuk folder besar yang perlu dipensiunkan, gunakan commit terpisah setelah readiness
terbukti. Jangan memakai cleanup untuk memperluas refactor saat defect kritis masih aktif.

## Retire per 24 November 2026: Node keluar dari tree aktif Android

Keputusan PM (mandat pemisahan repo): source Go pindah ke repo `0037-Backend-Financial`
(commit `327d876`, ekstraksi subtree dari `0037_FinancialMobile@cd0f91a`). `backend/` Node
dipindahkan ke arsip filesystem di luar repo pada tanggal yang sama; riwayatnya tetap di Git
Android (`cd0f91a`) dan dapat dipulihkan dengan `git checkout cd0f91a -- backend/`.

- Alasan: satu backend aktif; Node tidak kompatibel dengan schema terbaru dan hanya
  referensi/interop.
- Bukti prasyarat: fixture interop (`fixtures/interop/*.json`) dipindahkan ke repo backend
  dan tes Go lulus dari repo itu; `go vet`/`build`/`test` PASS dari clone bersih.
- Kriteria keluar/rollback: arsip Node dipertahankan sampai parity E2E Android↔Go
  terverifikasi dan pemilik mengotorisasi cutover. Setelah itu hapus arsip fisik; pulihkan
  source hanya lewat riwayat Git bila diperlukan.

## Backend Node dan Go (historis)

- Jalur implementasi aktif: `backend-go/`.
- `backend/` Node saat ini masih dicatat sebagai referensi/interop migrasi. Itu tidak
  membuktikan Node dapat menggantikan Go pada schema terkini.
- Audit sebelum retirement: script/CI/test interop, fixture yang dihasilkan Node,
  kompatibilitas schema/crypto/auth serta runbook pemulihan yang masih merujuk Node.
- Porting fixture ke aset uji mandiri boleh menjadi prasyarat menghapus dependency
  runtime/toolchain Node. Pertahankan bukti interop yang masih diperlukan.
- Setelah dependency ditutup dan keputusan final cutover tersedia sesuai mandat existing,
  kode Node yang pensiun dapat dihapus dari jalur aktif melalui Git. Simpan catatan
  retirement dan commit pemulihan; jangan menjanjikan rollback instan ke database baru.
- Tidak menjalankan dua migration runner pada database yang sama.

**Belum dilakukan dalam perubahan kebijakan ini:** pemindahan/penghapusan backend Node,
cutover, pemindahan SRS, penghapusan file runtime/credential, atau cleanup WIP SE aktif.

## Format catatan cleanup dalam checkpoint

| Item | Keputusan | Pengganti/alasan dipertahankan | Bukti/exit criteria |
|---|---|---|---|
| Path atau dependency | DELETE / ARCHIVE / KEEP | Path canonical atau dependency aktif | Check relevan atau kondisi retirement |

Untuk arsip dokumen, header minimal berisi tanggal, status **HISTORICAL/SUPERSEDED**,
scope yang digantikan dan tautan dokumen aktif. Jangan memindahkan dokumen baseline,
evidence acceptance atau requirement yang masih menjadi rujukan tanpa memperbaiki
traceability. Bukti dengan path absolut lokal yang sudah tidak tersedia harus ditandai,
bukan direkayasa ulang sebagai bukti lama.
