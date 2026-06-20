import React, { useEffect, useState } from 'react';
import OverlayWysiwyg from './components/OverlayWysiwyg';
import { GamepadProfile } from './types';
import { INITIAL_PROFILES } from './defaults';
import { GamepadProfileSchema } from './schemas/profile';

// Declare native interface
declare global {
  interface Window {
    AndroidOverlay?: {
      onReactReady: () => void;
      setInteractive: (interactive: boolean) => void;
      closeOverlay: () => void;
    };
    injectConfig: (configJson: string) => void;
    injectActiveKeys: (keysJson: string) => void;
    injectActiveAxes: (axesJson: string) => void;
    togglePalette?: (isOpen: boolean) => void;
  }
}


export default function OverlayApp() {
  const [profile, setProfile] = useState<GamepadProfile | null>(null);
  const [activeKeys, setActiveKeys] = useState<string[]>([]);
  const [activeAxes, setActiveAxes] = useState({lx:0, ly:0, rx:0, ry:0});

  useEffect(() => {
    // Prevent scrolling from keydown globally in overlay
    const handleKeyDown = (e: KeyboardEvent) => {
      // Prevent scrolling from arrow keys / space
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', ' '].includes(e.key)) {
        e.preventDefault();
      }
    };
    window.addEventListener('keydown', handleKeyDown, { passive: false });

    // Expose functions to Android Native Java
    window.injectConfig = (json: string) => {
      try {
        setProfile(JSON.parse(json));
      } catch(e) { console.error('Failed to parse config'); }
    };

    window.injectActiveKeys = (json: string) => {
      try { setActiveKeys(JSON.parse(json)); } catch(e) {}
    };

    window.injectActiveAxes = (json: string) => {
      try { setActiveAxes(JSON.parse(json)); } catch(e) {}
    };

    const handleMessage = (e: MessageEvent) => {
      // Check origin if possible, but Capacitor WebView might be arbitrary 'http://localhost'
      if (e.origin !== "https://appassets.androidplatform.net" && e.origin !== "http://localhost") {
        return;
      }
      try {
        if (e.data) {
          const parsed = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
          const result = GamepadProfileSchema.safeParse(parsed);
          if (result.success) {
            setProfile(result.data as GamepadProfile);
          } else {
            console.warn('Invalid profile received', result.error);
          }
        }
      } catch (err) {}
    };
    window.addEventListener('message', handleMessage);

    // Tell Android we are ready
    if (window.AndroidOverlay && typeof window.AndroidOverlay.onReactReady === 'function') {
      window.AndroidOverlay.onReactReady();
    } else {
      // Dev mode fallback
      setProfile(INITIAL_PROFILES[0]);
    }
    
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('message', handleMessage);
    };
  }, []);

  if (!profile) return null;

  // We are in play mode inside the service - background transparent
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
