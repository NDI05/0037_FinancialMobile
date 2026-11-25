# Diagnosis — kategorisasi AI tidak berjalan di UAT lokal (25 November 2026, WIB)

## Gejala

Di aplikasi UAT lokal, transaksi tidak memperoleh kategori otomatis dari AI; antrean/klasifikasi
tidak menghasilkan apa pun.

## Penyebab (terverifikasi dari konfigurasi, bukan asumsi)

1. Launcher UAT loopback pemilik `~/.config/pam/uat-runtime/start.py` sengaja mematikan classifier:
   `CATEGORY_LLM_ENABLED=false` dan `LLM_PROVIDER=fake` (komentar di file: “Azure/classifier
   deliberately off for the Gmail Rp0 gate (no external AI calls)”).
2. Dengan `CATEGORY_LLM_ENABLED=false`, `cmd/api/main.go` tidak membangun `CategoryClassifier`, dan
   `autoCategoryEnabled()` di `internal/httpapi/handlers_category_queue.go` mengembalikan `false`.
   Semua jalur auto-kategori (enqueue/drain) menjadi tidak aktif.
3. Kategorisasi otomatis juga bergantung pada consent `AI_CATEGORIZATION` berstatus `GRANTED` dan
   pada backfill Gmail awal selesai; tanpa dua hal itu pun antrean tidak berjalan.
4. Kredensial Azure classifier tidak ada di env UAT loopback (`~/.config/pam/gmail-uat.env` berisi kunci Gmail/SSO serta
   setelan DB/JWT, tetapi tidak memuat kredensial Azure); kredensial classifier ada di file env privat terpisah
   `~/.config/pam/azure-classifier.env`.

## Kesimpulan

Ini **bukan bug aplikasi**: build/endpoint kategori benar, tetapi runtime UAT lokal dikonfigurasi
hemat biaya (Rp0) sehingga tidak ada panggilan AI. Analisis AI juga memakai `LLM_PROVIDER=fake`
pada runtime ini.

## Cara mengaktifkan (butuh persetujuan biaya pemilik)

Jalankan backend dengan classifier Azure aktif, misalnya:

```bash
set -a; . ~/.config/pam/azure-classifier.env; set +a
CATEGORY_LLM_ENABLED=true CATEGORY_LLM_PROVIDER=azure \
  # plus env Gmail/SSO yang sama seperti start.py
  ./pam-api-loopback
```

Lalu di aplikasi: berikan consent **AI_CATEGORIZATION** (Pengaturan → consent kategori), tunggu
backfill Gmail selesai, dan jalankan sinkronisasi/antrean kategori. Batas biaya (`CATEGORY_LLM_DAILY_
CEILING_USD`/`MONTHLY`) tetap berlaku. Uji aktif ini **NOT RUN** sampai pemilik menyetujui penggunaan
kuota Azure.
