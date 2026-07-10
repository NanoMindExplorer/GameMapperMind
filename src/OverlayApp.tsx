import React, { useState, useEffect, useCallback } from 'react';
import OverlayWysiwyg from './components/OverlayWysiwyg';
import { GamepadProfile } from './types';
import { INITIAL_PROFILES } from './defaults';
import { useInputInjector } from './hooks/useInputInjector';
import TouchInjection from './plugins/TouchInjection';

interface ToastMessage {
  type: 'success' | 'error' | 'info';
  text: string;
}

interface OverlayAppProps {
  // Opsional: overlay window (FloatingOverlayService.java) adalah WebView
  // TERPISAH dari App.tsx utama -- tidak berbagi React state/instance.
  // Kalau prop ini tidak dikasih (kasus nyata di production), OverlayApp
  // memuat & menyimpan profile sendiri lewat Capacitor Preferences,
  // pakai key yang sama dengan App.tsx ('nexion_profiles' / 'nexion_active_profile').
  activeProfile?: GamepadProfile;
  onUpdateProfile?: (profile: GamepadProfile) => void;
  onLogMessage?: (msg: string) => void;
}

export default function OverlayApp({ activeProfile: externalProfile, onUpdateProfile: externalOnUpdate, onLogMessage }: OverlayAppProps) {
  const { startOverlay, stopOverlay } = useInputInjector();

  const [overlayActive, setOverlayActive] = useState(false);
  const [isMacroRecording, setIsMacroRecording] = useState(false);
  const [toastMessage, setToastMessage] = useState<ToastMessage | null>(null);

  // ==================== SELF-LOADED PROFILE (fix: overlay window terpisah dari App.tsx) ====================
  const [profiles, setProfiles] = useState<GamepadProfile[]>(INITIAL_PROFILES);
  const [activeProfileId, setActiveProfileId] = useState('efootball');
  const [profileLoaded, setProfileLoaded] = useState(false);

  // Profile yang di-push LANGSUNG oleh native lewat window.injectConfig(). Ini jembatan
  // yang sudah ada di FloatingOverlayService.java (onPageFinished / onReactReady /
  // pushConfigToActiveOverlay) tapi sebelumnya tidak pernah didefinisikan di sisi JS --
  // jadi selama ini no-op (di-guard `if(window.injectConfig)` di Java, makanya tidak crash,
  // cuma tidak pernah jalan). Native selalu punya data intent-time-fresh (persis yang aktif
  // saat user tekan "Start Overlay" di App.tsx), jadi ini lebih cepat & bebas race
  // dibanding baca dari Preferences yang bisa saja belum selesai ke-persist.
  const [nativeProfile, setNativeProfile] = useState<GamepadProfile | null>(null);

  useEffect(() => {
    if (externalProfile) {
      setProfileLoaded(true);
      return;
    }
    let cancelled = false;
    import('@capacitor/preferences').then(({ Preferences }) => {
      Promise.all([
        Preferences.get({ key: 'nexion_profiles' }),
        Preferences.get({ key: 'nexion_active_profile' }),
      ]).then(([profilesRes, activeRes]) => {
        if (cancelled) return;
        if (profilesRes.value) {
          try {
            const parsed = JSON.parse(profilesRes.value);
            if (Array.isArray(parsed) && parsed.length > 0) setProfiles(parsed);
          } catch (e) {
            console.error('OverlayApp: failed to parse nexion_profiles', e);
          }
        }
        if (activeRes.value) setActiveProfileId(activeRes.value);
        setProfileLoaded(true);
      }).catch((e) => {
        console.warn('OverlayApp: failed to load profile from Preferences', e);
        if (!cancelled) setProfileLoaded(true);
      });
    }).catch((e) => {
      console.warn('OverlayApp: capacitor preferences import error', e);
      if (!cancelled) setProfileLoaded(true);
    });
    return () => { cancelled = true; };
  }, [externalProfile]);

  useEffect(() => {
    if (externalProfile) return; // host controls the profile directly, native bridge not needed
    (window as any).injectConfig = (jsonStr: string) => {
      try {
        const profile = JSON.parse(jsonStr);
        if (profile && profile.id) {
          setNativeProfile(profile);
          setProfileLoaded(true);
        }
      } catch (e) {
        console.error('OverlayApp: failed to parse config from native injectConfig', e);
      }
    };
    // Tell native React has mounted and is ready to receive the config now — closes the
    // ordering race where onPageFinished fires (and calls injectConfig) before this
    // effect has had a chance to define window.injectConfig.
    try {
      (window as any).AndroidOverlay?.onReactReady?.();
    } catch (e) {
      console.warn('OverlayApp: AndroidOverlay.onReactReady failed', e);
    }
    return () => { delete (window as any).injectConfig; };
  }, [externalProfile]);

  const activeProfile = externalProfile ?? nativeProfile ?? profiles.find(p => p.id === activeProfileId) ?? profiles[0];

  const handleUpdateProfile = useCallback((updated: GamepadProfile) => {
    if (externalOnUpdate) {
      externalOnUpdate(updated);
      return;
    }
    // Keep whichever source is currently driving `activeProfile` in sync immediately
    // (no flicker back to a stale value), then persist to Preferences as usual.
    setNativeProfile(prev => (prev && prev.id === updated.id ? updated : prev));
    setProfiles(prev => {
      const next = prev.map(p => (p.id === updated.id ? updated : p));
      import('@capacitor/preferences').then(({ Preferences }) => {
        Preferences.set({ key: 'nexion_profiles', value: JSON.stringify(next) }).catch((e) =>
          console.warn('OverlayApp: failed to persist profile update', e)
        );
      }).catch(() => {});
      return next;
    });
  }, [externalOnUpdate]);

  const showToast = (type: ToastMessage['type'], text: string) => {
    setToastMessage({ type, text });
    setTimeout(() => setToastMessage(null), 3200);
    if (onLogMessage) onLogMessage(text);
  };

  // ==================== OVERLAY ====================
  const handleStartOverlay = async () => {
    if (!activeProfile) return;
    try {
      const success = await startOverlay(activeProfile, 'canvas');
      if (success) {
        setOverlayActive(true);
        showToast('success', 'Overlay started');
      } else {
        showToast('error', 'Failed to start overlay');
      }
    } catch (error: any) {
      showToast('error', `Overlay error: ${error?.message || error}`);
    }
  };

  const handleStopOverlay = async () => {
    try {
      const success = await stopOverlay();
      if (success) {
        setOverlayActive(false);
        showToast('success', 'Overlay stopped');
      }
    } catch (error: any) {
      showToast('error', `Stop overlay error: ${error?.message || error}`);
    }
  };

  // ==================== MACRO RECORDING ====================
  const handleToggleMacroRecording = async () => {
    if (!activeProfile) {
      showToast('error', 'No active profile selected');
      return;
    }

    try {
      if (!isMacroRecording) {
        if ((TouchInjection as any).startMacroRecording) {
          await (TouchInjection as any).startMacroRecording(activeProfile.id);
        }
        setIsMacroRecording(true);
        showToast('info', 'Macro recording started');
      } else {
        if ((TouchInjection as any).stopMacroRecording) {
          await (TouchInjection as any).stopMacroRecording();
        }
        setIsMacroRecording(false);
        showToast('success', 'Macro recording stopped');
      }
    } catch (error: any) {
      setIsMacroRecording(false);
      showToast('error', `Macro error: ${error?.message || error}`);
    }
  };

  if (!profileLoaded || !activeProfile) {
    return (
      <div className="flex items-center justify-center h-full bg-slate-950 text-slate-400 text-sm">
        Memuat profile...
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-slate-950 text-slate-200">
      {/* Top Control Bar */}
      <div className="flex items-center justify-between px-4 py-3 bg-slate-900 border-b border-slate-800">
        <div className="flex items-center gap-3">
          <span className="font-semibold">Overlay Mode</span>
          {overlayActive && <span className="px-2.5 py-0.5 text-xs bg-emerald-600 rounded-full">Active</span>}
          {isMacroRecording && <span className="px-2.5 py-0.5 text-xs bg-red-600 rounded-full animate-pulse">Recording Macro</span>}
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleToggleMacroRecording}
            className={`px-4 py-2 rounded-lg text-sm font-semibold transition-all ${isMacroRecording ? 'bg-red-600 hover:bg-red-500' : 'bg-pink-600 hover:bg-pink-500'}`}
          >
            {isMacroRecording ? 'Stop Recording' : 'Record Macro'}
          </button>

          {!overlayActive ? (
            <button onClick={handleStartOverlay} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 rounded-lg text-sm font-semibold">
              Start Overlay
            </button>
          ) : (
            <button onClick={handleStopOverlay} className="px-4 py-2 bg-rose-600 hover:bg-rose-500 rounded-lg text-sm font-semibold">
              Stop Overlay
            </button>
          )}
        </div>
      </div>

      {/* Main WYSIWYG */}
      <div className="flex-1 overflow-hidden">
        <OverlayWysiwyg
          activeProfile={activeProfile}
          onUpdateProfile={handleUpdateProfile}
          onLogMessage={(msg: string) => showToast('info', msg)}
          activeKeys={[]}
          activeAxes={{ lx: 0, ly: 0, rx: 0, ry: 0 }}
          isNativeOverlay={true}
        />
      </div>

      {/* Toast Notification */}
      {toastMessage && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50">
          <div className={`px-5 py-2.5 rounded-full text-sm font-medium shadow-xl ${
            toastMessage.type === 'success' ? 'bg-emerald-600 text-white' :
            toastMessage.type === 'error' ? 'bg-red-600 text-white' : 'bg-slate-700 text-slate-200'
          }`}>
            {toastMessage.text}
          </div>
        </div>
      )}
    </div>
  );
}
