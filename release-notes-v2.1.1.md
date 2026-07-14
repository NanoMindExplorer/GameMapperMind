# v2.1.1 — Multi-Pointer + Gamepad Compatibility Fix

**Release Date**: 2026-07-15
**Demo Video**: https://youtu.be/OtdO_hg2ZdI

## What's New

This release fixes critical multi-touch and gamepad compatibility bugs that prevented analog stick + button from working simultaneously. After 3 iterations of fixes, GameMapperMind now supports true multi-pointer touch injection.

## Bug Fixes

### Multi-Pointer Touch Injection (v3)

**Fixed**: Analog stick returns to center when any other button is pressed.

**Root cause**: `TouchDaemonService` created single-pointer MotionEvents with `ACTION_DOWN` for every pointer transition. When a second pointer (button) went DOWN while the stick was active, Android interpreted it as a new touch session — cancelling the stick's touch.

**Fix**: Rewrote touch injection to use correct Android multi-touch semantics:
- First pointer DOWN: `ACTION_DOWN`, `pointerCount=1`
- Additional pointer DOWN: `ACTION_POINTER_DOWN` with ALL active pointers
- Pointer UP while others active: `ACTION_POINTER_UP` (not `ACTION_UP`)
- `actionIndex` correctly encoded in action integer

### BTN_GAMEPAD = BTN_A Mapping (v3)

**Fixed**: Button A doesn't inject on generic Bluetooth gamepads.

**Root cause**: Many generic controllers report the A button as `BTN_GAMEPAD` in getevent. In Linux `input.h`, `BTN_GAMEPAD = BTN_SOUTH = BTN_A = 0x130` — they're the same code. The app only checked for `BTN_A` and `BTN_SOUTH` substrings, missing `BTN_GAMEPAD`.

**Fix**: Added explicit `BTN_GAMEPAD → "A"` mapping in `mapEvdevToButton()`.

### Combo Delay Elimination (v2)

**Fixed**: Player pauses briefly when any button is pressed while moving with the analog stick.

**Root cause**: Single AIDL dispatch thread (FIFO queue) — button events piled up in front of stick move events, freezing the stick for 50-100ms per button press.

**Fix**: Split into two threads:
- `stickAidlHandler` (MAX_PRIORITY) — analog touchDown/touchMove/touchUp with coalescing
- `buttonAidlHandler` (NORM_PRIORITY) — button touchDown/touchUp/injectTap

### Per-Pointer downTime (v2)

**Fixed**: Button A touchUp silently rejected when analog stick is active.

**Root cause**: Shared `baseDownTime` field was reset to 0 when L_STICK went UP, causing button A's UP event to use a mismatched downTime — Android silently drops ACTION_UP events with wrong downTime.

**Fix**: Replaced with `ConcurrentHashMap<Int, Long>` tracking downTime per pointer ID.

### Analog "Nyangkut ke Bawah" (v1)

**Fixed**: Analog stick drifts downward for a few frames after release.

**Root cause**: Deadzone check ran on the SMOOTHED magnitude, which decayed exponentially over several frames before dropping below deadzone — during decay, the touch position kept drifting toward center.

**Fix**: Deadzone check now runs on RAW input. Release is immediate when the physical stick returns inside the deadzone circle.

### Other Fixes (v1)

- `injectTap` now uses pointer ID 50 (was 0, colliding with L_STICK)
- `injectMotionEvent` no longer permanently locks to Path C (always retries A → B → C)
- `normalizeTrigger` heuristic fallback for non-255 trigger ranges
- `handleButton` now honors `interactionType` for ALL buttons (was only for buttons with `trigger` object)
- `mapEvdevToButton` added BTN_LT/BTN_RT aliases

## Full Changelog

See [CHANGELOG.md](https://github.com/NanoMindExplorer/GameMapperMind/blob/main/CHANGELOG.md) for the complete version history.

## Supported Games (Built-in Profiles)

- eFootball
- Genshin Impact
- PUBG Mobile
- Mobile Legends
- COD Mobile
- Free Fire

## Download

- **APK**: Download `app-release.apk` (or `app-debug.apk` for testing) from the Assets section below
- **Requirements**: Android 12+, Shizuku v13+

## Documentation

- [README](https://github.com/NanoMindExplorer/GameMapperMind#readme)
- [Wiki](https://github.com/NanoMindExplorer/GameMapperMind/wiki)
- [Installation Guide](https://github.com/NanoMindExplorer/GameMapperMind/wiki/Installation)

## Report Issues

Found a bug? [Open an Issue](https://github.com/NanoMindExplorer/GameMapperMind/issues) with:
1. Controller name and model
2. Game you're playing
3. On-screen log output (especially `[GAMEPAD-DETECT]` and `[GAMEPAD-KEY]` lines)
4. Steps to reproduce
