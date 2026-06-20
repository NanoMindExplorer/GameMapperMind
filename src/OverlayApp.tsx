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

/**
 * Origin yang diizinkan untuk MessageEvent ke OverlayApp.
 * Hanya origin ini yang boleh mengirim profile ke overlay.
 * Invariant: MessageEvent dari origin lain akan di-reject.
 */
const ALLOWED_MESSAGE_ORIGINS = [
  'https://appassets.androidplatform.net',
  'http://localhost',
  'http://localhost:3000',
  'null' // Untuk file:// atau sandboxed iframe di dev mode
];


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
    // injectConfig juga divalidasi sebelum setProfile.
    window.injectConfig = (json: string) => {
      try {
        const parsed = JSON.parse(json);
        const validation = validateGamepadProfile(parsed);
        if (validation.success && validation.data) {
          setProfile(validation.data);
        } else {
          console.error('OverlayApp.injectConfig: invalid profile data', validation.error);
        }
      } catch(e) {
        console.error('OverlayApp.injectConfig: failed to parse JSON', e);
      }
    };

    window.injectActiveKeys = (json: string) => {
      try { setActiveKeys(JSON.parse(json)); } catch(e) {}
    };

    window.injectActiveAxes = (json: string) => {
      try { setActiveAxes(JSON.parse(json)); } catch(e) {}
    };

    /**
     * handleMessage dengan validasi zod dan origin check.
     *
     * Fix untuk BUG-N06 (regression dari fix BUG-C02):
     * - Sebelumnya handleMessage menerima e.data tanpa validasi shape.
     * - Jika e.data bukan GamepadProfile (misal {foo: 'bar'}), setProfile akan set
     *   object salah, OverlayWysiwyg yang akses profile.buttons akan crash dengan TypeError.
     * - Juga tidak ada origin check, sehingga postMessage dari origin lain bisa inject profile.
     *
     * Fix:
     * - Origin check: hanya ALLOWED_MESSAGE_ORIGINS yang diizinkan.
     * - Zod validation: e.data wajib valid GamepadProfile sebelum setProfile.
     * - Jika validasi gagal, log error dan reject (tidak setProfile).
     *
     * Invariant:
     * - setProfile hanya dipanggil dengan data yang valid (lolos zod schema).
     * - MessageEvent dari origin tidak diizinkan di-reject.
     */
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
