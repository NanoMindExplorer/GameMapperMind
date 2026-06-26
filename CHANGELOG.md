# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
