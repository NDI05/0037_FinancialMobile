# PAM — instruksi kerja agent

## Git dan otorisasi pemilik

Pemilik mengklarifikasi pada 22 November 2026 bahwa push sebelumnya memang diotorisasi,
dan menginstruksikan: **setiap commit langsung dipush ke branch kerja yang sama**.

- Setelah commit berhasil, segera push ke branch yang sama di `origin`; gunakan upstream yang
  sesuai atau tetapkan upstream pada push pertama. Verifikasi hasilnya dan laporkan hash/ref.
- Aturan ini menggantikan larangan push pada prompt/checkpoint/review PAM yang lebih lama.
  Tidak perlu meminta izin push lagi untuk setiap commit.
- Commit perubahan yang koheren dan pertahankan pekerjaan aktif pihak lain. Instruksi ini
  tidak berarti otomatis men-stage atau membuat commit dari semua WIP yang belum selesai.
- Jika push gagal, pertahankan commit, tangani penyebab yang aman dan catat status aktual.
  Jangan menggunakan force-push, reset destruktif atau menghapus history untuk memaksakan sinkronisasi.
- Merge dan deployment tetap memerlukan instruksi pemilik tersendiri. Push feature branch
  tidak sama dengan penerimaan gate sprint atau izin merge/deploy.

## Delivery aktif

Mandat gabungan S2–S4 berada di `docs/agent-prompts/MANDAT_S2_S4.md`.
Living checkpoint berada di `docs/DELIVERY_S2_S4_CHECKPOINT.md`.
Lanjutkan dari status repo/checkpoint terkini dan jaga perbedaan PASS/PARTIAL/NOT RUN.
