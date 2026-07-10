# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.0] - 2026-06-28 — RELEASE
### Added
- **Flexible trigger system** — "Learn Trigger" mode captures any physical gamepad button (including non-standard via raw evdev) and assigns it as trigger
- **Chord Learn Mode** — capture multiple buttons sequentially (e.g., LB+RB = special action). All must be pressed simultaneously to fire.
- **6 interaction types** beyond default hold:
  - Turbo (auto-repeat tap every N ms while held)
  - Toggle (press once = touch stays, press again = release)
  - Charge (hold for threshold ms, release to trigger)
  - Gesture (multi-point touch path with delays)
  - Tap (single quick touch)
  - Macro (trigger recorded macro sequence)
- **Stick-as-drag mode** — analog stick moves touch point absolutely across screen (mortar/sniper aim) vs default joystick mode (relative to center)
- **Gesture Point Editor** — UI for adding/editing/removing gesture path points (X%, Y%, delayMs)
- **Visual interaction indicators** — badge icons on canvas nodes: ⚡ turbo, ⊕ toggle, ⏱ charge, ~ gesture, ▸ tap, M macro, DRAG stick
- **Gesture path visualization** — dashed cyan lines connecting gesture points in canvas editor
- **Macro selector** — dropdown to choose which recorded macro to trigger
- **Sensitivity curve selector** — dropdown for linear/exponential/parabolic/concave

### Fixed
- Duplicate `val wasDown` declaration in NativeGamepadMapper causing Kotlin compile failure
- Sensitivity slider now connected to injection pipeline (was writing field but never reading it)
- `exponential` curve now true exponential `(e^(kx)-1)/(e^k-1)` instead of identical to parabolic (x²)
- `types.ts` sensitivityCurve enum missing 'concave' (type mismatch with schema)
- `isAlive()` always returning true (SparseArray.size() >= 0 is always true)
- `pathFailCount` race condition (changed to AtomicInteger)
- RCE in executeShellCommand (whitelist moved into service layer)
- ADMIN_TOKEN logged to stdout
- /api/log auth mismatch (frontend calls without Authorization header)
- Dual-path gamepad double-injection (isRunning guard now protects injection calls too)
- Shizuku persistence: reduced polling 5s→20s, auto battery ignore, one-shot rebind on resume

### Changed
- `NativeGamepadMapper.buildMapCache` now indexes by both mappedKey (legacy) and trigger.inputs[] (new)
- `NativeGamepadMapper.handleButton` evaluates trigger-based mappings first, falls back to legacy path
- `NativeGamepadMapper.processStick` supports stickMode='drag' for absolute screen movement
- `ButtonPropertyPanel` redesigned with Learn Trigger, interaction type selector, dynamic params
- `OverlayWysiwyg` canvas shows interaction type badges + gesture path visualization
- `package-lock.json` regenerated to remove 12 deleted dependencies

## [2.0.0] - 2026-06-27
### Breaking
- Minimum Android version raised to **Android 12 (API 31)**. Older versions no longer supported.

### Added
- **3-path touch injection** with automatic failover:
  - Path A: IInputManager AIDL via ServiceManager (primary, most reliable)
  - Path B: InputManager class via getSystemService + reflection (fallback)
  - Path C: `input tap` / `input swipe` shell command (last resort, guaranteed)
- **Installed Games panel** — browse installed games, launch them directly from app, auto-create profiles
- **Test Injection button** — verify touch injection works without gamepad
- **Live canvas feedback** — buttons in WYSIWYG editor light up when gamepad pressed
- **Shell fallback auto-switch** — after 3 consecutive injection failures, switches to `input tap`
- **Diagnostic endpoint** `testInjection(x, y)` — full injection chain check via AIDL API
- eFootball 2026 default profile (jp.konami.pesam) with 11 button mappings

### Changed
- TouchDaemonService completely rebuilt with 3-path injection scheme
- AndroidManifest.xml cleaned for Android 12+ (removed dead BLUETOOTH permission, added largeHeap)
- ProGuard rules updated for IInputManager AIDL reflection targets
- README.md updated with new architecture documentation
- tsconfig.json: enabled strict mode
- Server: removed 6 dead endpoints (/api/ai/*, /api/daemon/*, /api/health, /api/simulation/execute)

### Fixed
- Shizuku app disappearing from management tab (removed bind/unbind churn in polling)
- Fake "Gamepad Connected" when no gamepad is on (ERROR_* events no longer set connected state)
- Canvas buttons not reacting to gamepad (activeKeys prop now highlights pressed buttons)
- InputManager.getInstance() reflection blocked on Android 10+ (now uses public getSystemService API)
- Analog stick signal chain (deadzone on raw magnitude, rescaling, curve on magnitude only)

### Removed
- Web Gamepad API polling (navigator.getGamepads) — doesn't work in native WebView
- GamepadStatusBadge component + useGamepad hook (always showed DISCONNECTED)
- 8 unused npm dependencies (@base-ui/react, class-variance-authority, clsx, dotenv, motion, recharts, shadcn, tailwind-merge, @capacitor/keyboard, @capacitor/status-bar)
- Dead code: InterceptFrameLayout, MappingSetup, MacroService, verify_contract.sh, docs/fixes/
- Duplicate icon.svg at root (Vite serves public/icon.svg)
- Template test files (ExampleUnitTest, ExampleInstrumentedTest)

## [1.0.0-FINAL] - 2026-06-23
### Added
- Added secure ADMIN_TOKEN enforcement.
- Added AES-256-GCM encryption for stored profiles and macros.
- Multi-gamepad support structure.

### Changed
- Refactored radial deadzone into constants.
- Updated vitest configuration.

### Fixed
- Fixed unauthenticated access to /api/logs and /api/macros (BUG-C02, BUG-C03).
- Added rate limit to /api/log (BUG-C04).
- Handled spurious race conditions in useGamepadLoop (BUG-H01).

### Security
- Resolved missing cors protections.

## [2.3.0] - 2026-07-09
### Added
- OnboardingWizard (4 langkah interaktif pertama kali)
- Quick Action di notification (Toggle Overlay, Test Injection)
- `onboardingCompleted` flag via Preferences
- 3-path touch injection di `TouchDaemonService`: Path A (`IInputManager` AIDL binder) → Path B (`InputManager` reflection) → Path C (shell `input tap` fallback, single-pointer tap only), dengan auto-switch dan `pathFailCount`
- `NativeGamepadMapper` dipecah ulang dari `FloatingOverlayService` — deadzone radial, sensitivity curve, 6 interaction type, chord trigger, macro, multi-gamepad (0-3) sekarang jalan lewat `GamepadListenerService` + `TouchDaemonService` (Shizuku UserService via AIDL)

### Improved
- First launch experience
- Daemon resilience

### Fixed
- **Overlay window blank/crash**: `OverlayApp` sekarang self-load `activeProfile` dari Capacitor Preferences (`nexion_profiles` / `nexion_active_profile`) karena `FloatingOverlayService` membuka WebView terpisah (`index.html?overlay=true`) yang tidak pernah punya jalur untuk terima props dari `App.tsx`. Sebelumnya ini bikin `npx tsc --noEmit` gagal dan overlay window jadi kosong/rusak di runtime.
- **`window.injectConfig` disambungkan**: native (`FloatingOverlayService.java`) sudah lama punya jembatan buat push `activeProfile` langsung ke WebView (`onPageFinished` / `onReactReady()` / `pushConfigToActiveOverlay()`), tapi sisi JS nggak pernah mendefinisikan `window.injectConfig` — jadi selama ini no-op (di-guard `if(window.injectConfig)` di Java, makanya nggak crash, cuma nggak pernah jalan). `OverlayApp` sekarang mendefinisikan `window.injectConfig` + manggil `AndroidOverlay.onReactReady()` saat mount, supaya overlay dapet profile yang persis sama dengan yang aktif saat user tekan "Start Overlay" — bukan cuma nunggu Preferences yang bisa saja belum selesai ke-persist.
- **Silent injection failure (touchDown)**: 3 titik `touchDown` (stick pointer di `processStick`, tombol hold di `handleButton`, `handleHoldInteraction`) yang sebelumnya nelan exception tanpa log sekarang manggil `logInjectFailure()`, konsisten dengan `touchUp`/`touchMove` di sekitarnya.
- **Silent injection failure (touchMove, Path C)**: `touchMove` balikin `Boolean` lewat AIDL, tapi return value-nya sebelumnya nggak pernah dicek — cuma exception yang ditangkep. Saat `TouchDaemonService` degradasi ke Path C (shell fallback), `touchMove` balikin `false` tanpa exception, jadi drag stick analog berhenti total secara diam-diam tanpa ada log sama sekali. Sekarang return `false` diperlakukan sama seperti exception (rate-limited warning, sama seperti mekanisme `moveFailWarned` yang sudah ada).
- **D-pad mati total saat main (bukan cuma pas testing)**: ada 2 jalur baca gamepad — `GamepadPlugin.kt` (Android input biasa, D-pad-nya udah bener via `AXIS_HAT_X/Y`) yang CUMA aktif kalau MainActivity fokus, dan `GamepadListenerService.kt` (baca `getevent -l` mentah lewat Shizuku) yang jalan system-wide termasuk pas eFootball fokus. `handleAbsEvent` di jalur kedua nggak pernah nangani `ABS_HAT0X`/`ABS_HAT0Y` (cara standar D-pad dikirim controller) — jadi D-pad kelihatan jalan pas nge-test di layar utama, tapi mati total pas benar-benar main. Sekarang `ABS_HAT0X/Y` di-translate ke `DPAD_UP/DOWN/LEFT/RIGHT` lewat pipeline button yang sama, plus fallback `BTN_DPAD_*` buat controller yang kirim D-pad sebagai tombol diskrit.
- **`OverlayWysiwyg.tsx` — Settings Panel, Button Palette, Bottom Bar, dan Analog Stick Properties hilang**: commit `b559996` ("Update OverlayWysiwyg.tsx", hari ini) motong 248 baris kode asli dan ganti jadi komentar placeholder ("kode tetap sama seperti asli") tanpa benar-benar menyertakan kode-nya. Efeknya: nggak bisa nambah tombol baru, nggak bisa edit properti tombol yang udah ada, dan panel deadzone analog stick ilang dari UI overlay. Fitur "Phase 4" yang legit dari commit itu (macro recording button, scene selector) dipertahankan; kode yang hilang di-restore dari commit terakhir yang masih lengkap (`a9fbf73`), termasuk visualisasi gesture-path yang ikut ke-drop.
- **`InstalledGamesPlugin.kt`**: `com.gramedia.gramedia` (Gramedia, toko buku) dan `com.gojek.gopay` (GoPay, e-wallet) ke-list sebagai "game" di `knownGamePackages` — kemungkinan salah tempel package name. Dihapus.

### Known limitation (belum di-fix, butuh keputusan produk)
- Kalau device gagal di Path A & B dan turun ke Path C, drag stick analog tetap nggak akan gerak halus — shell `input tap` cuma dukung single-pointer DOWN/UP, bukan MOVE kontinu. Log warning di atas bikin ini kelihatan di logcat, tapi belum ada indikator di UI buat user. Opsi: tampilkan banner "mode terbatas" di app saat `activePath == "C"` — butuh method AIDL baru + wiring UI, sengaja belum disentuh di patch ini karena nggak bisa diverifikasi di device sungguhan dari sini.
- `GyroPlugin.kt` terdaftar sebagai Capacitor plugin dan berfungsi secara native, tapi nggak ada satu pun kode di `src/` yang manggil `Gyroscope.startListening()` atau dengerin event `gyroEvent`/`calibrationComplete` — fitur gyro-aim (kalau memang direncanakan) belum ke-wire ke UI sama sekali. Dead code, bukan bug, tapi sengaja nggak saya bikinin UI-nya karena itu fitur baru, bukan perbaikan.
- `src/components/ButtonPalette.tsx` dan `ButtonPropertyPanel.tsx` ada di repo tapi nggak di-import di mana pun — sepertinya sisa percobaan ekstraksi komponen yang nggak jadi dipakai (versi yang aktif dipakai adalah JSX inline di `OverlayWysiwyg.tsx`, yang barusan di-restore). Aman diabaikan atau dihapus, tergantung preferensi.
