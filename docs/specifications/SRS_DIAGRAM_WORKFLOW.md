# Alur kerja diagram Excalidraw untuk SRS PAM

## Pasang skill di checkout PAM

Dari root repo Android `pam-app`, jalankan:

```sh
./scripts/docs/setup-excalidraw-skill.sh
```

Setup membutuhkan Git, `uv`, Node.js dengan npm, dan akses internet. Script mengambil
[excalidraw-diagram-skill](https://github.com/coleam00/excalidraw-diagram-skill/tree/8646fcc9f74f38539c6cdb4c969723336a96ddcd)
pada revisi yang dipatok, memasang Playwright dan Chromium, lalu membundel
library Excalidraw secara lokal agar render tidak bergantung pada impor CDN.

- Codex: buka `.agents/skills/excalidraw-diagram/SKILL.md` di repo ini. Mulai sesi
  baru dari checkout `pam-app` agar skill lokal dapat ditemukan.
- DeepSeek dan agent lain: baca path yang sama secara eksplisit berdasarkan
  aturan `AGENTS.md`. Agent yang mencari skill di `.claude/skills/` dapat memakai
  tautan yang menunjuk ke instalasi yang sama.
- Checkout baru perlu menjalankan setup. Git menyimpan script dan tautan skill.
  Paket dari upstream dan virtual environment disimpan lokal.

## Buat dan periksa diagram Appendix A

1. Pakai SRS v0.7 yang sudah dikumpulkan dan keputusan pemilik sebagai acuan
   cakupan. Cocokkan istilah, kunci dan kardinalitas ERD dengan kontrak dan skema
   PAM. Tandai relasi yang masih berupa rancangan. Gunakan data sintetis untuk
   contoh dan hindari kredensial serta data transaksi pribadi.
2. Baca `SKILL.md` dan referensi palet Excalidraw. Buat satu berkas
   `.excalidraw` per gambar. A1 memakai aktor, batas sistem, dan use case.
   A2 memakai entitas, PK/FK dan kardinalitas. A3 memakai awal/akhir, aktivitas,
   serta keputusan yang setiap cabangnya diberi label.
3. Render setiap sumber ke PNG. Contoh dari root repo Android:

   ```sh
   skill_dir="$PWD/.agents/skills/excalidraw-diagram/references"
   diagram="$PWD/docs/specifications/diagrams/A1_Use_Case.excalidraw"
   (cd "$skill_dir" && uv run python render_excalidraw.py "$diagram" --scale 3)
   ```

   Gunakan template yang dikonfigurasi oleh script setup. Simpan hasil
   render dari Chromium lokal di samping sumbernya.
4. Buka PNG dan periksa ukuran teks, garis relasi, label, kontras, dan ruang
   terhadap tepi gambar. Jika ada perbaikan, edit sumber Excalidraw dan render
   ulang. Simpan sumber editable dan PNG dengan nama dasar yang sama di folder
   `docs/specifications/diagrams/` ketika aset diserahkan ke repo.
5. Dalam salinan SRS yang sedang direvisi, tempatkan PNG di **Appendix A** dengan
   caption dan rujukan FR sesuai diagram. Pertahankan A4 Kamus Data sebagai tabel
   editable yang selaras dengan ERD. Perbarui daftar isi dan periksa hasil render
   Word sebelum menyerahkan salinan revisi.
