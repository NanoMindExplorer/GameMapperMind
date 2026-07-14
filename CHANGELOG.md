# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.1] - 2026-07-15 — Multi-Pointer + Gamepad Compatibility Fix
### Added
- **Game Test Demo video** — https://youtu.be/OtdO_hg2ZdI
- **Gamepad Compatibility table** di README — dokumentasi lengkap evdev code mapping per logical button
- **On-screen diagnostic log** sekarang menampilkan `[GAMEPAD-DETECT] axes: ... | buttons: ...` saat controller terdeteksi, dan `[GAMEPAD-KEY] Unmapped button BTN_XXX` untuk kode non-standard
- **BTN_GAMEPAD → A mapping** — BTN_GAMEPAD = BTN_A = 0x130 di Linux kernel (banyak generic Bluetooth gamepads pakai nama ini)

### Fixed
- **Multi-pointer MotionEvent** (root cause of "analog kembali ke tengah saat tombol lain ditekan"): rewrite `touchDown`/`touchMove`/`touchUp` di `TouchDaemonService` untuk menggunakan `ACTION_POINTER_DOWN`/`ACTION_POINTER_UP` dengan SEMUA pointer aktif dalam satu MotionEvent. Sebelumnya `ACTION_DOWN` dengan `pointerCount=1` membatalkan touch session stick yang aktif.
- **BTN_GAMEPAD filter salah** (root cause of "tombol A tidak menginjeksi"): v2 salah memfilter `BTN_GAMEPAD` sebagai meta event. Padahal BTN_GAMEPAD = BTN_A = 0x130 di Linux input.h — adalah tombol A yang sesungguhnya. Filter dihapus, mapping ditambahkan.
- **Shell fallback (Path C) hijack multi-touch**: Path C sekarang TIDAK PERNAH fire saat ada pointer aktif lain. Sebelumnya `input tap` shell command inject pointer 0 DOWN+UP yang membatalkan session multi-touch yang aktif.
- **Per-pointer downTime** (root cause of "tombol A tidak inject saat analog aktif"): ganti shared `baseDownTime` dengan `ConcurrentHashMap<Int, Long>` per pointer. Sebelumnya saat L_STICK UP me-reset `baseDownTime=0`, tombol A UP pakai `downTime=eventTime` yang tidak match dengan DOWN → Android reject ACTION_UP.
- **Combo delay** (root cause of "pemain diam sejenak saat tombol lain ditekan"): pisahkan AIDL dispatch ke dua thread — `stickAidlHandler` (MAX_PRIORITY + coalescing) dan `buttonAidlHandler` (NORM_PRIORITY). Sebelumnya single thread FIFO queue membuat button event numpuk di depan stick move → stick freeze 50-100ms.
- **Analog nyangkut ke bawah**: deadzone check sekarang pada RAW input (bukan smoothed) → release immediate saat stick kembali ke center.
- **injectTap pointer ID collision**: `injectTap` sekarang pakai pointer ID 50 (di luar range 0-63 analog/button per gamepad). Sebelumnya pakai pointer 0 = L_STICK → konflik.
- **Path C permanent lock**: `injectMotionEvent` tidak lagi cache `activePath = "C"`. Selalu retry A → B → C setiap call.
- **Trigger normalization fallback**: heuristic berdasarkan magnitude raw value (255/1023/4095/32767) untuk controller yang tidak terdeteksi range-nya.
- **handleButton interactionType**: sekarang honor `interactionType` (tap/turbo/toggle/charge/macro) untuk SEMUA button, bukan hanya yang punya object `trigger`.
- **nodeId collision**: `handleHoldInteraction` nodeId sekarang include `mappedKey` untuk uniqueness saat `mapping.id` kosong.

### Changed
- `TouchDaemonService` rewrite lengkap: hapus `baseDownTime`/`pointerDownTimes`, tambah `activePointers` ConcurrentHashMap + `gestureDownTime` shared per gesture
- `NativeGamepadMapper` companion object: hapus single `aidlThread`/`aidlHandler`, tambah `stickAidlThread` + `buttonAidlThread` + `pendingMoveRunnables` untuk coalescing
- `NativeGamepadMapper.processStick`: pakai `dispatchStickCall` (down/up) + `dispatchStickMove` (move, coalesced)
- `NativeGamepadMapper.handleHoldInteraction`/`handleToggle`/`resetGamepad`: pakai `dispatchButtonCall`
- `GamepadListenerService.mapEvdevToButton`: tambah BTN_GAMEPAD, BTN_LT, BTN_RT alias
- `GamepadListenerService.normalizeTrigger`: heuristic fallback untuk range non-255
- `GamepadListenerService.handleKeyEvent`: log unmapped button codes via `emitDiagnosticLog`
- README.md updated dengan Multi-Pointer MotionEvent section, Dual AIDL Dispatch Thread section, Gamepad Compatibility table, dan Changelog Ringkasan

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
- **Build gagal total — `Unresolved reference: runDiagnosticTestTap`** (ketauan dari CI build asli, bukan dari sandbox ini yang cuma bisa `tsc`, bukan compile Kotlin beneran): notification quick-action "Test Injection" (ditambahkan di 2.3.0) manggil `NativeGamepadMapper.instance?.runDiagnosticTestTap()` di 2 tempat, tapi fungsinya sendiri gak pernah ditulis — bug ini PRA-EXISTING, ada dari sebelum patch manapun di sesi ini. Selain bikin gagal compile, panggilan yang sama di `TouchDaemonService.testInjection()` juga gak akan pernah bisa jalan biar fungsinya ada sekalipun: `TouchDaemonService` jalan sebagai proses terpisah (Shizuku user service), jadi singleton `NativeGamepadMapper.instance` di proses app selalu `null` di situ. Fix: `TouchDaemonService.testInjection()` sekarang self-contained pakai `injectTap()` (Path A/B/C) yang udah ada, dan `NativeGamepadMapper.runDiagnosticTestTap()` (buat notification action) didefinisikan, delegasi ke AIDL `testInjection()` yang sudah diperbaiki. Sekalian di-cross-check manual semua pemanggilan fungsi lintas-file lain (AIDL, `GamepadJniPlugin`, `TouchInjectionPlugin`, `GamepadPlugin.instance`) — sisanya semua resolve dengan benar.

### Fixed (real-device testing — build sukses, tapi input masih bermasalah)
- **LT/RT gak bereaksi ditekan** — 3 akar masalah ditemukan & dibenerin sekaligus di jalur `getevent` (`GamepadListenerService.kt`, satu-satunya jalur input aktif pas main beneran):
  1. `normalizeTrigger()` asumsi range mentah trigger sama kayak stick (signed -32768..32767), padahal kebanyakan trigger controller itu unsigned (0..255 atau 0..1023). Dengan formula lama, trigger 0..255 selalu ternormalisasi ke ~0.50 baik dilepas maupun ditekan penuh — nembus threshold tekan sekali di awal terus gak pernah berubah lagi (nampak "gak bereaksi").
  2. `mapEvdevToButton` pakai `.contains("BTN_TL")` yang otomatis ketangkep juga sama nama standar evdev `"BTN_TL2"` (trigger digital) — Kotlin `"BTN_TL2".contains("BTN_TL")` itu `true`. Jadi kalau controller kirim trigger sebagai tombol digital, malah kepetakan ke LB/RB, bukan LT/RT.
  3. Ditambahkan deteksi adaptif per-device: kalau device gak punya axis `ABS_RX`/`ABS_RY` sama sekali tapi punya `ABS_Z`/`ABS_RZ`, axis itu sekarang dianggap stick kanan (bukan trigger) — banyak controller generik/murah pakai konvensi ini, bukan konvensi Xbox/xpad yang diasumsikan kode lama.
- **Analog kanan mati** — akar masalahnya SAMA dengan poin LT/RT di atas (#3): kalau controller-nya pakai `ABS_Z`/`ABS_RZ` buat stick kanan, kode lama malah baca itu sebagai trigger, jadi stick kanan gak pernah kekirim ke mana pun. Deteksi adaptif di atas juga menyelesaikan ini.
- **Analog kiri nyangkut / gak smooth** — `normalizeAxis()` sebelumnya hardcode asumsi range mentah `-32768..32767` buat SEMUA controller. `detectGamepadDevice()` sebenernya udah menjalankan `getevent -lp` (buat nyari device path) yang isinya JUGA berisi range asli tiap axis (`min X, max Y`) — sebelumnya info ini dibuang, cuma path device-nya yang dipakai. Sekarang di-parse dan dipakai buat normalisasi yang akurat sesuai device asli, bukan tebakan.
- Semua fix di atas cuma menyentuh jalur `getevent` (`GamepadListenerService.kt`) yang aktif pas gameplay sungguhan. Jalur `GamepadPlugin.kt` (aktif pas testing di layar utama app) sudah lebih dulu robust — sudah baca AXIS_RX/RY DAN AXIS_Z/RZ sekaligus pilih yang ada sinyal, dan sudah punya fallback KEYCODE_BUTTON_L2/R2 — jadi kenapa testing di layar utama kelihatan oke tapi pas main beneran rusak: dua jalur itu emang gak sama levelnya sebelum patch ini.
- Diaudit ulang canvas WYSIWYG editor (`useOverlayWysiwyg.ts`) dan native floating/"K2er-style" overlay (`FloatingOverlayService.java`) — keduanya sudah cukup matang dari audit-audit sebelumnya, gak ketemu bug baru yang berdiri sendiri di situ. Kemungkinan besar gejala "canvas/K2er style bermasalah" yang dilaporkan adalah manifestasi visual dari bug analog stick di atas (indikator posisi stick di canvas ikut baca data axis yang salah), bukan bug terpisah di layer overlay UI-nya.

## [Unreleased] — Penyisiran menyeluruh (seluruh src/ + android/)
### Fixed
- **Akar masalah lain untuk "analog gak smooth"**: slider "Stick Deadzone Axis" dan "Exponential Smoothing Damping" di tab Profile Manager (`GameSelector.tsx`) mengubah field level-*profile* (`GamepadProfile.deadzone`/`.smoothing`), tapi `NativeGamepadMapper.handleAxes()` sebenarnya baca field level-*tombol* per L_STICK/R_STICK (`mapping.optDouble("deadzone"/"smoothing", ...)`) — slider itu geser tapi gak ngefek apa-apa ke device. Lebih parah: TIDAK ADA satu pun profile bawaan yang nge-set `smoothing` per-tombol, jadi fallback-nya selalu `0.0` (= filter smoothing efektif MATI) untuk semua profile termasuk eFootball, dan sebelumnya gak ada UI sama sekali buat ngeset itu. Fix (4 bagian):
  1. Field `smoothing` ditambahkan ke tipe `VirtualButton` dan `VirtualButtonSchema` (sebelumnya tipe-nya sendiri gak mendeklarasikan field yang udah dibaca native).
  2. Slider "Smoothing" baru ditambahkan ke panel Analog Stick Properties di Overlay Canvas (sejajar sama Deadzone yang udah ada).
  3. Semua L_STICK/R_STICK di profile bawaan (`defaults.ts`) sekarang punya `smoothing: 0.18` (dulu gak ada sama sekali → fallback 0.0).
  4. Slider level-profile di Profile Manager sekarang di-cascade ke semua tombol `analog_stick` saat diubah, jadi "atur untuk seluruh profile" itu beneran ngefek — panel per-stick di Overlay Canvas masih bisa dipakai buat fine-tune satu stick aja kalau perlu.
- **`TouchDaemonService.testInjection()` — nama field JSON gak nyambung ke UI**: fix compile-error sebelumnya bikin fungsi ini return `success`/`path`/`failCount`, tapi `ShizukuPanel.tsx` dan `OnboardingWizard.tsx` udah lama nunggu field `inputManager_null`/`injectMethod_null`/`touchDown_result`/`shellInputTap_result`/`useShellFallback` — jadi tombol "Test Injection" bakal nampilin `undefined` di semua baris diagnostik walau tap-nya sendiri berhasil. Sekarang kedua set field itu dikirim sekaligus.

### Known issues (dicatat, sengaja belum disentuh — butuh keputusan produk atau kerja fitur baru, bukan bug fix)
- Tab "Desktop ADB Companion" di `ShizukuPanel.tsx` murni dekoratif — tombol "INITIALIZE VIA DESKTOP ADAPTER" manggil fungsi Shizuku yang persis sama kayak tombol boot biasa, dan teks soal "Electron/Node.js companion script" + `/data/local/tmp/gmm_daemon` menjelaskan sebuah companion desktop yang gak pernah dibangun.
- Native macro recording (`NativeGamepadMapper.startMacroRecording`/`stopMacroRecording`) cuma nyetel flag `isRecordingMacro`, gak pernah benar-benar nangkep event tombol/axis ke `recordedMacros`, dan gak ada accessor buat baca hasilnya balik. Tombol "Record Macro" di top bar Overlay Canvas masih TODO stub di sisi JS juga. ­Fitur ini belum selesai dibangun end-to-end (native capture logic-nya sendiri belum ada), bukan sekadar kabel yang putus — butuh kerja fitur baru, bukan patch.
- Klaster kode mati terkonfirmasi gak dipakai di mana pun: `ProfileToolbar.tsx`, `ScreenshotBackground.tsx`, `OverlayCanvas.tsx` (selain `ButtonPalette.tsx`/`ButtonPropertyPanel.tsx` yang udah dicatat sebelumnya). Aman dihapus kalau mau beres-beres, gak mempengaruhi apa pun yang jalan.

## [Unreleased] — Respons ke laporan "touchDown FAILED + shellInputTap FAILED" di device asli
### Fixed
- **Exception di Path A/B/C gak pernah di-log** — `tryPathA`/`tryPathB`/`shellInputTap` di `TouchDaemonService.kt` sebelumnya nelan exception/exit-code tanpa jejak sama sekali. Kalau ketiga jalur gagal bareng (kayak yang dilaporkan: `InputManager: OK`, `injectMethod: OK`, tapi `touchDown: FAILED` dan `shellInputTap: FAILED`), sebelumnya gak ada cara buat tau APAKAH itu `SecurityException` (indikasi device/OEM ngeblokir injeksi walau Shizuku konek — umum di MIUI/ColorOS), exit-code shell, atau sebab lain. Sekarang: exception asli (termasuk `cause` di balik `InvocationTargetException` dari reflection) dan exit-code+stderr shell di-log ke logcat DAN ditampilkan langsung di app lewat field baru `lastError` pada hasil "Test Injection".
- Kalau kamu masih dapet `touchDown FAILED` + `shellInputTap FAILED` bareng setelah patch ini, coba lagi tombol "Test Injection" dan baca baris `[TEST] Last error: ...` yang baru muncul — itu bakal nunjukin akar masalah aslinya (paling sering: izin device/OEM, bukan bug kode).

### Added — Game Screen Calibration (basis untuk "posisi mapping gak sesuai layar game")
- Ternyata overlay window-nya emang sudah full-screen (`MATCH_PARENT` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`), tapi TIDAK ADA mekanisme kompensasi kalau game yang di-mapping tidak render edge-to-edge (status bar kelihatan, letterbox, aspect ratio beda) — semua koordinat diasumsikan 1:1 dengan layar fisik penuh.
- 4 field baru per-profile: `screenInsetTop/Bottom/Left/Right` (% dari layar penuh, default 0 = perilaku lama, gak ada regresi buat yang belum kalibrasi).
- UI kalibrasi baru di tab Profile Manager → Damping Parameters: 4 slider (Top/Bottom/Left/Right Inset).
- Boundary "Game Play Area" (kotak putus-putus cyan) sekarang muncul di Overlay Canvas kalau ada inset yang di-set, jadi kelihatan langsung di mana batas kalibrasinya.
- `NativeGamepadMapper.getScreenCoords()` (jalur touch injection asli) DAN `FloatingOverlayService.rebuildFloatingButtons()` (indikator visual K2-style) sekarang sama-sama pakai rumus kalibrasi yang sama — sebelumnya kalau ini cuma dibenerin di satu tempat, indikator K2-style bakal nampilin posisi yang beda dari titik sentuh sebenarnya.
- **Catatan**: ini alat kalibrasi MANUAL (kamu geser slider sampai pas), bukan deteksi otomatis area render game. Deteksi otomatis butuh baca window bounds app yang lagi foreground (misal lewat `dumpsys window` atau AccessibilityService) — itu kerja fitur baru yang lebih besar, sengaja belum saya bangun blind tanpa bisa tes di device.

## [Unreleased] — Respons ke screenshot: "posisi tombol sangat jauh dan tidak tepat"
### Fixed
- **Root cause dikonfirmasi via screenshot**: canvas editor WYSIWYG punya top bar (~52px) di atasnya, sementara overlay window asli di device itu full-screen tanpa top bar. Screenshot yang di-upload dirender pakai `object-contain` (preserve aspect ratio) di dalam container yang aspect ratio-nya BEDA dari layar fisik (karena top bar makan tinggi) — hasilnya screenshot ke-pillarbox/letterbox di dalam canvas, tapi posisi tombol (x/y %) dihitung relatif ke CONTAINER PENUH, bukan ke area screenshot yang sebenarnya kelihatan. Di runtime, `getScreenCoords()` motong kompas dan mapping % itu langsung ke layar fisik penuh (1:1, gak ada top bar) — dua ruang koordinat yang beda, makanya posisi jauh meleset.
- Ada state `screenshotDimensions` yang SEBENARNYA udah dibuat sebelumnya khusus buat masalah ini (komentar di kode udah nyebut persis akar masalah yang sama), tapi `setScreenshotDimensions` gak pernah dipanggil dan valuenya gak pernah dipakai — pola "fix setengah jalan" yang sama kayak beberapa bug lain di codebase ini.
- **Fix**: `useOverlayWysiwyg.ts` sekarang benar-benar menghitung `contentRect` — persegi panjang tempat screenshot RENDER SEBENARNYA di dalam canvas (matematika letterbox/pillarbox yang sama kayak `object-contain`, tapi dihitung eksplisit) — pakai `ResizeObserver` buat ukuran container + `onLoad` di `<img>` buat nangkep dimensi asli screenshot. `OverlayWysiwyg.tsx` sekarang bungkus screenshot + semua node tombol + boundary kalibrasi di dalam wrapper `#content-rect` yang persis pas sama rect itu, dan semua kalkulasi drag (`handleDragStart`/`handleDragMove`/`handleDragEnd`) sekarang pakai rect ini juga — bukan rect container penuh. Hasilnya: 0-100% di editor sekarang benar-benar = 0-100% dari screenshot = 0-100% dari layar fisik device, konsisten end-to-end.
- Berlaku otomatis buat KEDUA overlay style (Canvas & Floating/K2) karena keduanya baca data tombol (x/y %) yang sama — begitu posisinya benar di editor, keduanya ikut benar di runtime.

### Belum terjawab — butuh info dari kamu
- Soal "gak bisa sentuh/gerakan layar saat overlay aktif": saya cek ulang flag window (`FLAG_NOT_TOUCHABLE`) di kedua mode overlay dan teardown logic pas ganti mode — keduanya kelihatan benar secara kode, jadi saya gak nemu bug konkret yang bisa saya benerin blind. Kemungkinan besar ini masih terkait sama `touchDown FAILED` + `shellInputTap FAILED` yang dilaporkan sebelumnya. **Saya masih butuh baris `[TEST] Last error: ...` dari tombol "Test Injection"** (fitur ini sudah ada di patch sebelumnya) buat mastiin akar masalahnya sebelum saya berani nebak fix lagi.

## [Unreleased] — Floating (K2-style): fix mode edit yang bisa nyangkut permanen
### Fixed
- **Overlay bisa ketahan di mode Edit (touchable) selamanya**: notification "Edit Layout" (khusus Floating/K2-style) toggle window jadi touchable buat dibawa ke app. Kalau user tap "Edit Layout" terus gak pernah tap "Resume Play" balik (misal cuma back/home dari app), window-nya TETAP touchable — nyerap semua sentuhan jari — bahkan setelah overlay di-restart lagi dari app, karena kode lama cuma push config pas restart, gak pernah reset touchability-nya. Sekarang: setiap kali app eksplisit manggil "Start Overlay" (bukan dari notification action), overlay dipaksa balik ke play mode otomatis — sesuai intent user yang jelas-jelas mau main, bukan lanjut edit dari sesi sebelumnya yang mungkin udah kelupaan.
- **Catatan penting**: ini FIX UNTUK SATU SKENARIO SPESIFIK (edit mode ketahan), BUKAN konfirmasi bahwa ini akar masalah "gak bisa sentuh layar" yang dilaporkan — itu masih butuh konfirmasi dari kamu (cek notification-nya nunjukin "Edit Layout" atau "Resume Play" pas masalahnya kejadian).

## [Unreleased] — AKAR MASALAH KETEMU: "seluruh layar terblokir, gak bisa keluar app sama sekali"
### Fixed
- **Root cause terkonfirmasi**: ini kebijakan keamanan resmi Android 12+ ("Untrusted Touch Events"), bukan bug logika di kode. Sejak Android 12, sistem BLOKIR touch yang "tembus" (pass-through) lewat window `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` — TAPI CUMA kalau overlay itu nampil di atas APLIKASI LAIN (persis kasus GameMapperMind nge-overlay eFootball; exemption "interactions within your own app" gak berlaku di sini). Ini system-wide security block, bukan cuma app di bawahnya yang keblokir — makanya gesture home/back juga ikut gak jalan, cuma notification shade yang tetap bisa disentuh (itu window sistem terpisah, di luar restriction ini). Referensi resmi: https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events
- **Fix**: satu-satunya exemption resmi yang gak butuh Accessibility Service adalah window alpha (opacity) ≤ `InputManager.getMaximumObscuringOpacityForTouch()` (default 0.8/80%). Kedua window overlay (Canvas & Floating/K2-style) sekarang di-set `alpha = 0.75f` khusus di Android 12+ (`Build.VERSION.SDK_INT >= S`), biar masuk exemption "sufficiently translucent" dan touch-nya beneran tembus lagi — termasuk gesture sistem yang sebelumnya ikut keblokir.
- **Efek samping visual yang disengaja**: indikator tombol overlay bakal kelihatan sedikit lebih transparan (~25%) di Android 12 ke atas dibanding sebelumnya. Ini trade-off yang perlu buat exemption-nya berlaku — device di bawah Android 12 gak kena efek ini sama sekali (kode di-guard versi).
- **Yang masih perlu dites**: fix ini ngerjain skenario PLAY MODE (pass-through, state normal/"Resume Play") yang paling penting buat gameplay sungguhan. Kalau mode EDIT ("Edit Layout") di Floating/K2-style masih kerasa "gak ngapa-ngapain" — itu WAJAR sebenarnya, karena floating mode memang gak punya UI edit-di-tempat (`no in-place drag-to-reposition`, per desain), tap "Edit Layout" cuma nyoba buka MainActivity via toast. Kalau mau EDIT posisi tombol beneran, pakai Canvas mode dari app, bukan floating.

## [Unreleased] — AKAR MASALAH SEBENARNYA dari `touchDown FAILED` + `shellInputTap FAILED`: bug parameter, bukan izin OS
### Fixed
- **KETEMU dari toast in-app**: "Test Tap GAGAL: pointerCount must be at least 1" — ini bukan SecurityException/pembatasan OEM seperti dugaan sebelumnya, ini murni bug argumen di `MotionEvent.obtain(...)`. Parameter ke-4 pada overload ini HARUS `pointerCount`, tapi kode lama ngirim `actionIndex` (yang nilainya selalu 0 dari `injectSinglePointer`) di posisi itu — jadi SETIAP panggilan injeksi minta Android bikin `MotionEvent` dengan `pointerCount=0`, yang otomatis ditolak. Ini menjelaskan kenapa `InputManager`/`injectMethod` resolve OK (setup reflection-nya emang gak pernah masalah) tapi invoke-nya selalu gagal — bukan cuma di Test Injection, tapi di SETIAP `touchDown`/`touchMove`/`touchUp`/`injectTap`, yang berarti ini kemungkinan besar akar dari SEMUA laporan "gamepad gak ada reaksi ke game" sepanjang sesi debugging ini, bukan cuma soal LT/RT.
- Dicek juga: cuma ADA satu titik `MotionEvent.obtain()` di seluruh codebase, jadi gak ada duplikasi bug yang sama di tempat lain.
- **Diagnostic baru**: info axis (`ABS_*`) dan tombol (`BTN_*`) mentah yang beneran dikirim controller kamu sekarang muncul di tab "Sensor & Input Diagnostics" (baris `[GAMEPAD-DETECT] ...`) — sebelumnya cuma bisa dilihat lewat `adb logcat`. Ini biar investigasi kenapa LT/RT spesifik gak "menyala" di canvas (kalau masih terjadi setelah fix pointerCount) bisa dilacak pakai data asli dari controller kamu, bukan tebak-tebakan konvensi axis lagi.

## [Unreleased] — Akar masalah "RB tekan → analog berhenti", delay umum, "analog nyangkut"
### Fixed
- **Root cause**: SEMUA event gamepad (tombol MAUPUN stick) diproses lewat SATU thread (`GamepadJniPlugin.injectionThread`), dan SETIAP panggilan touch injection (`touchDown`/`touchMove`/`touchUp`/`injectTap`) itu panggilan Binder SINKRON lintas-proses ke daemon Shizuku — thread yang manggil BLOCK sampai daemon-nya bales. Kalau ada tombol yang lagi diproses (misal RB, yang notabene pakai tipe interaksi "hold" default), thread itu KETAHAN nunggu balesan daemon, dan SELAMA itu update axis/stick yang baru cuma numpuk nimpa data lama (coalesced) tanpa pernah sempat diproses — persis kerasa kayak "analog berhenti total" pas RB ditahan.
- **Fix**: pengambilan keputusan (baca axis, deadzone/smoothing math, alokasi pointer, deteksi edge tombol) TETAP di satu thread yang sama (gak nambah race condition baru), tapi EKSEKUSI panggilan AIDL yang lambat sekarang dipindah ke 1 thread background KHUSUS (`TouchAidlDispatch`) — thread background ini tetap SATU (bukan pool), jadi urutan pengiriman touch event tetap terjaga FIFO persis kayak sebelumnya, cuma gak lagi nge-block thread pengambilan-keputusan. Nyentuh 14 titik panggilan di `NativeGamepadMapper.kt` (stick down/move/up, semua tipe interaksi tombol: hold/toggle/charge/tap/turbo/macro).
- **Temuan tambahan saat nyisir semua titik panggilan**: mode Turbo dan pemutaran Macro sebelumnya manggil `injectTap()` langsung di `mainHandler` (thread UI!) — jadi tiap repeat turbo/macro sempat nge-block UI thread juga selama durasi round-trip AIDL-nya. Ikut kebenerin sekalian pakai mekanisme dispatch yang sama.
- `PointerState.isActive`/`.virtualKey` sekarang `@Volatile` karena ditulis dari 2 thread berbeda (decision thread pas alokasi, background thread pas rollback kegagalan) — gak ada race check-then-act karena keputusan ALOKASI tetap eksklusif di decision thread, background thread cuma pernah nulis balik `isActive = false` kalau gagal.

### Belum terjawab — butuh info/klarifikasi
- **LT, RT, A "tidak menginjeksi"**: fix `pointerCount` di atas kemungkinan udah nyelesain sebagian besar kasus injeksi gagal, tapi kalau 3 tombol ini SPESIFIK masih gak jalan setelah 2 patch terakhir (pointerCount + threading), saya butuh baris `[GAMEPAD-DETECT] ...` yang baru (muncul di tab Diagnostics begitu controller kedetect) — itu nunjukin persis axis/tombol mentah apa yang beneran dikirim controller kamu, jadi saya gak nebak lagi soal LT/RT. Buat "A" spesifik: tolong cek juga apa tombol A punya posisi mapping valid di profile yang lagi aktif (bukan kosong/belum di-drag ke posisi).
- **"Layar ikut bergerak" pas tes analog di tab Diagnostics**: kalau maksudnya indikator titik stick kiri/kanan di tab Sensor & Input Diagnostics ikut bergerak pas gamepad digerakin — itu MEMANG perilaku yang disengaja (indikator visual buat mastiin axis kebaca), bukan bug. Kalau maksudnya sesuatu yang lain (misal seluruh halaman/scroll ikut gerak), tolong dijelasin lebih detail atau kirim screenshot, saya belum yakin persis apa yang dimaksud.

## [Unreleased] — Respons ke laporan: A/LT/RT masih gak inject, RT ikut memicu LT, log tombol A berulang tanpa UP
### Fixed
- **Log `[GAMEPAD] A DOWN` berulang 7x tanpa `UP` di antaranya**: `getevent -l` melaporkan event evdev mentah, dan tombol yang ditahan lama umumnya ngirim event repeat (value=2) selain event press awal (value=1) — sebelumnya SETIAP baris berlabel "DOWN" langsung diteruskan apa adanya ke pipeline injeksi DAN ke log layar, jadi satu kali tahan fisik bisa kelihatan (dan diproses ulang) seolah beberapa kali pencet terpisah. Sekarang di-dedupe di titik paling awal (dilacak per nama tombol evdev mentah) — cuma transisi press/release asli yang diteruskan.
- Perubahan ini kemungkinan turut membantu keluhan "delay" secara umum juga, karena mengurangi kerja berulang yang gak perlu di thread pemrosesan.

### Masih belum terjawab — 3 hal ini butuh data spesifik, bukan tebakan lagi
- **RT memicu LT ikut kepencet**: ini gejala yang sangat spesifik dan saya BELUM nemu penyebabnya lewat baca kode — `handleTrigger("LT", ...)` dan `handleTrigger("RT", ...)` pakai key `lastState` yang berbeda (`"LT0"` vs `"RT0"`), gak ada tumpang tindih yang kelihatan di kode. Saya butuh baris `[GAMEPAD-DETECT] axes: ... | buttons: ...` (fitur ini sudah ada sejak patch `pointercount-fix`) buat tau PERSIS axis/tombol mentah apa yang dikirim controller kamu untuk LT/RT — apa dia analog (`ABS_Z`/`ABS_RZ`) atau digital (`BTN_TL2`/`BTN_TR2`), dan apa keduanya benar-benar 2 sinyal terpisah atau cuma 1 axis yang disalahartikan jadi 2. **Baris ini cuma muncul SEKALI, pas gamepad pertama kali kedetect** — kalau kamu gak restart overlay/app dari awal setelah controller connect, baris ini gak akan muncul lagi. Tolong: stop overlay → tutup app total (force close) → buka lagi → connect gamepad → start overlay → scroll ke ATAS log diagnostic, cari baris `[GAMEPAD-DETECT]`, kirim ke saya.
- **A tetap gak inject ke game** meskipun terdeteksi (ada di log `[GAMEPAD] A DOWN`): kemungkinan konsisten dengan LT/RT (butuh data yang sama di atas), TAPI tolong cek juga hal sederhana ini dulu — di Profile Manager/Overlay Canvas, apa tombol A punya posisi mapping yang valid (bukan default kosong)? Kalau kamu belum pernah nge-drag node "A" ke posisi tertentu di layar, itu bisa jadi penyebabnya sendiri, terpisah dari bug apa pun.
- **Analog masih gak smooth**: fix threading kemarin harusnya udah ngurangin sebagian besar penyebabnya (RB gak lagi nge-block stick). Kalau masih berasa gak smooth SETELAH restart bersih + patch dedup ini, tolong jelasin lebih spesifik — "gak smooth" itu kayak nyendat-nyendat (stutter), delay konsisten (semua gerakan telat sekian ratus ms), atau gerakannya gak presisi (overshoot/kurang sensitif)? Tiap gejala itu nunjuk ke penyebab teknis yang beda.

### Known limitation (belum di-fix, butuh keputusan produk)
- Kalau device gagal di Path A & B dan turun ke Path C, drag stick analog tetap nggak akan gerak halus — shell `input tap` cuma dukung single-pointer DOWN/UP, bukan MOVE kontinu. Log warning di atas bikin ini kelihatan di logcat, tapi belum ada indikator di UI buat user. Opsi: tampilkan banner "mode terbatas" di app saat `activePath == "C"` — butuh method AIDL baru + wiring UI, sengaja belum disentuh di patch ini karena nggak bisa diverifikasi di device sungguhan dari sini.
- `GyroPlugin.kt` terdaftar sebagai Capacitor plugin dan berfungsi secara native, tapi nggak ada satu pun kode di `src/` yang manggil `Gyroscope.startListening()` atau dengerin event `gyroEvent`/`calibrationComplete` — fitur gyro-aim (kalau memang direncanakan) belum ke-wire ke UI sama sekali. Dead code, bukan bug, tapi sengaja nggak saya bikinin UI-nya karena itu fitur baru, bukan perbaikan.
- `src/components/ButtonPalette.tsx` dan `ButtonPropertyPanel.tsx` ada di repo tapi nggak di-import di mana pun — sepertinya sisa percobaan ekstraksi komponen yang nggak jadi dipakai (versi yang aktif dipakai adalah JSX inline di `OverlayWysiwyg.tsx`, yang barusan di-restore). Aman diabaikan atau dihapus, tergantung preferensi.
