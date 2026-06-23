# GameMapperMind

[![Build Status](https://img.shields.io/github/workflow/status/NanoMindExplorer/GameMapperMind/build-apk)](https://github.com/NanoMindExplorer/GameMapperMind/actions)
[![Version](https://img.shields.io/github/package-json/v/NanoMindExplorer/GameMapperMind)](https://github.com/NanoMindExplorer/GameMapperMind)
[![License](https://img.shields.io/github/license/NanoMindExplorer/GameMapperMind)](LICENSE)
[![Android Min SDK](https://img.shields.io/badge/Android-7.0%2B-green)]()
[![Shizuku](https://img.shields.io/badge/Requires-Shizuku%20v13%2B-blue)]()

Aplikasi pemetaan gamepad (Keymapper) untuk menghubungkan kontroler fisik dengan layar sentuh Android.

## Features
- **Anti-ban technology** (Gaussian randomization, MOUSE source)
- **Multi-gamepad support** (hingga 4 gamepad untuk couch co-op)
- **Sensitivity curve editor** (linear, exponential, parabolic, custom)
- **Haptics feedback integration**
- **Profile persistence** (cloud sync ready)
- **WYSIWYG visual editor**
- **Macro recorder**
- **Orientation-aware** (landscape + portrait)

## Supported Games
- Genshin Impact
- PUBG Mobile
- Mobile Legends
- CODM
- Free Fire
- Minecraft, Stardew Valley (Couch Co-Op)

## Prerequisites
- Android 7.0+ (API 24)
- Shizuku v13+ (link: https://shizuku.rikka.app/)
- Gamepad Bluetooth/USB (Xbox, PlayStation, 8BitDo, generic)

## Setup Shizuku
- **Step 1:** Install Shizuku dari Play Store.
- **Step 2:** Aktifkan Developer Options di Android.
- **Step 3:** Start Shizuku via ADB dengan menjalankan command: `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`
- **Step 4:** Buka aplikasi, setujui/grant permission ke GameMapperMind.

## First-time Setup
1. Buka app, grant overlay permission.
2. Pilih game dan pilih profile.
3. Calibrate gamepad kalian (deadzone dan sensitivity).
4. Start overlay dan mulai mainkan.

## Profile Creation Tutorial
1. Buka editor WYSIWYG.
2. Drag & drop button ke posisi yang diinginkan.
3. Set mapped key pada button property.
4. Adjust deadzone, sensitivity, atau tapDuration untuk masing-masing aksi.
5. Save profile.

## Troubleshooting
- **Gamepad tidak terdeteksi:** Pastikan gamepad terhubung melalui Bluetooth atau OTG Cable dan dikenali oleh OS Android.
- **Touch tidak responsif:** Pastikan service Shizuku dan Touch Daemon tetap berjalan. Restart daemon dari pengaturan jika perlu.
- **Shizuku mati setelah reboot:** Layanan Shizuku non-root perlu dinyalakan kembali via ADB tiap kali perangkat direboot.
- **Profile tidak akurat:** Pastikan rotasi layar Anda sesuai dengan orientasi layout.
- **Anti-cheat banned:** Kami menyediakan mode anti-ban, tetapi gunakan dengan risiko sendiri. Tidak ada bypass yang 100% aman.
- **Multi-gamepad tidak jalan:** Pastikan Anda menggunakan build terbaru dan profil map di-setting untuk 'Player' spesifik.

## FAQ
**Q: Apakah ini membutuhkan ROOT?**
A: Tidak, aplikasi berjalan lewat akses Shizuku/ADB wireless debugging.

**Q: Apakah aman dari Banned?**
A: Kami memakai event "MOUSE" dan injeksi Shizuku dengan Gaussian offset, yang umumnya lebih aman, namun tetap **gunakan dengan risiko pengguna**.

**Q: Aplikasi crash saat mapping.**
A: Cek di "Mode Developer" untuk details stack trace, dan laporkan bug.

**Q: Support controller Xbox dan PS?**
A: Ya, semua standar mapping W3C / Android HTML5 Gamepad terdeteksi.

**Q: Bisa bermain couch multiplayer di satu layar tablet?**
A: Bisa, kami mensupport hingga 4 controllers simultan. Atur config Player (1-4) di masing-masing node tombol.

## Contributing
Kami menerima Pull Request dan bantuan open source.
- Buka ISU sebelum membuat PR yang besar.
- **Setiap release wajib increment `versionCode` di `android/app/build.gradle`**
- Jalankan `npm run eslint` untuk fix any linting errors.

## License
Apache-2.0

## Disclaimer
Aplikasi ini tidak berafiliasi dengan game yang didukung. Gunakan dengan bertanggung jawab. Risiko banned ditanggung user.
