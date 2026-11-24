# UAT aksesibilitas S4 — 24 November 2026

Dokumen ini mencatat bukti aksesibilitas untuk gate S4 (persyaratan SRS "aksesibilitas" pada
UI Compose). Lingkup yang dijalankan **otomatis** adalah audit semantik Compose; pemeriksaan
manual TalkBack, font scale, dan switch access tetap **NOT RUN** dan dicatat sebagai batas,
bukan diklaim PASS.

- Branch `feat/c2-android`, HEAD saat bukti dibuat `8f426bd`.
- Alat: Compose UI test (Robolectric) untuk audit semantik + `:app:lintDebug` untuk kategori
  aksesibilitas; tidak memakai data pemilik.
- Hasil: **PASS otomatis** (3 layar inti), **NOT RUN** manual.

## Metode

1. Helper baru `app/src/test/java/com/pam/recap/testing/AccessibilityAssertions.kt`
   (`assertEveryClickableControlIsLabeled`) mengiterasi seluruh node dengan aksi klik:
   - node tanpa ukuran (belum ter-layout, mis. bagian sheet/dialog tersembunyi) dilewati;
   - node lulus bila punya `Text`, `ContentDescription`, atau `EditableText`, **atau** salah
     satu turunannya punya label (kartu yang bisa diklik dengan label anak);
   - audit dinyatakan tidak valid bila tidak ada satu pun node klik yang ter-layout.
2. Audit ditempel di suite layar inti:
   - `AdminScreenTest` — layar Admin (pencarian, chip peran/status, baris akun, aksi akun);
   - `TransactionAttachmentsSectionTest` — panel lampiran dengan satu baris PNG (tambah,
     cari, ganti nama, hapus);
   - `TransactionDetailScreenTest` — detail transaksi dengan editor field dinamis dan slot
     lampiran.
3. `:app:lintDebug` diperiksa untuk kategori aksesibilitas (mis. `ContentDescription`,
   `ClickableViewAccessibility`, `SpeakableTextPresent`).
4. Audit statis kode: jumlah pemakaian `contentDescription` dan dekoratif `null`.

## Hasil

| Pemeriksaan | Hasil | Bukti |
|---|---|---|
| Node klik tanpa label — layar Admin | **0** | `AdminScreenTest.every clickable control exposes an accessible label` lulus |
| Node klik tanpa label — panel lampiran | **0** | `TransactionAttachmentsSectionTest.every clickable control exposes an accessible label` lulus |
| Node klik tanpa label — detail transaksi | **0** | `TransactionDetailScreenTest.every clickable control exposes an accessible label` lulus |
| Temuan awal saat audit dijalankan | 3 node tanpa label, semuanya berukuran nol (satu toggle dan dua field teks di bagian sheet yang belum tampil), sehingga bukan kontrol yang terlihat; tidak ada kontrol terlihat tanpa nama | riwayat uji 24 Nov (assertion menyebut id/bounds/keys) |
| Kontrol negatif (memastikan audit tidak kosong) | `Box` 48 dp yang bisa diklik tanpa teks/deskripsi **menggagalkan** audit sesuai harapan (uji sementara dijalankan lalu dihapus, tidak masuk commit) | `TmpA11yNegativeTest` 24 Nov, exit 0 karena assertion gagal tertangkap |
| Lint kategori aksesibilitas | **0 issue** dari 113 issue lint (0 error/112 warning/1 hint) | `app/build/reports/lint-results-debug.xml` |
| Pemakaian `contentDescription` di `app/src/main/java` | 59 pemakaian di 24 berkas; 19 `contentDescription = null` (ikon dekoratif) | `grep -rn contentDescription app/src/main/java` |
| TalkBack penuh, font scale 1.3/2.0, switch access | **NOT RUN** | butuh sesi manual di perangkat/emulator dengan TalkBack aktif |

## Batas dan tindak lanjut

- Audit otomatis membuktikan **nama kontrol** (text/content-desc), bukan kualitas
  pengumuman TalkBack, urutan fokus, target sentuh minimum, atau kontras warna.
- Perlu sesi manual sebelum gate S4 dinyatakan penuh untuk aksesibilitas: TalkBack pada
  Ringkasan → Transaksi → Detail → Analisis, font scale 1.3 dan 2.0, serta switch access.
- Temuan tipe "kontrol terlihat tanpa label" akan menggagalkan audit otomatis, jadi regresi
  baru pada 3 layar inti akan tertangkap di CI lokal (`:app:testDebugUnitTest`).
