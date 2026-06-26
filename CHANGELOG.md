# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.0] - 2026-06-27
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
