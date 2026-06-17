import React, { useEffect, useState } from 'react';
import OverlayWysiwyg from './components/OverlayWysiwyg';
import { GamepadProfile } from './types';
import { INITIAL_PROFILES } from './defaults';
import { useGamepad } from './hooks/useGamepad';

// ============================================================
// Global Window interface extension for Android native bridge.
// These properties are injected by FloatingOverlayService.java
// via evaluateJavascript() calls. We declare them here so
// TypeScript knows about them.
// ============================================================
declare global {
  interface Window {
    AndroidOverlay?: {
      onReactReady: () => void;
      onCommand: (command: string) => void;
      closeOverlay: () => void;
    };
    injectConfig?: (configJson: string) => void;
    injectActiveKeys?: (keysJson: string) => void;
    injectActiveAxes?: (axesJson: string) => void;
    togglePalette?: (isOpen: boolean) => void;
  }
}

export default function OverlayApp() {
  const [profile, setProfile] = useState<GamepadProfile | null>(null);
  const [activeKeys, setActiveKeys] = useState<string[]>([]);
  const [activeAxes, setActiveAxes] = useState({ lx: 0, ly: 0, rx: 0, ry: 0 });

  const handleGamepadPress = React.useCallback((button: string, isPressed: boolean) => {
    setActiveKeys(prev => {
      if (isPressed && !prev.includes(button)) return [...prev, button];
      if (!isPressed && prev.includes(button)) return prev.filter(k => k !== button);
      return prev;
    });
  }, []);

  const handleGamepadAxis = React.useCallback((axes: { lx: number, ly: number, rx: number, ry: number }) => {
    setActiveAxes(axes);
  }, []);

  useGamepad(handleGamepadPress, handleGamepadAxis);

  useEffect(() => {
    // Prevent scrolling from keydown globally in overlay
    const handleKeyDown = (e: KeyboardEvent) => {
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', ' '].includes(e.key)) {
        e.preventDefault();
      }
    };
    window.addEventListener('keydown', handleKeyDown, { passive: false });

    // ============================================================
    // Expose functions to Android Native Java (FloatingOverlayService).
    // Guard with if-checks to prevent re-assignment on hot-reload
    // or React StrictMode double-mount in development.
    // ============================================================
    if (!window.injectConfig) {
      window.injectConfig = (json: string) => {
        try {
          const parsed = JSON.parse(json);
          setProfile(parsed);
        } catch (e) {
          console.error('[OverlayApp] Failed to parse injectConfig JSON:', e);
        }
      };
    }

    if (!window.injectActiveKeys) {
      window.injectActiveKeys = (json: string) => {
        try {
          const parsed = JSON.parse(json);
          if (Array.isArray(parsed)) {
            setActiveKeys(parsed);
          }
        } catch (e) {
          console.error('[OverlayApp] Failed to parse injectActiveKeys JSON:', e);
        }
      };
    }

    if (!window.injectActiveAxes) {
      window.injectActiveAxes = (json: string) => {
        try {
          const parsed = JSON.parse(json);
          if (parsed && typeof parsed === 'object' && 'lx' in parsed && 'ly' in parsed) {
            setActiveAxes(parsed);
          }
        } catch (e) {
          console.error('[OverlayApp] Failed to parse injectActiveAxes JSON:', e);
        }
      };
    }

    // Tell Android we are ready
    if (window.AndroidOverlay) {
      window.AndroidOverlay.onReactReady();
    } else {
      // Dev mode fallback — load default profile
      setProfile(INITIAL_PROFILES[0]);
    }

    // ============================================================
    // Cleanup: remove window properties on unmount to prevent
    // stale closures and memory leaks.
    // ============================================================
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      // Only delete if we own them (not re-assigned by another instance)
      delete window.injectConfig;
      delete window.injectActiveKeys;
      delete window.injectActiveAxes;
    };
  }, []);

  if (!profile) return null;

  // We are in play mode inside the service — background transparent
  return (
    <div className="w-screen h-screen bg-transparent pointer-events-none overflow-hidden">
      <OverlayWysiwyg
        activeProfile={profile}
        onUpdateProfile={(updated) => setProfile(updated)}
        onLogMessage={(msg) => console.log(msg)}
        activeKeys={activeKeys}
        activeAxes={activeAxes}
        isNativeOverlay={true}
      />
    </div>
  );
}
