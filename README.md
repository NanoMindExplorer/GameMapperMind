# GameMapperMind

Aplikasi Android untuk memetakan tombol gamepad fisik ke kontrol sentuh di layar. Mainkan game mobile favorit Anda dengan gamepad Bluetooth/USB (seperti Vortex XP107, Xbox, atau Switch Pro Controller) di tablet atau HP Android.

Didukung oleh **Shizuku API** untuk injeksi sentuh tingkat sistem tanpa root, memberikan latensi rendah dan dukungan multi-touch.

## 🎮 Fitur Utama

- **Shizuku UserService**: Injeksi sentuh tingkat kernel (UID Shell) untuk respons maksimal.
- **Native Evdev Capture**: Membaca input gamepad langsung dari `/dev/input/event*`.
- **20 Preset Game Profiles**: Dioptimalkan untuk tablet 12.2" (2800x1840).
- **WYSIWYG Overlay Editor**: Editor visual drag-and-drop untuk kalibrasi tombol.
- **Anti-Ban Engine**: Humanisasi sentuhan dengan jitter koordinat, tekanan, dan timing.
- **Gyro to Touch Mapping**: Kontrol kamera via giroskop (untuk FPS).
- **Macro Engine**: Rekaman dan playback aksi sentuh dengan loop.
- **Auto-Start Detection**: Otomatis ganti profil saat game dibuka.

## 📥 Instalasi

1. Download APK terbaru dari [GitHub Actions](https://github.com/NanoMindExplorer/GameMapperMind/actions) (cari workflow run yang berhasil, scroll ke bawah ke "Artifacts").
2. Install APK di perangkat Android Anda.
3. Install aplikasi [Shizuku](https://shizuku.rikka.app/) dari Play Store.
4. Aktifkan Shizuku via Wireless Debugging (Android 11+) atau ADB.
5. Buka GameMapperMind, selesaikan onboarding, dan grant permission Shizuku.

## 🎯 Game yang Didukung (Preset)

Tersedia 20 profil siap pakai untuk game populer:

| Kategori | Game |
|----------|------|
| **FPS / BR** | PUBG Mobile, Free Fire, COD Mobile, Apex Legends, Arena Breakout, Farlight 84, Blood Strike |
| **MOBA** | Mobile Legends, Honor of Kings, LoL: Wild Rift, Pokemon UNITE, Arena of Valor |
| **Sports** | eFootball 2024, EA Sports FC Mobile, NBA 2K Mobile |
| **ARPG / Lainnya** | Genshin Impact, Diablo Immortal, Brawl Stars, Roblox, Marvel Snap |

## 🤝 Kontribusi: Tambahkan Profil Game Anda

Kami terbuka untuk kontribusi! Jika game favorit Anda belum ada di daftar, Anda bisa menambahkannya. **Anda hanya diizinkan menambahkan file profil game, tidak bisa mengubah kode aplikasi inti** (untuk keamanan).

### Cara Menambahkan Profil:

1. **Fork** repository ini.
2. Buat branch baru: `git checkout -b add-profile-mygame`.
3. Copy template profil:
   ```bash
   cp src/communityProfiles/_template.ts src/communityProfiles/myGame.ts
   ```
4. Edit `src/communityProfiles/myGame.ts`:
   - Ubah `id`, `name`, dan `packageName` sesuai game Anda.
   - Atur koordinat `buttons` (menggunakan persentase 0-100).
   - Atur `mappings` (dalam pixel absolut untuk resolusi 2800x1840).
5. Daftarkan profil di `src/communityProfiles/index.ts`:
   ```typescript
   import { MY_GAME } from './myGame';
   
   const communityProfileImports: GamepadProfile[] = [
     MY_GAME,
   ];
   ```
6. Commit dan submit **Pull Request**.

Sistem kami akan memvalidasi PR Anda secara otomatis. Jika lolos, profil Anda akan ditambahkan ke APK build selanjutnya!

> **Catatan Keamanan**: Sistem `CODEOWNERS` dan workflow validasi otomatis memastikan tidak ada file inti (seperti `android/` atau `src/components/`) yang bisa diubah oleh kontributor. Hanya folder `src/communityProfiles/` yang terbuka untuk PR.

## 🛠️ Tech Stack

- **Frontend**: React 19, TypeScript, Tailwind CSS 4, Vite 6
- **Mobile**: Capacitor 8 (Android)
- **Backend/Native**: Kotlin, Java, Shizuku API v13.1.5
- **CI/CD**: GitHub Actions (Auto-build APK)

## 💎 Donate

GameMapperMind adalah software gratis dan open-source. Jika Anda merasa terbantu, pertimbangkan untuk mendukung pengembang:

- **BTC**: `bc1pt9lqxy0vnhrk0d2trn25j47hqm6y26t7ckzfw5hygphnt0rk94es77suv2`
- **EVM**: `0x96e49c673252bb0a2253418417cf1db000fec6ef`
- **Solana**: `4B4wprDDz3pnd6EUumwAKf4LNzRHK5pH4qbustsLcLuR`
- **Tron**: `TDzaGUA7YgQEaB1RfnBgWWn9QzJ8QFCVmt`

## 📄 Lisensi

Proyek ini dilisensikan di bawah [Apache License 2.0](LICENSE).
