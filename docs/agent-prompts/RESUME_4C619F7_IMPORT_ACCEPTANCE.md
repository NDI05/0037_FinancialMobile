# Prompt lanjutan SE — tutup defect 4c619f7 dan buktikan impor end-to-end

Kamu adalah Software Engineer PAM. Lanjutkan penugasan S2–S4 yang masih aktif. Parser split WIB
dan uploader awal sudah ada; jangan membangunnya ulang atau berhenti pada laporan PARTIAL lain
selama pekerjaan implementasi independen masih dapat dilakukan.

## Mulai dari kondisi aktual

- Root Git `/Users/user/Documents/Gabut-Code/Pertemuan_KeenamBelas_TugasAKhir/pam-app`, branch
  `feat/c2-android`. Checkpoint worker `4c619f7` lokal/remote bersih saat review PM; commit review/
  prompt PM akan sesudahnya. Baca HEAD/status aktual dan pertahankan seluruh commit/WIP yang sah.
- Baca `AGENTS.md`, `docs/reviews/PM_REVIEW_4C619F7.md`,
  `docs/agent-prompts/RESUME_LIVIN_S2_S4.md`, `docs/agent-prompts/MANDAT_S2_S4.md`,
  living checkpoint dan kontrak API/SRS v0.4. Prompt ini memperinci acceptance berikutnya;
  seluruh scope S2–S4 tetap berlaku.
- **Setiap commit langsung push** ke branch yang sama, verifikasi remote. Tidak ada force-push,
  reset destruktif, merge/deploy, atau penghapusan data pemilik. Jangan meminta izin push ulang.
- Pertahankan C1/F1–F4/gate A/consent/R1/Manrope/OFL dan QA proxy di `0d7ba18`. Perbaikan lokal
  dan tests memakai data/akun/app/AVD/DB sintetis terpisah dari akun asli.

## A. Perbaiki defect parser sebelum memperluas klaim PASS

PM menjalankan ulang tujuh tests targeted dan semuanya PASS, tetapi probe pada method parser
aktual menemukan `31 Nov 2026` menjadi 30 November, dan `24:00 WIB` menjadi 00:00. Lihat JSON
evidence review. Gunakan parsing strict pada split-date/time serta combined datetime agar nilai
invalid ditolak, bukan dinormalisasi. Pertahankan format valid lama dan expected
`19 Nov 2026 09:15:20 WIB → 2026-11-19T02:15:20Z`.

Tambah regression melalui public parser dan MIME boundary untuk tanggal mustahil, leap year,
jam invalid, pasangan hilang, WITA/WIT, whitespace/NBSP dan field ambiguity. Jangan mengendurkan
status check untuk dua email sebelumnya tanpa verifikasi template. BI Fast tetap unsupported
jika body/arah/status belum mempunyai bukti tersanitasi; jangan menebak dari judul.

## B. Jadikan import remote dapat dipulihkan dan counts-nya benar

1. **Recovery UI:** `retry()` saat ini tidak memanggil uploader; local COMPLETE juga menonaktifkan
   retry saat upload gagal. Pisahkan parse complete dari upload pending/failed/acknowledged.
   Sediakan retry/resume yang benar-benar meneruskan operasi remote tanpa mengulang intent yang
   sudah commit. Busy/error/finally/cancellation dan account switch harus menjaga state yang benar.
2. **Journal durable:** simpan owner, intent/job remote ID, batch sequence, immutable sanitized
   payload/key atau material pemulihan setara yang cukup, ack progress dan finalization state
   secara privat dan dicek keberhasilan tulisnya. Jangan kirim bila pending state belum durable.
   Jangan mengandalkan job ID dalam variabel ViewModel atau selalu membuat server job baru.
   Tuntaskan recovery create-job response loss sesuai kontrak; bila perlu perluas kontrak/API
   secara minimal dengan owner-scoped operation identity/recovery yang idempoten.
3. **Stream kandidat:** jangan membaca semua row EMAIL bulan tersebut sebagai pengganti isi job.
   Hubungkan kandidat hasil parse ke batch server sebelum reason/counts hilang. Rejected/unresolved
   disampaikan memakai opaque fingerprint/reference dan reason code sesuai kontrak, tanpa raw ID
   atau email. Dedupe lokal lama tidak boleh menghalangi kandidat mencapai server pada reimport.
4. **Counts/finalisasi:** simpan dan tampilkan counts/revision/coverage dari server. Buat invariant
   job/pages/candidates/final-state konsisten. Uji semua-rejected, empty mailbox, duplicates-only,
   campuran parsed+rejected, kegagalan fetch dan partial batch. COMPLETE tanpa satu batch tidak
   boleh dikirim lalu dianggap sukses. Jangan menandai all-rejected sebagai EMPTY untuk membuang
   informasi matched/skipped. UI harus menjelaskan `0 tersimpan; N dilewati` meskipun pemrosesan
   seluruh halaman selesai, dan tidak mengklaim local COMPLETE sebagai remote complete.
5. **Kategori:** jangan menelan GET categories failure menjadi empty list lalu diam-diam mengirim
   categoryId null. Bedakan lookup failed dengan hasil valid tanpa mapping; gunakan kebijakan
   kontrak yang eksplisit dan recovery yang dapat diuji. Preserve custom IDs/provenance.
6. **Fingerprint:** hubungkan account key stabil dari server ke HMAC perangkat dengan storage
   wrapped/owner-scoped, dan strategi transisi yang jelas. Buktikan same-email dedupe setelah
   restart API/app, clear-data/reinstall sintetis/login ulang dan dua owner. Jangan mengklaim
   master random perangkat sebagai account key server, atau mengunggah raw message IDs.
7. **Refresh:** sesudah remote ack, invalidasi list/summary/analysis untuk owner+bulan yang benar.
   Uploader sekarang belum memanggil refresh bus. Buktikan data baru tampil di instance aplikasi
   yang sama; jangan memicu hasil dengan restart atau mengganti bulan.
8. **Finalize error:** teruskan errorCode yang sudah disediakan port repository ke DTO/API dan
   readback state yang relevan. Jangan membuangnya atau memalsukan remote reason dari state lokal.

Local Room journal/uploader boleh dipakai hanya sebagai transisi koheren. Target C2B1 dan S4
tetap server source-of-truth dan pengeluaran Room/SQLCipher dari varian penilaian. Jangan
menambah fallback lokal untuk menyembunyikan server failure atau menghapus data nyata.

## C. Jalankan acceptance fixture → Go → UI sampai benar

Gunakan fixture mailbox yang diketahui expected results-nya, Go lokal dan DB uji baru. Buat
evidence satu run yang mengikat owner/bulan/run ID, request/ack job+batch, rows/revisions dan UI.

Wajib dibuktikan:

- MIME split-date/time valid → kandidat tersanitasi → Go job/batch → DB → GET list/detail/summary
  → Ringkasan/Transaksi. Counts lokal/server/UI direkonsiliasi, nilai exact minor dan Jakarta benar.
- Reimport tidak menambah row email yang sama, mempertahankan koreksi dan soft delete. Dua owner
  tetap terisolasi. Empty/all-rejected/duplicates-only tidak memberi success/coverage palsu.
- Respons create-job, batch dan finalize hilang setelah commit; process death/restart;
  resume melanjutkan identity/payload yang sama dan tidak menaikkan row/revision dua kali.
- Local parse COMPLETE lalu server unavailable, kategori GET gagal dan finalize gagal:
  error UI benar, retry tersedia dan benar-benar mencapai server setelah pulih.
- Consent withdrawal/deletion race, owner switch dan late response tidak menulis/menerbitkan
  hasil pada akun yang salah. Tidak ada auth secrets/raw email/ID/real finance di evidence.

Unit FakeServer tidak menutup acceptance Android→Go→DB. Jika akses Gmail nyata belum tersedia
untuk worker, tests fixture tetap dijalankan. Siapkan UAT nyata terpisah setelah jalur sintetis
lulus; otorisasi baca mailbox PM bukan akses otomatis worker atau izin QA destructive akun asli.

## D. Tutup C2A dan lanjutkan sisa S2–S4

Gunakan `docs/QA_C2A_COMMITTED_RESPONSE_LOSS.md` dan proxy standalone yang sudah tersedia.
Jangan mengulang narasi proxy lama sebagai blocker. Backend smoke sudah PASS; Android lost-
response masih OPEN. UI success setelah drop boleh valid bila trace membuktikan retry otomatis,
key/body/result sama, row N→N+1→N+1 dan month revision R→R+1→R+1.

Refresh 2,53 detik tetap observasi worker; rekonsiliasi fresh mutation/key/row dan list+summary
<5 detik dalam satu run. Bedakan record revision dari month revision.

Setelah irisan di atas koheren, commit/push dan langsung lanjut pagination/filter/search >100,
update/delete/409, budget/analysis/freshness/deletion receipt UI; kemudian S3 CRUDS kategori/
anggaran/transaksi dan dynamic fields; S4 lampiran/admin/audit/25 aksi serta audit dependency/APK
Room removal. Seluruh batas SRS, privacy, owner scope dan visual R1 tetap berlaku. Jangan
mengubah penugasan gabungan menjadi hanya satu parser/uploader patch lagi.

## E. Cara membuktikan selesai

Jalankan targeted regression selama implementasi, lalu Android unit/assemble/lint dan Go fresh
tests/race/vet/build jika backend berubah. Migration/interop bila terdampak. Historical 250/42/124
bukan run baru. Jangan menonaktifkan assertions/checks atau mengganti FAIL/NOT RUN menjadi PASS.

Update living checkpoint dan matriks requirement→implementation→evidence. Rekonsiliasi tabel
lama dengan status terbaru agar tidak ada NOT DONE dan PASS untuk hal sama tanpa konteks waktu.
Laporkan commit/push, defect yang ditutup, actual commands/results, evidence import/recovery/C2A,
25 aksi, empat entitas/dynamic fields, R1 dan storage audit. Scope yang belum dikerjakan tetap
PARTIAL/NOT RUN dengan dependency spesifik; owner visual UAT dan external Sprint 5 terpisah.

**Implementasikan sekarang. Jangan mengakhiri run setelah menambahkan test happy path atau
menulis checkpoint. Lanjutkan sampai acceptance S2–S4 terpenuhi, atau sampai batas runtime/
hambatan eksternal nyata telah dicatat dengan pekerjaan independen yang tersedia dituntaskan.**
