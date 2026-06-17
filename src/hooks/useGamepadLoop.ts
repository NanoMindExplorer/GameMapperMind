import { useEffect, useRef } from 'react';
import GameMapper from '../plugins/GameMapper';
import { Capacitor } from '@capacitor/core';

/**
 * useGamepadLoop — Pushes gamepad profile + anti-ban config to native pipeline.
 *
 * FIX #1 (Audit #1): Removed dual injection path (Path A — JS-side injection).
 * FIX #16 (Audit #2): Updated stale comments to reflect current behavior.
 *
 * All touch injection is now handled EXCLUSIVELY by native pipeline (Path B):
 *   evdev → GameMapperUserService → forwardEventToPipeline
 *   → GameMapperPluginImpl.onGamepadButton → InputPipelineWorker.onButtonEvent
 *   → TouchInjector.analogMove/touchUp (single instance, shell privilege)
 *
 * This hook now ONLY:
 *   1. Pushes profile JSON to native pipeline when profile changes
 *   2. Pushes anti-ban config to native pipeline when profile changes
 *
 * It does NOT:
 *   - Listen to onGamepadButton/onGamepadAxis events for injection
 *   - Call GameMapper.injectTap/injectSwipe/injectTouchUp
 *   - Manage pointer IDs in JavaScript
 *   - Handle button press logic (native pipeline does this)
 *
 * The native pipeline (Path B) handles ALL gamepad-to-touch injection:
 *   - Button press → InputPipelineWorker.onButtonEvent → TouchInjector.analogMove/touchUp
 *   - Analog stick → InputPipelineWorker.processAnalogSticks → TouchInjector.analogMove/touchUp
 *   - Triggers → InputPipelineWorker.onTriggerEvent → TouchInjector.analogMove/touchUp
 */
export function useGamepadLoop(mapProfile: any, active: boolean) {
  const lastProfileRef = useRef<string>('');

  // Push anti-ban config + profile to native daemon whenever profile changes
  useEffect(() => {
    if (!active) return;

    // Push anti-ban config to native pipeline
    const cfg = mapProfile?.antiBanConfig;
    const enabled = !!mapProfile?.antiBanEnabled && !!cfg?.enabled;
    if (cfg && Capacitor.isNativePlatform()) {
      GameMapper.setAntiBanConfig({
        enabled,
        coordinateJitter: cfg.coordinateJitter ?? 4,
        timingJitterMs: cfg.timingJitterMs ?? 3,
        pressureVariance: cfg.pressureVariance ?? 0.15,
        sizeVariance: cfg.sizeVariance ?? 0.10,
      }).catch((e: any) => console.warn('setAntiBanConfig failed', e));
    }

    // Push profile to native pipeline for evdev-based injection (Path B only)
    if (mapProfile && Capacitor.isNativePlatform()) {
      try {
        const profileJson = buildProfileJson(mapProfile);
        // Only push if profile actually changed (avoid redundant AIDL calls)
        if (profileJson !== lastProfileRef.current) {
          lastProfileRef.current = profileJson;
          GameMapper.setActiveProfile({ profileJson }).catch((e: any) =>
            console.warn('setActiveProfile failed', e)
          );
        }
      } catch (e) {
        console.warn('Failed to build profile JSON', e);
      }
    }
  }, [mapProfile, active]);

  // ============================================================
  // buildProfileJson — converts React profile object to JSON format
  // expected by InputPipelineWorker.setProfileFromJson()
  //
  // Coordinate system:
  //   touchX = buttonPixelX / screenW  → percentage [0.0..1.0]
  //   touchY = buttonPixelY / screenH  → percentage [0.0..1.0]
  //   centerX = stickCenterPixelX / screenW → percentage [0.0..1.0]
  //   radius = stickRadiusPixels / screenW → percentage [0.0..1.0]
  //
  // The native pipeline (InputPipelineWorker) multiplies these percentages
  // by the actual DisplayMetrics screenWidth/screenHeight (overridden by
  // GameMapperPluginImpl.setProfile()) to get absolute pixel coordinates.
  //
  // Screen dimensions use availWidth/availHeight (excludes system UI)
  // with landscape normalization (width = larger axis).
  // ============================================================
  function buildProfileJson(profile: any): string {
    const rawW = window.screen.availWidth || window.screen.width;
    const rawH = window.screen.availHeight || window.screen.height;
    const screenW = Math.max(rawW, rawH); // landscape normalize
    const screenH = Math.min(rawW, rawH);

    const buttons = (profile.mappings || []).map((m: any) => ({
      hardwareKey: m.hardwareKey,
      touchX: m.x / screenW,
      touchY: m.y / screenH,
      actionType: 'tap',
      swipeDirection: null,
    }));

    const leftStick = profile.leftJoystick ? {
      centerX: profile.leftJoystick.centerX / screenW,
      centerY: profile.leftJoystick.centerY / screenH,
      radius: profile.leftJoystick.radius / screenW,
      deadzone: profile.deadzone ?? 0.15,
      smoothing: profile.smoothing ?? 0.3,
    } : null;

    const rightStick = profile.rightJoystick ? {
      centerX: profile.rightJoystick.centerX / screenW,
      centerY: profile.rightJoystick.centerY / screenH,
      radius: profile.rightJoystick.radius / screenW,
      deadzone: profile.deadzone ?? 0.15,
      smoothing: profile.smoothing ?? 0.3,
    } : null;

    return JSON.stringify({
      packageName: profile.packageName || '',
      screenWidth: screenW,
      screenHeight: screenH,
      displayId: 0,
      buttons,
      leftStick,
      rightStick,
    });
  }
}
