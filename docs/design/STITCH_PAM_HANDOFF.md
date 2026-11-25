# Handoff Android dari Stitch

Ekspor pemilik disimpan utuh di [stitch_pam_finance_app_prototype](stitch_pam_finance_app_prototype/), berisi 16 prototipe HTML, satu sistem desain Markdown, dan satu logo SVG. [Mandat DeepSeek](../agent-prompts/PROMPT_SE_STITCH_PAM_REDESIGN.md) berisi urutan implementasi dan acceptance.

| Referensi | Lokasi dalam folder ekspor |
|---|---|
| Sistem desain | pam_design_system.md |
| Masuk dan Gmail | 1._masuk, 2._tautkan_gmail |
| Ringkasan dan Transaksi | 3._ringkasan, 4._transaksi, 5._detail_transaksi, 6._tambah_transaksi |
| Analisis, Pengaturan, dan Kategori | 7._analisis, 8._pengaturan, 9._kelola_kategori |
| Kartu bank | eksplorasi_kartu_bank_opsi_a_opsi_b |
| Keadaan transaksi dan sinkronisasi | keadaan_transaksi_loading_kosong_gagal, keadaan_sinkronisasi_semua_state |
| Komponen dan aksesibilitas | komponen_pam_dapat_dipakai_ulang, pemeriksaan_360_dp_412_dp_font_200 |
| Widget dan notifikasi | widget_layar_utama_launcher_pemilih, notifikasi_transaksi_panel_layar_kunci |
| Logo | logo_financialrecap/screen.svg |

HTML adalah referensi desain dan memakai CDN untuk sebagian tampilan. Implementasi Android menggunakan Compose dan aset lokal. Ekspor menyimpan opsi lama agar jejak desain dapat diperiksa. Untuk keputusan terbaru pilih opsi B, yaitu carousel di Ringkasan dengan satu kartu per bank dan jumlah transaksi pada periode terpilih. Mandiri tahap pertama. Jago dan BRI hanya data prototipe sampai tersedia dukungan nyata.

Gunakan [CR-010](../changes/CR_010_DRAFT_HOME_WIDGET_TRANSACTION_NOTIFICATIONS.md) untuk widget dan notifikasi. Untuk cakupan dan perilaku layar lain, periksa SRS final v0.7, keputusan pemilik yang lebih baru seperti CR-009, serta kontrak aktual. Aset dan rancangan sebelum ekspor ini bisa ditelusuri di folder desain dan riwayat Git.
