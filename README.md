# GameMapperMind

<div align="center">

**GameMapperMind v1.0.0**

Aplikasi Android untuk memetakan tombol gamepad fisik ke kontrol sentuh di layar. Mainkan game mobile favorit Anda dengan gamepad Bluetooth/USB (seperti Vortex XP107, Xbox, atau Switch Pro Controller) di tablet atau HP Android.

Didukung oleh **Shizuku API** untuk injeksi sentuh tingkat sistem tanpa root, memberikan latensi rendah dan dukungan multi-touch.

[![CI](https://github.com/NanoMindExplorer/GameMapperMind/actions/workflows/ci.yml/badge.svg)](https://github.com/NanoMindExplorer/GameMapperMind/actions/workflows/ci.yml)
[![Security](https://github.com/NanoMindExplorer/GameMapperMind/actions/workflows/security.yml/badge.svg)](https://github.com/NanoMindExplorer/GameMapperMind/actions/workflows/security.yml)
[![Release](https://github.com/NanoMindExplorer/GameMapperMind/actions/workflows/release.yml/badge.svg)](https://github.com/NanoMindExplorer/GameMapperMind/releases)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

</div>

---

## 📥 Download & Instalasi

### Opsi 1: Download APK Release (Recommended)

Download APK release terbaru dari [**GitHub Releases**](https://github.com/NanoMindExplorer/GameMapperMind/releases/latest):

1. Buka halaman [Releases](https://github.com/NanoMindExplorer/GameMapperMind/releases/latest)
2. Download file `GameMapperMind-1.0.0-release.apk`
3. Verify SHA-256 hash (opsional, untuk security):
   ```bash
   sha256sum GameMapperMind-1.0.0-release.apk
   # Bandingkan dengan hash yang tertera di release notes
   ```
4. Aktifkan "Install from unknown sources" untuk browser/file manager Anda
5. Tap APK untuk install

### Opsi 2: Build dari Source

```bash
# Clone repository
git clone https://github.com/NanoMindExplorer/GameMapperMind.git
cd GameMapperMind

# Install dependencies
npm ci

# Build web app
npx vite build

# Sync Capacitor
npx cap sync android

# Generate keystore untuk release signing
keytool -genkey -v \
  -keystore android/app/game-mapper-mind.keystore \
  -alias game-mapper-mind \
  -keyalg RSA -keysize 2048 -validity 10000

# Buat android/keystore.properties (Lihat template di android/keystore.properties.example)
# atau set environment variables:
#   export KEYSTORE_PASSWORD=your_password
#   export KEY_PASSWORD=your_password

# Build debug APK
cd android && ./gradlew assembleDebug
# Output: android/app/build/outputs/apk/debug/app-debug.apk

# Build release APK (signed)
./gradlew assembleRelease
# Output: android/app/build/outputs/apk/release/app-release.apk
```

### Setup Post-Install

1. **Install Shizuku** dari [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) atau [shizuku.rikka.app](https://shizuku.rikka.app/)
2. **Aktifkan Shizuku** via:
   - **Wireless Debugging** (Android 11+) — paling mudah, no PC required
   - **ADB** dari PC — `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`
3. **Buka GameMapperMind**, selesaikan onboarding, grant Shizuku permission
4. **Hubungkan gamepad** via Bluetooth atau USB OTG
5. **Pilih profil game** atau gunakan WYSIWYG editor untuk kalibrasi
6. **Aktifkan overlay** dan minimize app — tombol gamepad akan ter-mapped ke sentuhan

---

## 🎮 Fitur Utama

- **Shizuku UserService**: Injeksi sentuh tingkat kernel (UID Shell) untuk respons maksimal
- **Native Evdev Capture**: Membaca input gamepad langsung dari `/dev/input/event*`
- **Dynamic Adaptive Polling**: 100Hz saat aktif, 20Hz saat idle (hemat baterai 80%)
- **100-Slot Pointer Pool**: LRU-based garbage collection, mendukung 100+ tombol simultan
- **20 Preset Game Profiles**: Dioptimalkan untuk tablet 12.2" (2800x1840)
- **WYSIWYG Overlay Editor**: Editor visual drag-and-drop untuk kalibrasi tombol
- **Anti-Ban Engine**: Humanisasi sentuhan dengan jitter koordinat, tekanan, dan timing
- **Gyro to Touch Mapping**: Kontrol kamera via giroskop (untuk FPS)
- **Macro Engine**: Rekaman dan playback aksi sentuh dengan loop
- **Auto-Start Detection**: Otomatis ganti profil saat game dibuka
- **Safe-Area Aware**: Kompensasi notch/cutout untuk koordinat presisi

---

## 🎯 Game yang Didukung (Preset)

Tersedia 20 profil siap pakai untuk game populer:

| Kategori | Game |
|----------|------|
| **FPS / BR** | PUBG Mobile, Free Fire, COD Mobile, Apex Legends, Arena Breakout, Farlight 84, Blood Strike |
| **MOBA** | Mobile Legends, Honor of Kings, LoL: Wild Rift, Pokemon UNITE, Arena of Valor |
| **Sports** | eFootball 2024, EA Sports FC Mobile, NBA 2K Mobile |
| **ARPG / Lainnya** | Genshin Impact, Diablo Immortal, Brawl Stars, Roblox, Marvel Snap |

---

## 🛠️ Tech Stack

- **Frontend**: React 19, TypeScript, Tailwind CSS 4, Vite 6, Zod
- **Mobile**: Capacitor 8 (Android)
- **Backend/Native**: Kotlin, Java, Shizuku API v13.1.5, kotlinx.serialization
- **CI/CD**: GitHub Actions (7 workflows — CI, Security, Release, Stale, Build, Validate)
- **Testing**: Vitest (frontend), JUnit (Android), 51 unit tests

---

## 🔧 Troubleshooting

### Shizuku Tidak Berjalan

**Gejala**: App menampilkan "Shizuku not running" saat mencoba connect.

**Solusi**:
1. Buka aplikasi Shizuku
2. Cek status di bagian atas — harus "Running"
3. Jika tidak running:
   - **Android 11+**: Gunakan Wireless Debugging (tidak perlu PC)
   - **Android 10 ke bawah**: Hubungkan ke PC via ADB, jalankan:
     ```bash
     adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
     ```
4. Restart Shizuku setelah reboot device

### Tombol Gamepad Tidak Terdeteksi

**Gejala**: Gamepad terhubung tapi tidak ada response saat tombol ditekan.

**Solusi**:
1. Verify gamepad terdeteksi di Settings → Bluetooth/Connected devices
2. Buka GameMapperMind → tab "Tester" → verify gamepad muncul di list
3. Test setiap tombol — harus muncul visual feedback
4. Jika gamepad terdeteksi tapi tombol tidak response:
   - Restart Shizuku
   - Restart device (Shizuku tidak survive reboot)
   - Coba gamepad di game native (mis. game yang support gamepad) untuk verifikasi hardware

### Koordinat Sentuh Tidak Akurat

**Gejala**: Tombol di-overlay tidak mengenai target di game.

**Solusi**:
1. Buka WYSIWYG Editor
2. Pastikan orientation device = landscape (untuk game landscape)
3. Recalibrate tombol dengan drag ke posisi yang benar
4. Jika device punya notch/cutout, pastikan safe-area insets terbaca:
   - Buka DevTools (chrome://inspect)
   - Jalankan: `getEffectiveScreenRect()` — harus return non-zero `left`/`top`
5. Save profil dan restart overlay

### Overlay Tidak Muncul

**Gejala**: Tombol "Start Overlay" tidak menampilkan overlay mengambang.

**Solusi**:
1. Cek permission "Display over other apps":
   - Settings → Apps → GameMapperMind → Display over other apps → Enable
2. Restart app setelah grant permission
3. Jika masih gagal, cek logcat:
   ```bash
   adb logcat -s GameMapper:* GameMapper_ERROR:*
   ```
4. Cari error "FloatingOverlayService" — biasanya permission issue

### Battery Drain Berlebihan

**Gejala**: Baterai cepat habis saat overlay aktif.

**Solusi**:
1. Pastikan adaptive polling aktif (default):
   - Logcat harus menampilkan: `Tier → IDLE (50ms)` saat gamepad idle
2. Jika selalu `ACTIVE (10ms)`, mungkin ada stuck button event
3. Restart overlay untuk reset state
4. Jika tetap, hapus profil aktif dan buat ulang

### App Crash / Force Close

**Gejala**: App crash saat startup atau saat overlay aktif.

**Solusi**:
1. Cek logcat untuk stack trace:
   ```bash
   adb logcat -s GameMapper_ERROR:* AndroidRuntime:*
   ```
2. Jika ErrorBoundary aktif, akan muncul recovery UI dengan 4 tombol:
   - **Reload App** — restart app
   - **Reset Overlay** — clear config (preserve Shizuku grant)
   - **Copy Error** — copy stack trace untuk bug report
   - **Show Details** — lihat full stack trace
3. Submit bug report di [GitHub Issues](https://github.com/NanoMindExplorer/GameMapperMind/issues) dengan:
   - Stack trace (dari Copy Error)
   - Device model + Android version
   - Game yang dimainkan
   - Langkah reproduksi

### Build Gagal dari Source

**Gejala**: `./gradlew assembleRelease` gagal.

**Solusi**:
1. Verify JDK 21+ terinstall: `java -version`
2. Verify Android SDK terinstall dengan platform-tools + build-tools
3. Verify keystore ada di `android/app/game-mapper-mind.keystore`
4. Verify `android/keystore.properties` ada (atau env vars `KEYSTORE_PASSWORD` + `KEY_PASSWORD` set)
5. Clean build:
   ```bash
   cd android
   ./gradlew clean
   ./gradlew assembleRelease
   ```
6. Jika masih gagal, cek full error di logcat atau gradle output

---

## 🤝 Kontribusi: Tambahkan Profil Game Anda

Kami terbuka untuk kontribusi! Jika game favorit Anda belum ada di daftar, Anda bisa menambahkannya. **Anda hanya diizinkan menambahkan file profil game, tidak bisa mengubah kode aplikasi inti** (untuk keamanan).

### Cara Menambahkan Profil:

1. **Fork** repository ini
2. Buat branch baru: `git checkout -b add-profile-mygame`
3. Copy template profil:
   ```bash
   cp src/communityProfiles/_template.ts src/communityProfiles/myGame.ts
   ```
4. Edit `src/communityProfiles/myGame.ts`:
   - Ubah `id`, `name`, dan `packageName` sesuai game Anda
   - Atur koordinat `buttons` (menggunakan persentase 0-100)
   - Atur `mappings` (dalam pixel absolut untuk resolusi 2800x1840)
5. Daftarkan profil di `src/communityProfiles/index.ts`:
   ```typescript
   import { MY_GAME } from './myGame';

   const communityProfileImports: GamepadProfile[] = [
     MY_GAME,
   ];
   ```
6. Commit dan submit **Pull Request**

Sistem kami akan memvalidasi PR Anda secara otomatis. Jika lolos, profil Anda akan ditambahkan ke APK build selanjutnya!

> **Catatan Keamanan**: Sistem `CODEOWNERS` dan workflow validasi otomatis memastikan tidak ada file inti (seperti `android/` atau `src/components/`) yang bisa diubah oleh kontributor. Hanya folder `src/communityProfiles/` yang terbuka untuk PR.

---

## 📊 Status Proyek

- **Version**: 1.0.0
- **CI/CD**: 7 workflows aktif (CI, Security, Release, Stale, Build, Validate)
- **Test Coverage**: 51 unit tests (25 vitest + 26 JUnit)
- **Security**: 13 WebView hardening flags + InputSanitizer + NativeCrashGuard
- **Performance**: Adaptive polling 20-100Hz + 100-slot pointer pool

---

## 💎 Donate

GameMapperMind adalah software gratis dan open-source. Jika Anda merasa terbantu, pertimbangkan untuk mendukung pengembang:

- **BTC**: `bc1pt9lqxy0vnhrk0d2trn25j47hqm6y26t7ckzfw5hygphnt0rk94es77suv2`
- **EVM**: `0x96e49c673252bb0a2253418417cf1db000fec6ef`
- **Solana**: `4B4wprDDz3pnd6EUumwAKf4LNzRHK5pH4qbustsLcLuR`
- **Tron**: `TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt`

---

## 📄 Lisensi

Proyek ini dilisensikan di bawah [Apache License 2.0](LICENSE).
