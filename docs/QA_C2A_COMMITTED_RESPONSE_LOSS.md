# QA C2A — respons hilang sesudah commit

Tool ini khusus QA lokal dengan akun/database sintetis. Tidak menambahkan hook ke API/Android
produksi dan tidak dikemas dalam APK. Jangan arahkan ke database atau akun asli pemilik.

## Apa yang sudah dibuktikan

PM menjalankan binary Go existing melalui proxy dan Python HTTP client dengan database sementara
baru. Request create pertama diakui upstream HTTP 201, lalu proxy menutup koneksi downstream
sebelum status/header/body dikirim. Client mengalami putus respons, retry memakai key/body sama
mendapat 200, dan database tetap satu transaksi/satu month revision/satu idempotency record.
Create identik sebagai aksi baru dengan key berbeda mendapat 201 dan menjadi dua row/revisi,
sehingga proxy tidak memutus semua respons dan idempotensi tidak menggabungkan dua intent berbeda.

Evidence ada di `docs/evidence/c2a_proxy_backend_smoke.json` dan `.events.jsonl`.
**Ini PASS untuk Go + proxy + Python client; Android UI acceptance masih OPEN.**

## Tool dan perilakunya

- `scripts/qa/committed_response_proxy.py`: forward HTTP ke upstream loopback yang ditentukan.
- `--drop-once`: hanya fresh `POST /v1/transactions` pertama yang mendapat 201 upstream
  diputus setelah respons upstream selesai dibaca. Replay 200 tidak menghabiskan arm tersebut.
- Request auth/consent/read tetap diteruskan, tetapi tidak dicatat oleh proxy.
- Evidence hanya mencatat run ID, attempt counter, hash key/body/result ID, status dan waktu.
  Tidak mencatat body, nominal, credential, raw key, receipt atau path sensitif.
- Evidence file wajib baru setiap run; tool menolak overwrite agar hasil dua percobaan tidak campur.
- Proxy bind loopback; Android emulator dapat memakai `10.0.2.2` atau adb reverse yang sesuai.

## Jalankan smoke backend secara terisolasi

Dari root `pam-app`

```bash
cd ../0037-Backend-Financial
../scripts/go.sh build -o /tmp/pam-qa-proxy-api ./cmd/api
cd ..
python3 scripts/qa/verify_committed_response_proxy.py \
  --binary /tmp/pam-qa-proxy-api \
  --output /tmp/pam-proxy-smoke-UNIQUE.json
```

Ganti UNIQUE untuk setiap run. Script membuat dan membersihkan database/backend sementara miliknya.
Tidak membaca `.env`, memakai credential nyata atau menyentuh `/tmp/pam-gate.db`.

## Jalankan dengan Android QA

1. Gunakan AVD/app QA dan database/akun sintetis yang terpisah dari pengujian akun asli pemilik.
   Jangan `pm clear`, uninstall, delete workspace atau revoke credential pada aplikasi/akun asli.
2. Jalankan Go development pada port terpisah, misalnya 18080, dengan DB temporer baru dan fixture
   yang jelas. Catat baseline rows dan `month_revisions` per owner/bulan.
3. Jalankan proxy

```bash
python3 scripts/qa/committed_response_proxy.py \
  --upstream http://127.0.0.1:18080 --port 18081 \
  --drop-once --evidence /tmp/pam-android-lost-response-UNIQUE.jsonl
```

4. Arahkan build QA ke `http://10.0.2.2:18081` atau koneksi adb reverse yang benar, bukan ke port
   API langsung. Jangan mengubah endpoint aplikasi asli pemilik untuk fault injection.
5. Dari form baru, isi nilai/deskripsi unik dan pastikan field lama tidak ter-append akibat restore.
   Kirim satu aksi dengan key baru. Tunggu outcome; jika UI gagal, gunakan retry aksi yang sama.
6. Bila client retry otomatis dan UI langsung sukses, itu **boleh valid**. Jangan mensyaratkan UI
   menampilkan error agar gate lulus; buktikan jejak request pada langkah berikut.
7. Simpan JSONL, screenshot/XML state UI dan snapshot DB/API untuk satu run yang sama.

Gate lost-response lulus jika evidence mengikat seluruh kondisi berikut

| Kondisi | Bukti |
|---|---|
| Request pertama commit | upstream 201 + `upstream_complete=true` + row/idempotency baru |
| Respons pertama benar-benar tidak diterima | `downstream=dropped_before_headers` untuk attempt pertama |
| Retry nyata | attempt kedua, manual/otomatis, dengan `key_sha256` dan `body_sha256` sama |
| Replay acknowledged | upstream 200 dan result ID hash sama; UI menggunakan hasil yang sah |
| Tidak duplikat | baseline N → N+1 setelah commit → tetap N+1 setelah retry |
| Revisi benar | baseline month revision R → R+1 → tetap R+1 setelah retry |
| Lifecycle Android | pending key/field tetap benar, tidak dibangkitkan sebagai aksi baru |

Jika hanya ada satu POST yang diputus tetapi UI terlihat sukses, atau proxy tidak mencatat drop,
hasilnya belum cukup untuk gate. Selidiki apakah UI lama, endpoint melewati proxy, atau instrumentasi
tidak mengenai request yang dimaksud. Jangan mengubah hasil menjadi PASS tanpa jejak yang konsisten.

## Gate refresh <5 detik merupakan kasus berbeda

Jalankan tanpa drop atau dengan proxy mode observasi (tanpa `--drop-once`). Gunakan fresh action/key
dan deskripsi unik. Catat baseline N/R, tap save, lalu bukti row baru serta summary yang berubah pada
instance/navigation yang sama dalam <5 detik. Jangan restart, mengganti bulan atau menunggu timeout
subscriber untuk memicu refresh.

Menemukan label generik `1 transaksi` ketika sebelum/sesudah sama-sama satu record belum membuktikan
mutation baru terlihat. Bedakan `transactions.revision` (record baru dapat 0) dari
`month_revisions.revision` (naik ketika create baru commit). Kaitkan timestamp, owner/bulan, request,
record baru dan UI dalam satu run.

## Akun asli pemilik

Pemilik boleh mengetik sendiri email asli dan password khusus PAM di aplikasi untuk uji login akun.
Alamat Gmail tidak membuat password utama Google berlaku sebagai password PAM. Koneksi Gmail IMAP
menggunakan Google App Password sesuai alur produk; credential tidak dikirim ke chat/source/log.

Otorisasi login manual itu tidak mengizinkan agent memutus jaringan, menghapus data, memakai proxy
QA, menjalankan sweep/deletion atau melakukan clear-data pada akun/perangkat asli. Gate reliabilitas
tetap memakai akun/AVD/database sintetis terisolasi. Login berhasil bukan bukti import/retry/CRUDS lulus.

## Android gate — PASS 22 November 2026

- App build: `http://10.0.2.2:18081` QA proxy, Go `18080`, DB `/tmp/pam-c2a-gate2.db`.
- Baseline: transactions=0, month revision=0.
- Attempt 1: proxy `downstream=dropped_before_headers`, upstream 201,
  `upstream_complete=true`; DB transactions=1, revision=1, idempotency=1.
- UI after drop: form retained, explicit “Tidak dapat terhubung…” error.
- Attempt 2 (same form/key): proxy `downstream=forwarded`, upstream 200,
  same key_sha256, body_sha256 and result_id_sha256; DB transactions=1,
  revision=1, idempotency=1; UI moved to the transaction list.
- Evidence: `docs/evidence/c2a_android_lost_response_gate2.jsonl`.
- This gate is **PASS for Android UI + QA proxy + Go/DB**.
