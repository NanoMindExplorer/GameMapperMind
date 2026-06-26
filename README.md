# GameMapperMind

[![Build Status](https://img.shields.io/github/workflow/status/NanoMindExplorer/GameMapperMind/build-apk)](https://github.com/NanoMindExplorer/GameMapperMind/actions)
[![Version](https://img.shields.io/github/package-json/v/NanoMindExplorer/GameMapperMind)](https://github.com/NanoMindExplorer/GameMapperMind)
[![License](https://img.shields.io/github/license/NanoMindExplorer/GameMapperMind)](LICENSE)
[![Android Min SDK](https://img.shields.io/badge/Android-12%2B-green)]()
[![Shizuku](https://img.shields.io/badge/Requires-Shizuku%20v13%2B-blue)]()

Aplikasi pemetaan gamepad (Keymapper) untuk menghubungkan kontroler fisik dengan layar sentuh Android.

## Features
- **3-path touch injection** (IInputManager AIDL → InputManager class → shell fallback)
- **Installed Games browser** — launch games directly from app + auto-create profiles
- **Test Injection button** — verify touch injection works without gamepad
- **Multi-gamepad support** (hingga 4 gamepad untuk couch co-op)
- **Sensitivity curve editor** (linear, exponential, parabolic, concave, custom)
- **Haptics feedback integration**
- **Profile persistence** (encrypted with AES-256-GCM)
- **WYSIWYG visual editor** with live gamepad feedback
- **Macro recorder**
- **Orientation-aware** (landscape + portrait)

## Supported Games (built-in profiles)
- eFootball 2026 (jp.konami.pesam)
- Genshin Impact
- PUBG Mobile
- Mobile Legends
- COD Mobile
- Free Fire

## Prerequisites
- **Android 12+ (API 31)** — minimum supported version
- Shizuku v13+ (https://shizuku.rikka.app/)
- Gamepad Bluetooth/USB (Xbox, PlayStation, 8BitDo, generic)

## Setup Shizuku
1. Install Shizuku dari Play Store.
2. Aktifkan Developer Options di Android.
3. Start Shizuku via ADB wireless debugging:
   `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`
4. Buka GameMapperMind, grant permission ke Shizuku.

## First-time Setup
1. Buka app → tab **"Orchestration Control"** → tap **"Start Daemon"**.
2. Tap **"Test Injection"** button — verify touch appears at screen center.
3. Pilih game di tab **"Installed Games"** → tap **Play** untuk launch.
4. Atau buat profile sendiri di tab **"Profile Manager"** → drag tombol di WYSIWYG canvas.
5. Mulai mainkan game — gamepad fisik akan kontrol game via touch injection.

## Injection Architecture (Android 12+)

App uses 3-path injection with automatic failover:

| Path | Method | Reliability | Latency | Multi-touch |
|------|--------|-------------|---------|-------------|
| **A** (primary) | IInputManager AIDL via ServiceManager | Highest — same path as `input` binary | <1ms | Full |
| **B** (fallback) | InputManager class via getSystemService + reflection | High | <1ms | Full |
| **C** (last resort) | `input tap` / `input swipe` shell command | Guaranteed | ~100ms | Single-tap only |

Shizuku runs as **shell uid (2000)** which:
- Bypasses hidden API restrictions
- Has `INJECT_EVENTS` permission

## Troubleshooting
- **Gamepad tidak terdeteksi:** Pastikan gamepad terhubung Bluetooth/OTG dan dikenali Android. Cek di tab **"Sensor & Input Diagnostics"**.
- **Touch tidak responsif:** Jalankan **"Test Injection"** di tab Shizuku. Log akan menampilkan path mana yang aktif (A/B/C) dan rekomendasi jika ada yang broken.
- **Shizuku mati setelah reboot:** Layanan Shizuku non-root perlu dinyalakan kembali via ADB tiap reboot.
- **Analog stick tidak bergerak:** Jika app jatuh ke Path C (shell fallback), analog stick tidak akan jalan. Hanya button press yang bekerja. Pastikan Path A atau B aktif.
- **App hilang dari Shizuku management:** Fixed di versi terbaru — polling sekarang read-only, tidak ada lagi bind/unbind churn.

## FAQ
**Q: Apakah ini membutuhkan ROOT?**
A: Tidak, aplikasi berjalan lewat akses Shizuku (shell uid via ADB wireless debugging).

**Q: Apakah aman dari banned?**
A: App memakai sentuhan TOOL_TYPE_FINGER dengan source TOUCHSCREEN (bukan MOUSE). Anti-ban mode (Gaussian offset) opsional. Tetap **gunakan dengan risiko pengguna**.

**Q: Support controller Xbox dan PS?**
A: Ya, semua standar mapping Android gamepad terdeteksi. Xbox Bluetooth LT/RT didukung via AXIS_LTRIGGER/RTRIGGER fallback.

**Q: Bisa bermain couch multiplayer?**
A: Bisa, support hingga 4 controllers simultan. Atur config Player (1-4) di masing-masing node tombol.

## Contributing
Kami menerima Pull Request dan bantuan open source.
- Buka Issue sebelum membuat PR yang besar.
- **Setiap release wajib increment `versionCode` di `android/app/build.gradle`**
- Jalankan `npm run lint` dan `npm test` sebelum commit.

## License
Apache-2.0

## Disclaimer
Aplikasi ini tidak berafiliasi dengan game yang didukung. Gunakan dengan bertanggung jawab. Risiko banned ditanggung user.

