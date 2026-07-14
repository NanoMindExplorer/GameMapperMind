# GameMapperMind

[![Build Status](https://img.shields.io/github/workflow/status/NanoMindExplorer/GameMapperMind/build-apk)](https://github.com/NanoMindExplorer/GameMapperMind/actions)
[![Version](https://img.shields.io/github/package-json/v/NanoMindExplorer/GameMapperMind)](https://github.com/NanoMindExplorer/GameMapperMind/releases)
[![License](https://img.shields.io/github/license/NanoMindExplorer/GameMapperMind)](LICENSE)
[![Android Min SDK](https://img.shields.io/badge/Android-12%2B-green)]()
[![Shizuku](https://img.shields.io/badge/Requires-Shizuku%20v13%2B-blue)]()
[![Demo Video](https://img.shields.io/badge/Demo-YouTube-red?logo=youtube)](https://youtu.be/OtdO_hg2ZdI)

Aplikasi pemetaan gamepad (Keymapper) untuk menghubungkan kontroler fisik dengan layar sentuh Android. Mendukung multi-touch injection yang benar (analog + tombol bersamaan tanpa gangguan), 6 interaction types, dan 3-path injection dengan failover otomatis.

## Game Test Demo

<a href="https://youtu.be/OtdO_hg2ZdI" target="_blank">
  <img src="https://img.youtube.com/vi/OtdO_hg2ZdI/maxresdefault.jpg" alt="GameMapperMind Game Test Demo" width="640" style="border-radius: 12px; box-shadow: 0 4px 16px rgba(0,0,0,0.3);">
</a>

> **▶️ Click the thumbnail above to watch the demo on YouTube** — Full game test showing multi-pointer touch injection (analog + button simultaneous), combo without delay, and BTN_GAMEPAD compatibility fix.

[![Watch on YouTube](https://img.shields.io/badge/▶_Watch_on_YouTube-OtdO__hg2ZdI-red?style=for-the-badge&logo=youtube)](https://youtu.be/OtdO_hg2ZdI)

## Features
- **Multi-pointer touch injection** — analog stick dan tombol aktif bersamaan tanpa saling mengganggu (ACTION_POINTER_DOWN/UP yang benar)
- **Dual AIDL dispatch thread** — stick (high priority + coalescing) dan button (normal priority) di thread terpisah untuk eliminasi combo delay
- **3-path touch injection** (IInputManager AIDL → InputManager class → shell fallback) dengan retry otomatis (tidak lock ke Path C)
- **Installed Games browser** — launch games directly from app + auto-create profiles
- **Test Injection button** — verify touch injection works without gamepad
- **Flexible trigger system** — "Learn Trigger" captures any gamepad button via raw evdev (termasuk BTN_GAMEPAD/BTN_TL2/BTN_TR2 yang non-standard)
- **6 interaction types**: Hold, Tap, Turbo (auto-fire), Toggle (lock), Charge (hold-release), Gesture (multi-point path)
- **Chord triggers** — combine multiple buttons (e.g., LB+RB = special action)
- **Macro trigger** — assign recorded macros to any button
- **Stick-as-drag mode** — analog stick moves touch absolutely (mortar/sniper aim)
- **Radial deadzone on raw input** — release immediate saat stick kembali ke center (tidak nyangkut)
- **Visual interaction indicators** — canvas shows ⚡ turbo, ⊕ toggle, ⏱ charge, ~ gesture badges
- **Multi-gamepad support** (hingga 4 gamepad untuk couch co-op)
- **Sensitivity curve editor** (linear, exponential, parabolic, concave, custom)
- **Haptics feedback integration**
- **Profile persistence** (encrypted with AES-256-GCM)
- **WYSIWYG visual editor** with live gamepad feedback
- **Macro recorder**
- **Orientation-aware** (landscape + portrait)
- **On-screen diagnostic log** — surface raw evdev axis/button names, injection failures, dan unmapped button codes langsung di app (tidak perlu adb logcat)

## Supported Games (built-in profiles)
- eFootball 
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

## Interaction Types & Trigger Assignment

Setiap node overlay dapat dikonfigurasi dengan interaction type berbeda:

| Type | Deskripsi | Use Case |
|------|-----------|----------|
| **Hold** | Press = touchDown, release = touchUp (default) | Tombol biasa (Pass, Shoot) |
| **Tap** | Single quick tap on press | Menu, pause |
| **Turbo** | Auto-repeat tap every N ms while held | Auto-fire (RT = 20 taps/sec) |
| **Toggle** | Press once = touch stays, press again = release | Auto-run, ADS toggle |
| **Charge** | Hold N ms, release to trigger | Charged jump, power shot |
| **Gesture** | Multi-point touch path with delays | Skill combo, drawing gesture |
| **Macro** | Trigger recorded macro sequence | Complex combo (5-tap sequence) |

### Learn Trigger
- Tap **"Learn Single"** → press any gamepad button → assigned as trigger
- Tap **"Learn Chord"** → press multiple buttons sequentially → tap "Done" → all must be pressed together
- Supports non-standard buttons (paddles, extra buttons) via raw evdev detection

### Stick Modes
- **Joystick** (default): touch stays within radius of center, stick deflects cap
- **Drag**: touch moves absolutely across screen — for mortar/sniper aim

### Visual Indicators
Canvas menampilkan badge per interaction type:
- ⚡ = Turbo | ⊕ = Toggle | ⏱ = Charge | ~ = Gesture | ▸ = Tap | M = Macro | DRAG = Stick drag mode

## Injection Architecture (Android 12+)

App uses 3-path injection dengan automatic failover dan **tidak permanent lock** ke Path C (selalu retry A → B → C setiap call):

| Path | Method | Reliability | Latency | Multi-touch |
|------|--------|-------------|---------|-------------|
| **A** (primary) | IInputManager AIDL via ServiceManager | Highest — same path as `input` binary | <1ms | Full (multi-pointer) |
| **B** (fallback) | InputManager class via getSystemService + reflection | High | <1ms | Full (multi-pointer) |
| **C** (last resort) | `input tap` shell command | Guaranteed | ~100ms | Single-tap only (tidak fire saat ada pointer aktif lain) |

### Multi-Pointer MotionEvent (v3)

Touch injection menggunakan **Android multi-touch semantics yang benar**:
- Pointer pertama DOWN: `ACTION_DOWN`, `pointerCount=1`
- Pointer tambahan DOWN saat lain aktif: `ACTION_POINTER_DOWN`, `pointerCount=SEMUA pointer aktif`
- Pointer UP saat lain masih aktif: `ACTION_POINTER_UP` (bukan `ACTION_UP`)
- `actionIndex` di-set ke index pointer yang berubah dalam properties array
- `downTime` shared dari gesture pertama untuk semua pointer dalam session yang sama

Ini memastikan **analog stick dan tombol aktif bersamaan tanpa saling membatalkan** — saat L_STICK sedang aktif dan tombol A ditekan, Android menerima `ACTION_POINTER_DOWN` dengan kedua pointer, bukan `ACTION_DOWN` baru yang membatalkan session stick.

### Dual AIDL Dispatch Thread (v2)

Touch calls di-dispatch ke **dua thread terpisah**:
- **`stickAidlHandler`** (Thread.MAX_PRIORITY) — khusus analog touchDown/touchMove/touchUp. Dengan **coalescing**: hanya `touchMove` terbaru per pointer yang dikirim, move lama di-drop dari queue (caps latency di ~10ms regardless of getevent rate).
- **`buttonAidlHandler`** (Thread.NORM_PRIORITY) — khusus button touchDown/touchUp/injectTap.

Keduanya jalan **paralel** — Android InputManager menerima `injectInputEvent` concurrent untuk pointer ID berbeda. Button press tidak pernah delay stick movement.

Shizuku runs as **shell uid (2000)** yang:
- Bypasses hidden API restrictions
- Has `INJECT_EVENTS` permission

## Gamepad Compatibility

App mendeteksi otomatis layout controller via `getevent -lp` dan menormalisasi axis berdasarkan range real (bukan hardcoded 0..255 atau -32768..32767). Mapping evdev yang didukung:

| Logical Button | evdev Codes | Notes |
|----------------|-------------|-------|
| A | `BTN_GAMEPAD`, `BTN_A`, `BTN_SOUTH` | BTN_GAMEPAD = BTN_A = 0x130 di Linux kernel |
| B | `BTN_B`, `BTN_EAST` | |
| X | `BTN_X`, `BTN_NORTH` | |
| Y | `BTN_Y`, `BTN_WEST` | |
| LT | `BTN_TL2`, `BTN_LT`, atau analog axis (`ABS_Z`/`ABS_BRAKE`/`ABS_LTRIGGER`) | Digital + analog trigger didukung |
| RT | `BTN_TR2`, `BTN_RT`, atau analog axis (`ABS_RZ`/`ABS_GAS`/`ABS_RTRIGGER`) | Digital + analog trigger didukung |
| LB / RB | `BTN_TL`/`BTN_L1`, `BTN_TR`/`BTN_R1` | |
| L3 / R3 | `BTN_THUMBL`/`BTN_THUMB`, `BTN_THUMBR`/`BTN_THUMB2` | |
| D-Pad | `ABS_HAT0X`/`ABS_HAT0Y` (analog hat) atau `BTN_DPAD_*` (discrete) | |
| START / SELECT / HOME | `BTN_START`, `BTN_SELECT`, `BTN_MODE` | |

Right stick auto-detect: jika controller tidak punya `ABS_RX`/`ABS_RY`, app otomatis pakai `ABS_Z`/`ABS_RZ` untuk right stick (umum di generic Bluetooth gamepads).

## Troubleshooting
- **Gamepad tidak terdeteksi:** Pastikan gamepad terhubung Bluetooth/OTG dan dikenali Android. Cek tab **"Sensor & Input Diagnostics"**. Lihat juga on-screen log untuk `[GAMEPAD-DETECT] axes: ... | buttons: ...` yang menampilkan axis/button yang terdeteksi.
- **Tombol tertentu tidak bereaksi:** Cek on-screen log untuk `[GAMEPAD-KEY] Unmapped button BTN_XXX` — controller Anda pakai kode non-standard. Laporkan di Issue agar kami tambahkan mapping.
- **Touch tidak responsif:** Jalankan **"Test Injection"** di tab Shizuku. Log akan menampilkan path mana yang aktif (A/B/C) dan rekomendasi jika ada yang broken. Pastikan muncul `Injection OK via Path A`.
- **Analog kembali ke tengah saat tombol ditekan:** Fixed di v3 — pastikan APK yang terinstall adalah v3 atau lebih baru (multi-pointer MotionEvent).
- **Analog tersendat/nyangkut ke bawah:** Fixed di v1+v2 — deadzone check pada raw input + coalescing stick move.
- **Shizuku mati setelah reboot:** Layanan Shizuku non-root perlu dinyalakan kembali via ADB tiap reboot.
- **Analog stick tidak bergerak sama sekali:** Jika app jatuh ke Path C (shell fallback) saat ada pointer aktif, analog tidak akan jalan. Path C hanya untuk single-pointer DOWN/UP. Pastikan Path A atau B aktif (cek log `Using Path A`).

## FAQ
**Q: Apakah ini membutuhkan ROOT?**
A: Tidak, aplikasi berjalan lewat akses Shizuku (shell uid via ADB wireless debugging).

**Q: Apakah aman dari banned?**
A: App memakai sentuhan TOOL_TYPE_FINGER dengan source TOUCHSCREEN (bukan MOUSE). Anti-ban mode (Gaussian offset) opsional. Tetap **gunakan dengan risiko pengguna**.

**Q: Support controller Xbox dan PS?**
A: Ya, semua standar mapping Android gamepad terdeteksi. Xbox Bluetooth LT/RT didukung via AXIS_LTRIGGER/RTRIGGER fallback. Generic Bluetooth gamepads (BTN_GAMEPAD, BTN_TL2/BTN_TR2) juga didukung.

**Q: Bisa bermain couch multiplayer?**
A: Bisa, support hingga 4 controllers simultan. Atur config Player (1-4) di masing-masing node tombol.

**Q: Kenapa analog + tombol bisa aktif bersamaan tanpa gangguan?**
A: v3 menggunakan Android multi-touch MotionEvent yang benar (`ACTION_POINTER_DOWN`/`ACTION_POINTER_UP` dengan semua pointer aktif dalam satu event). Sebelumnya `ACTION_DOWN` dengan `pointerCount=1` membatalkan session stick yang aktif.

## Changelog Ringkasan

### v2.1.1 (2026-07-15) — Multi-Pointer + Gamepad Compatibility Fix
- **v3**: Rewrite multi-pointer MotionEvent (`ACTION_POINTER_DOWN`/`ACTION_POINTER_UP` dengan semua pointer aktif) — fix "analog kembali ke tengah saat tombol ditekan"
- **v3**: `BTN_GAMEPAD → A` mapping (BTN_GAMEPAD = BTN_A = 0x130 di Linux kernel)
- **v3**: Shell fallback (Path C) tidak fire saat ada pointer aktif lain (mencegah hijack)
- **v2**: Per-pointer `downTime` tracking (ConcurrentHashMap) — fix ACTION_UP rejected saat multi-pointer
- **v2**: Dual AIDL dispatch thread (stick MAX_PRIORITY + button NORM_PRIORITY) + coalescing stick move
- **v2**: Filter BTN_GAMEPAD/BTN_JOYSTICK meta event (NOTE: reverted di v3 karena BTN_GAMEPAD = BTN_A)
- **v1**: Async dispatch via `dispatchInteraction`, honor `interactionType` universal
- **v1**: `injectTap` pointer ID 50 (bukan 0) — hindari konflik dengan L_STICK
- **v1**: Deadzone check pada raw input — fix "analog nyangkut ke bawah"
- **v1**: `injectMotionEvent` tidak lock ke Path C — selalu retry A → B → C
- **v1**: `normalizeTrigger` heuristic fallback (255/1023/4095/32767)
- **v1**: `handleKeyEvent` log unknown button codes
- **v1**: `mapEvdevToButton` tambah BTN_LT/BTN_RT alias

Lihat [CHANGELOG.md](CHANGELOG.md) untuk history lengkap.

## Contributing
Kami menerima Pull Request dan bantuan open source.
- Buka Issue sebelum membuat PR yang besar.
- **Setiap release wajib increment `versionCode` di `android/app/build.gradle`**
- Jalankan `npm run lint` dan `npm test` sebelum commit.

## License
Apache-2.0

## Disclaimer
Aplikasi ini tidak berafiliasi dengan game yang didukung. Gunakan dengan bertanggung jawab. Risiko banned ditanggung user.

