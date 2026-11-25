# UAT gap admin C24/C25 (lanjutan) — 23 November 2026

Bukti verifikasi runtime untuk dua kriteria yang sebelumnya berstatus PARTIAL di
`docs/delivery/ACCEPTANCE_C01_C25.md`: **C24 (nonaktifkan akun + pencabutan sesi + guard admin
terakhir)** dan **C25 (cari/filter/halaman daftar akun + gerbang peran + pengecualian DELETED)**.

Pekerjaan dilakukan di worktree terpisah `pam-wt-admin` (branch `work/gap-admin`, HEAD `0e76329`)
dengan backend dan basis data **terisolasi lokal**, tanpa menyentuh backend UAT Turso maupun
emulator pekerja lain (emulator-5554 milik Worker A tidak digunakan).

## Lingkungan

| Komponen | Nilai |
|---|---|
| Backend | binary `api` dibangun dari `backend-go` HEAD `0e76329`, sha256 `17b29c728dbb402c19d361ea7a327ec9d7f246623e27e856c55613a7ee17dd34` |
| Port | `127.0.0.1:18092` (khusus walk ini; backend UAT lama di 18090 tidak diganggu) |
| Basis data | `file:/tmp/pam-gap-admin/pam.db` (SQLite lokal libSQL; **bukan** Turso UAT), 61 baris `users` saat walk selesai |
| Konfigurasi rahasia | disumber dari `~/.config/pam/real-uat.env` dengan `set -a; . file; set +a`; nilai (JWT secret, pepper, invite code) tidak pernah dicetak |
| LLM | `LLM_PROVIDER=fake` (tidak ada panggilan LLM nyata) |
| Bootstrap admin | jalur resmi: `POST /v1/accounts` (USER) → `/tmp/pam-gap-admin/pam-admin -promote gap.admin1@pam-uat.test` (sha256 `5f4e555f53fc0a92a3514af64ce1699004aea32ec2f4fa19eb82b15a8e41aca2`), output `changed=true (promoted)` |
| Admin kedua | jalur kontrak `POST /v1/admin/invitations` role `ADMIN` → akun menerima undangan (role ADMIN) |
| Emulator | `emulator-5556` (AVD `Pixel_10_Pro_XL`, `sys.boot_completed=1`), `adb -s emulator-5556 reverse tcp:18092 tcp:18092` |
| APK | `app-debug.apk` dibangun `-PPAM_BACKEND_BASE_URL=http://127.0.0.1:18092`, sha256 `33e650584a09330de250a3171e992ec9450495d554fd553b8d03ce35c2abac73`, 45.932.500 byte |
| APK terpasang | `com.pam.recap.debug` (install bersih: `pm clear` sebelum login) |

## Status ringkas

| Kriteria | Status | Bukti utama |
|---|---|---|
| C24 nonaktifkan akun | **PASS (runtime API + store + UI)** | DELETE 200 → `SUSPENDED` revisi 1; 2 sesi target (2 access token + refresh) semuanya ditolak |
| C24 larangan menonaktifkan diri sendiri | PASS (runtime API) | DELETE admin oleh dirinya → 400 `invalid_request` |
| C24 guard admin aktif terakhir | **PASS (runtime API + UI)** | demote satu-satunya admin → 409 `last_admin` (API) dan teks khusus di editor (UI); akun tetap `ADMIN · ACTIVE` |
| C25 cari akun | PASS (runtime API + UI filter) | `q=gap.user07` → tepat 1 baris `gap.user07@pam-uat.test`; `q=no-such-person` → 0 baris |
| C25 filter peran/status | PASS (runtime API + UI) | role USER → 26 akun (semua USER), role ADMIN → 2 akun, status SUSPENDED → 2 akun; nilai filter tak dikenal ditolak kontrak |
| C25 halaman dengan cursor | PASS (runtime API + UI) | 28 akun, 3 halaman `10/10/8`, 28 owner unik (tanpa duplikat/overlap); UI: 50 akun → "Muat akun lain" → sisanya ditambahkan → tombol hilang di halaman terakhir |
| C25 reset cursor saat filter berubah | PASS (runtime API) | cursor halaman hasil query lama dipakai pada filter role berbeda → 400 `invalid_cursor` |
| C25 akun USER tanpa izin admin ditolak | PASS (runtime API + unit UI) | token USER `GET /v1/admin/accounts` → 403 `forbidden`; di Android gerbang klien berhenti sebelum request (test `a USER is forbidden and no admin request is sent`) |
| C25 daftar default tidak memuat DELETED | PASS (runtime API) | default 2 halaman / 29 akun tanpa akun DELETED; `status=DELETED` mengembalikan 1 akun tersebut |

Tidak ada bug baru yang terbukti pada walk ini, sehingga tidak ada perubahan kode aplikasi yang
diperlukan; pekerjaan ini murni bukti + dokumen.

## C24 — nonaktifkan akun, cabut sesi, guard admin terakhir

### 1. Nonaktifkan akun target lewat API admin resmi

Target `gap.target.deact@pam-uat.test` (owner `u_a85f4fe6b0b4d985718b491c6981ad39`) login dua kali
sehingga punya dua sesi hidup. Sebelum aksi: `GET /v1/admin/accounts/{owner}` → `role USER`,
`status ACTIVE`, `revision 0`.

```
DELETE /v1/admin/accounts/u_a85f4fe6b0b4d985718b491c6981ad39?revision=0   (If-Match: "0")
→ 200, status SUSPENDED
GET /v1/admin/accounts/{owner} setelah aksi → 200, status SUSPENDED, revision 1
GET /v1/me dengan access token sesi #1 target        → 401 session_stale
GET /v1/me dengan access token sesi #2 target        → 401 session_stale
POST /v1/sessions/refresh dengan refresh token target → 401 refresh_invalid
GET /v1/admin/audit?action=admin.account.deactivate  → 1 baris, target owner di atas
```

Store lokal (dibaca langsung dari `pam.db`, bukan state proses):

```
gap.target.deact@pam-uat.test | USER | SUSPENDED | revision 1 | live_sessions 0
```

Artinya nonaktifkan tidak hanya mengubah status, tetapi mencabut **semua** sesi (access dan refresh)
target. Audit `admin.account.deactivate` tercatat dan bisa dibaca ulang lewat endpoint audit.

### 2. Nonaktifkan akun lewat UI admin di perangkat

Pada emulator-5556, login sebagai `gap.admin2@pam-uat.test`, buka **Pengaturan → Admin pengguna**
(daftar 30 akun, filter peran/status terlihat), tekan ikon **Nonaktifkan akun** pada baris
`Gap UI Target` / `gap.target.ui@pam-uat.test`. Dialog konfirmasi yang muncul:

> Nonaktifkan akun? — "Akun gap.target.ui@pam-uat.test akan disuspend dan semua sesinya dicabut.
> Tindakan ini memakai revisi 0." — Batal / Nonaktifkan

Setelah konfirmasi:

```
Baris akun di UI          → "Gap UI Target / gap.target.ui@pam-uat.test / USER · SUSPENDED · revisi 1"
Toast                     → "Akun dinonaktifkan."
GET /v1/me (token target) → 401 {"code":"session_stale","message":"Session is no longer active"}
Panel Audit admin di UI   → admin.account.deactivate oleh u_31aa... → u_55ea... (2026-11-23T12:16:48Z)
```

Panel audit UI juga memperlihatkan `admin.account.update` (demote admin1), `admin.invitation.accept`,
`admin.invitation.create`, dan `bootstrap_admin` — konsisten dengan rangkaian aksi walk ini.

### 3. Larangan menonaktifkan diri sendiri

```
DELETE /v1/admin/accounts/{admin2}?revision=0 (If-Match: "0") oleh admin2 sendiri
→ 400 {"code":"invalid_request"}
```

### 4. Guard "admin aktif terakhir"

Skenario: dua admin aktif (`gap.admin1`, `gap.admin2`). Admin2 menurunkan peran admin1 → **200**
(diizinkan karena masih ada 2 admin), dan sesi admin1 langsung mati (`401 session_stale` — pencabutan
sesi pada perubahan peran). Setelah itu hanya admin2 admin aktif yang tersisa.

```
PATCH /v1/admin/accounts/{admin1} {"role":"USER","revision":...} oleh admin2 → 200, role USER
GET   /v1/me dengan token admin1 lama                                    → 401 session_stale
GET   /v1/admin/accounts/{admin2}                                       → ADMIN ACTIVE revision 0
PATCH /v1/admin/accounts/{admin2} {"role":"USER","revision":0}          → 409 {"code":"last_admin"}
DELETE /v1/admin/accounts/{admin2}?revision=0                           → 400 {"code":"invalid_request"}
GET   /v1/admin/accounts/{admin2} setelah percobaan                     → ADMIN ACTIVE (tidak berubah)
```

Di UI, editor `Ubah akun` untuk satu-satunya admin (`Gap Admin Two`) dengan pilihan peran USER lalu
**Simpan akun** menampilkan pesan khusus:

> "Admin aktif terakhir tidak bisa diturunkan perannya atau dinonaktifkan." (plus "Terjadi konflik data.")

Editor tetap terbuka, dan setelah dialog ditutup baris akun tetap `ADMIN · ACTIVE · revisi 0`.
Catatan kontrak: jalur "nonaktifkan diri sendiri" memang lebih dulu ditolak oleh guard self-change
(400 `invalid_request`) — guard `last_admin` terbukti pada jalur demote (409) sesuai kontrak
(400/409 sama-sama ditolak, akun tetap aktif). Guard `last_admin` juga ditegakkan di level basis data
(trigger `last_active_admin`), sehingga berlaku walau ada dua penulis.

## C25 — daftar akun: cari, filter, halaman, gerbang peran, DELETED

Basis walk: 2 admin + 26 akun USER sintetis `@pam-uat.test` (total 28 akun non-DELETED) untuk walk
API, lalu ditambah 30 akun lagi untuk uji halaman di UI (total 61 baris `users`).

### 1. Cari dan filter (API, respons nyata)

```
GET /v1/admin/accounts?limit=50&q=gap.user07       → 200, 1 item: gap.user07@pam-uat.test
GET /v1/admin/accounts?limit=50&q=no-such-person   → 200, 0 item
GET /v1/admin/accounts?limit=100&role=USER         → 200, 26 item, semua role USER
GET /v1/admin/accounts?limit=100&role=ADMIN        → 200, 2 item: gap.admin1, gap.admin2 (semua ADMIN)
GET /v1/admin/accounts?limit=100&status=SUSPENDED  → 200, 2 item (dua akun yang dinonaktifkan)
```

Di UI, filter peran ADMIN menghasilkan "1 akun" (`Gap Admin Two`, karena admin1 sudah di-demote) dan
filter status SUSPENDED menghasilkan "2 akun" (dua target yang dinonaktifkan) — angka sama dengan API.

### 2. Halaman dengan cursor

```
GET /v1/admin/accounts?limit=10                 → 200, 10 item + nextCursor
GET ...&cursor=<next>                           → 200, 10 item + nextCursor
GET ...&cursor=<next>                           → 200, 8 item, tanpa nextCursor
Total 3 halaman, 10/10/8, 28 owner unik (tidak ada akun yang muncul dua kali).
```

Di UI (60 akun non-DELETED): halaman pertama memuat 50 akun dan menampilkan tombol **"Muat akun
lain"**; menekannya menambahkan 10 akun sisa (terlihat `Gap Admin One`, `Gap User 01`… di bagian
bawah), lalu tombol **hilang** karena `nextCursor` sudah habis. Ini bukti paging >1 halaman di
perangkat, bukan hanya API.

### 3. Cursor terikat pada query dan filter

Cursor yang diterbitkan untuk daftar tanpa filter (scope `q`/`role`/`status` kosong) dipakai pada
request dengan `role=ADMIN`:

```
GET /v1/admin/accounts?limit=5&role=ADMIN&cursor=<cursor scope lama>
→ 400 {"code":"invalid_cursor"}
```

Artinya perubahan filter tidak bisa membawa cursor lama (UI juga me-reset cursor di
`onQueryChange`/`onRoleFilterChange`/`onStatusFilterChange`; test VM menegakkan ini).

### 4. Akun USER tanpa izin admin

```
GET /v1/admin/accounts dengan Bearer token akun USER (gap.user01)
→ 403 {"code":"forbidden"}
```

Di Android, gerbang peran berhenti sebelum request dikirim (`AdminViewModel` memanggil `/v1/me`,
role USER → state forbidden, tanpa request admin); test unit `a USER is forbidden and no admin
request is sent` menjaga perilaku ini.

### 5. Daftar default tidak memuat DELETED

Status `DELETED` hanya dihasilkan alur penghapusan akun (deletion sweep), bukan endpoint admin.
Untuk menguji perilaku daftar pada basis data terisolasi, satu akun `gap.target.deleted@pam-uat.test`
ditempatkan pada status `DELETED` di store (UPDATE langsung pada `pam.db`), lalu dibaca lewat API:

```
Daftar default (2 halaman, limit 25) → 29 akun, akun DELETED TIDAK ada
GET /v1/admin/accounts?limit=100&status=DELETED → 200, 1 item: u_c8491d3ee83c90dcf158b22e09a6a048
```

Catatan: akun DELETED ini dibuat di level store hanya untuk membuktikan perilaku filter/pengecualian
daftar; alur deletion-sweep penuh (receipt + sweep) tidak dijalankan pada walk ini.

## Gate

| Gate | Hasil |
|---|---|
| Unit test terkait | `/tmp/pam_build_lock.sh ./gradlew :app:testDebugUnitTest --rerun-tasks --tests "com.pam.recap.ui.feature.admin.*"` → **BUILD SUCCESSFUL**, 22 test (AdminViewModelTest 11 + AdminScreenTest 11), 0 gagal |
| Lint | tidak dijalankan — tidak ada perubahan kode UI pada walk ini (APK dibangun dari kode apa adanya) |
| Build APK debug + base URL 18092 | `BUILD SUCCESSFUL`, sha256 `33e650584a09330de250a3171e992ec9450495d554fd553b8d03ce35c2abac73` |
| Test Go (regresi paket admin) | `scripts/go.sh test ./internal/store ./internal/httpapi` → `ok` keduanya, exit 0 (store 1.4 s, httpapi 19.6 s) |
| Build backend (Go) | `scripts/go.sh build ./cmd/api` dan `./cmd/pam-admin` sukses pada HEAD `0e76329` |

Catatan lingkungan test: worktree baru tidak punya cache lokal Robolectric/JDK, sehingga run pertama
gagal `IllegalArgumentException: Path is not a file: .../.robolectric-deps/android-all-instrumented-14-...jar`
(bukan kegagalan produk). Setelah cache bersama `pam-app` ditautkan (`.toolchain`, `.gradle-home`,
`.robolectric-deps` — semuanya gitignored) run ulang hijau seperti di atas.

## Batasan bukti

- Hanya satu emulator dipakai (`emulator-5556`); emulator-5554 milik Worker A dan perangkat fisik
  `(serial diredaksi)` tidak disentuh.
- Pencarian lewat keyboard emulator memakai `adb shell input text` yang tidak konsisten pada IME
  Compose; bukti pencarian di perangkat karena itu diganti dengan filter peran/status (angka cocok
  dengan API) dan unit test screen. Pencarian `q` sendiri dibuktikan lewat respons API nyata.
- Guard `last_admin` untuk jalur **deactivate** pada akun yang bukan diri sendiri tidak bisa
  dijalankan tanpa dua admin yang saling menonaktifkan; jalur yang terbukti adalah demote
  satu-satunya admin (409 `last_admin`) dan deactivate diri sendiri (400 `invalid_request`).
  Keduanya memenuhi kontrak "ditolak 400/409".
- Akun `DELETED` dibuat di level store (lihat di atas), bukan lewat alur deletion-sweep.
- Status C24/C25 tetap merujuk definisi kriteria di `docs/delivery/ACCEPTANCE_C01_C25.md`; dokumen
  ini menambahkan bukti runtime, bukan mengubah kriteria.
