# UnduhKu v1.0.0 — Download Cepat + Clip Virtual 10 Detik

APK Android (Kotlin native + Jetpack Compose Material3, dark).
Package: `com.daftech.unduhku` • versionCode 1 / versionName 1.0.0 •
minSdk 26, targetSdk 34, compileSdk 35.

## Scope v1 (TERKUNCI, gas 2026-11-13 14:03 WIB — satu gas = ini saja)

### Tab 1 — Unduh
- Tempel link -> deteksi 2 jenis:
  - **Direct file** (`.mp4`/`.mkv`/`.zip`/`.apk`/`.pdf`/dll): HEAD request
    ambil nama + ukuran + dukung Range? (fallback probe Range 0-0
    kalau server menolak HEAD; tolak content-type `text/html`).
  - **archive.org/details/\<id\>**: GET `https://archive.org/metadata/<id>`,
    tampilkan daftar file (nama, size, format) untuk dipilih. Sidecar
    (`_files.xml`, `_meta.xml`, `_meta.sqlite`, `.torrent`,
    `__ia_thumb.jpg`, folder `.thumbs/`) disaring — user memilih file
    media/dokumen, bukan file internal archive.org.
- Download **multi-chunk OkHttp: 8 koneksi default**, header `Range`,
  resume per-chunk (part di cache, batas chunk deterministik supaya
  resume lintas restart menghitung batas yang sama), progress +
  kecepatan + ETA, pause / resume / batal, foreground service +
  notifikasi, simpan ke **Download/UnduhKu**, verifikasi ukuran + MD5
  kalau metadata menyediakannya (MD5 archive.org dicek sambil menyalin,
  tanpa baca file dua kali).
- **Satu download aktif** dalam satu waktu (tanpa antrean di v1).

### Tab 2 — Clip Virtual
- Setelah video terdownload: baca durasi via `MediaMetadataRetriever`,
  buat daftar clip **VIRTUAL** tiap N detik (default 10, pilihan
  10/15/30/60) — **TIDAK dibuat 282 file fisik di awal**. Contoh nyata:
  film DBZ Movie 5 (2.819.240 ms) = 282 clip virtual @10 detik.
- Tiap item: thumbnail (`getFrameAtTime`), timestamp mulai-akhir,
  checkbox, preview via ExoPlayer (clip + `ClippingConfiguration`).
- Tombol **Export yang dipilih saja** -> potong via **Media3 Transformer**
  dengan `experimentalSetMp4EditListTrimEnabled(true)` +
  `experimentalSetTrimOptimizationEnabled(true)`, output ke
  `Download/UnduhKu/clips/`. Output divalidasi `MediaExtractor`
  (track video ada + durasi 0.4x–1.6x yang diminta); gagal = file
  dihapus, lanjut ke clip berikutnya, bukan crash.
- Ekor < 2 detik digabung ke clip sebelumnya (jangan buat clip 0,3 detik).
- Batas potong mengikuti keyframe terdekat (snap ±2 detik) — batas yang
  diterima scope, bukan bug.

### Riwayat (Room)
- Riwayat download + clip hasil export. "Hapus" hanya menghapus BARIS
  riwayat — file di Downloads milik user dan TIDAK ikut dihapus.

### TIDAK ADA di v1 (scope lock)
- Downloader YouTube / TikTok / Instagram (host itu DIBLOKIR di
  `LinkUtils` — pesan penolakan, bukan fitur yang belum jadi).
- Torrent, browser dalam app, edit kreatif (transisi/teks/musik/filter).
- Re-encode penuh, FFmpegKit (pensiun Jan 2025 — JANGAN dibundel).

## Mesin

| Bagian | Engine |
|---|---|
| HTTP + download | OkHttp 4.12.0, Dispatcher `maxRequestsPerHost=16` (default 5 akan mencekik 8 chunk paralel jadi 5) |
| Deteksi link | `LinkResolver` (HEAD + Range probe + metadata archive.org, `org.json`) |
| Potong clip | Media3 Transformer 1.9.0 (edit-list trim + trim optimization), `HandlerThread` khusus (pola ClipKu v1.1.0 — Transformer menolak akses dari thread lain) |
| Preview | Media3 ExoPlayer + `PlayerView` |
| Thumbnail/durasi | `MediaMetadataRetriever` (IO, cache memori 32) |
| Riwayat | Room 2.6.1 via KAPT |
| UI | Compose Material3, dark (teks TextField warna eksplisit — pelajaran LoopLive) |

## Penyimpanan (keputusan jujur — baca sebelum protes)

- Part chunk tinggal di `cacheDir/unduhku-parts/<nama>-<ukuran>-<hash8>/`
  (deterministik dari URL+nama+ukuran → resume lintas restart).
- File final dipublish via **MediaStore ke `Download/UnduhKu`**
  (pola ClipKu `saveToDownloads`): part disalin berurutan LANGSUNG ke
  stream MediaStore — tanpa file gabungan di cache, hemat 1x ukuran.
- **Kebutuhan ruang: ~2x ukuran file** (part 1x + final 1x). Download
  dibatalkan sebelum mulai kalau ruang kosong < 2x + 100 MB.
- Kalau MediaStore gagal: fallback ke
  `getExternalFilesDir(Downloads)/UnduhKu` (selalu bisa ditulis).
  Riwayat menyimpan Uri `content://…` atau path `file:…`; retriever /
  Transformer / ExoPlayer menerima keduanya lewat `storageUriToUri()`.

## Versi yang dipakai (PIN — jangan naik diam-diam)

- AGP 8.7.3, Gradle 8.9, Kotlin 1.9.25, JDK 17
- compileSdk 35, minSdk 26, targetSdk 34
- media3 **1.9.0** (1.10.0+ butuh compileSdk 36 — AGP 8.7.3 mentok 35)
- Room **2.6.1** (2.7.0+ tarik kotlin-stdlib 2.x, bunuh compiler 1.9.25)
- OkHttp 4.12.0, Compose BOM 2024.06.00, `kotlinCompilerExtensionVersion 1.5.15`

## Build

Build resmi via GitHub Actions (`.github/workflows/unduhku-build.yml`):
JDK 17 + Android SDK manual + Gradle 8.9 + `gradle assembleDebug`,
unit test dulu (gagal test = merah), gerbang keystore + gerbang
sertifikat APK sebelum upload ke rolling release `latest`,
nama file `UnduhKu-v1.0.0.apk`, plus `build.log` ke release `build-log`.

### Secret yang wajib diset di repo (sebelum build pertama)

- `KEYSTORE_BASE64` — keystore tetap Putra (nilai SAMA dengan yang
  dipakai SuaraKu/ClipKu; fingerprint SHA-256
  `0A:A5:2A:F9:10:17:42:AE:5D:5A:13:80:F7:F2:E8:A4:F1:F5:88:03:80:6F:B6:09:43:72:D3:3C:78:10:30:20`,
  sertifikat APK `0aa52af9101742ae5d5a1380f7f2e8a4f1f58803806fb6094372d33c78103020`).
  Fallback `KEYSTORE_B64` (nama secret lama) kalau belum sempat diset.
  Set via file, JANGAN paste web UI:
  `base64 -w0 signing/unduhku-tetap.keystore > /tmp/ks.b64`
  lalu `gh secret set KEYSTORE_BASE64 < /tmp/ks.b64`.
- Pola keystore = workspace (`signing/unduhku-tetap.keystore` +
  `signingConfigs` eksplisit). JANGAN tanam ke `~/.android/debug.keystore`
  (pola itu gagal diam-diam di SuaraKu v1.0.2).

Build lokal (opsional): Gradle 8.9, JDK 17, Android SDK 35, lalu
`gradle assembleDebug`. Tanpa keystore tetap, APK lokal ditandatangani
debug keystore bawaan dan TIDAK bisa timpa-install APK CI.

## Tes terima v1 (di HP Putra)

1. Tempel `https://archive.org/details/dbph-1` -> daftar file muncul,
   MP4 256 MB + MKV 4,5 GB; pilih MP4 -> download sampai "Selesai",
   MD5 cocok.
2. Pause di tengah jalan -> Lanjut -> selesai tanpa ulang dari nol.
3. Matikan layar saat download -> notifikasi jalan terus, download kelar.
4. Tab Clip: pilih MP4 -> durasi 46:59 -> 282 clip virtual @10 detik,
   thumbnail muncul, preview 1 clip bunyi + gambar sinkron.
5. Centang 3 clip -> Export -> 3 file muncul di `Download/UnduhKu/clips/`,
   bisa diputar, durasi ~10 detik tiap file.
6. Riwayat menampilkan download + export; hapus baris tidak menghapus file.

## Yang BELUM terverifikasi (jujur — bukan klaim)

- **Build belum pernah jalan.** Sandbox ini TIDAK punya Java/Gradle
  (`java`, `kotlinc`, `gradle` tidak ada) — tidak ada kompilasi,
  tidak ada unit test yang dieksekusi, tidak ada APK. Build hijau
  HANYA bisa dinyatakan dari GitHub Actions setelah push.
- Unit test (`LinkUtilsTest`, `ClipPlannerTest`, `ChecksumTest`) ditulis
  dengan hitungan yang dicek manual (termasuk koreksi ekor < 2 detik),
  tapi belum dieksekusi di mana pun.
- `experimentalSetMp4EditListTrimEnabled` /
  `experimentalSetTrimOptimizationEnabled` diambil dari dokumentasi
  Media3 Transformer (developer.android.com) + pola yang sama di
  riset ClipKu — belum teruji di device terhadap MP4 archive.org.
- Publish MediaStore + baca-balik Uri untuk retriever/Transformer/
  ExoPlayer mengikuti pola ClipKu (`saveToDownloads`) + API standar
  Android — belum teruji di Redmi Note 10S.
- Kecepatan 8 chunk vs 1 koneksi di jaringan Putra (data seluler)
  belum diukur — klaim kecepatan hanya dari desain (Range + paralel),
  bukan dari benchmark.
