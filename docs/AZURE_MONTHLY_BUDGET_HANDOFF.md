# Azure PAM — budget USD10/bulan

Owner meminta batas Foundry USD10/bulan pada 22 November 2026.

## Konfigurasi cloud terverifikasi dengan GET

- Resource: `pam-ai-student-74eab38`, resource group `rg-pam-ai-student`.
- Foundry cost control: `pam-monthly-10usd`, API `2026-11-15-preview`.
  Category `Cost`, amount `10` USD, reset `Monthly`, satu budget bersama
  untuk seluruh penggunaan resource yang didukung cost controls.
- Action `Block`, `GreaterThanOrEqualTo`, `100` percent.
- Aktif dari `2026-11-22T14:35:01Z` sampai sebelum `2027-11-23T00:00:00Z`.
  Perpanjang sebelum kedaluwarsa; jangan ubah periode/grouping untuk
  mengakali penghitung.
- Cost Management budget RG: `pam-foundry-monthly-10usd`, amount `10`,
  unit `USD`, `Monthly`, periode mulai `2026-11-01T00:00:00Z` dan end sama.
  Notifikasi Actual ke role `Owner` pada 50%, 80%, 100% (USD5/8/10).
  Pengiriman notifikasi belum diuji. Tidak membuat Event Grid/compute tambahan.

## Batas bukti dan tindakan SE

PUT dan GET berhasil; ini bukti konfigurasi, bukan pengujian penghentian
request model. Tidak melakukan inferensi tambahan atau sengaja menghabiskan
budget untuk membuktikan pemblokiran.

Dokumentasi preview menyatakan dukungan GPT/OpenAI o-series. Deployment PAM
adalah `gpt-oss-120b`, format `OpenAI-OSS`; cakupan enforcement untuk deployment
ini belum terverifikasi. Jangan mengasumsikan semua model tercakup hanya
karena pembuatan policy berhasil. Konfigurasi memerlukan sampai 15 menit
untuk berlaku; request yang melewati threshold dapat selesai, pemeriksaan
yang timeout dapat fail-open, dan total cost control bukan rekonsiliasi tagihan.
Cost Management budget sendiri hanya memberi peringatan.

Budget GET menunjukkan currentSpend USD0; query biaya month-to-date belum
memiliki baris. Ini bukan bukti tidak ada pemakaian atau saldo kredit masih utuh.

Jangan menaikkan budget runtime classifier hanya karena cloud ceiling USD10.
Target pilot PAM USD0,10/hari dan USD2/bulan tetap lebih ketat. SE tetap wajib
menegakkan durable atomic reservation, request/token caps, retry terbatas,
cache/coalescing, dan fail-closed sesuai mandat classifier. File env privat
dan feature flag tidak diubah dalam pekerjaan budget cloud ini.

Bedakan HTTP429 `x-ms-budget-cause: budget_exceeded` dari throttling transient:
jangan retry sampai reset/otorisasi perubahan budget; tampilkan status jelas
dan pertahankan transaksi finansial. Jangan menjadikan cloud cost control
pengganti gate backend yang sudah dimandatkan.

Untuk testing personal dengan payload minimal dan cache, USD10 adalah
alokasi awal yang masuk akal, bukan jaminan kapasitas. Hitung kebutuhan dari
token input/output/reasoning, harga deployment terverifikasi, retries, dan
volume merchant unik. Jangan memakai tarif atau throughput asumsi sebagai
hasil benchmark aktual.

## Referensi resmi

- https://learn.microsoft.com/azure/foundry/concepts/cost-controls
- https://learn.microsoft.com/azure/foundry/how-to/create-cost-controls
- https://learn.microsoft.com/azure/cost-management-billing/costs/tutorial-acm-create-budgets
