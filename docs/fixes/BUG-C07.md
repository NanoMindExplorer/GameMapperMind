# BUG-C07: Touch Injection Source Flag (Anti-Cheat)

File: `android/app/src/main/java/com/nanomindexplorer/gamemappermind/TouchDaemonService.kt`, `src/components/GameSelector.tsx`, `src/types.ts`, `src/schemas/profile.ts`, `android/app/src/main/aidl/com/nanomindexplorer/gamemappermind/ITouchService.aidl`

Diff: 
- Added `inputSource` and `toolType` to `VirtualButton` and `profile`.
- Added `updateConfig` to `ITouchService.aidl`.
- Randomization (Box-Muller) is added in `TouchDaemonService` for `pressure` and `size`, using standard deviation of 0.04 and 0.05 respectively. Added +/- 1px jitter to coordinates.
- Overwrote generic touchscreen injection properties so the event is disguised as `SOURCE_MOUSE` (safest configuration for anti-cheat software), while the UI configures these dynamically.

Command Output:
```bash
$ cd android && ./gradlew assembleDebug

BUILD SUCCESSFUL in 8s
```

Description: Now all touches are accurately randomized utilizing Gaussian distribution, bypassing naive cloned event triggers.
