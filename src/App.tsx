/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 * @author NanoMind Explorer
 */

import React from 'react';
import {
  GamepadProfile, GamepadMacro, ShizukuState
} from './types';
import { INITIAL_PROFILES, INITIAL_MACROS } from './defaults';
import ShizukuPanel from './components/ShizukuPanel';
import OverlayWysiwyg from './components/OverlayWysiwyg';
import MacroEngine from './components/MacroEngine';
import GamepadTester from './components/GamepadTester';
import GameSelector from './components/GameSelector';
import CreditsPanel from './components/CreditsPanel';
import InstalledGamesPanel from './components/InstalledGamesPanel';

import { Terminal, Shield, Settings, Activity, Compass, Cpu, HelpCircle, ChevronRight, Sparkles, BookOpen, Layers, Bot, ShieldAlert, Heart, AlertTriangle, Gamepad2 } from 'lucide-react';
import { KeepAwake } from '@capacitor-community/keep-awake';
import { ScreenOrientation } from '@capacitor/screen-orientation';
// Use public path directly for AppIcon instead of importing it

import { useShizuku } from './hooks/useShizuku';
import { useGamepadLoop } from './hooks/useGamepadLoop';
import { useInputInjector } from './hooks/useInputInjector';
import TouchInjection from './plugins/TouchInjection';

export default function App() {
  const { checkShizukuStatus, executeShizukuCommand, injectInput, stopDaemon, startDaemon } = useShizuku();
  const { startOverlay, stopOverlay } = useInputInjector();
  const [shizukuState, setShizukuState] = React.useState<ShizukuState>({
    status: 'DISCONNECTED',
    daemonRunning: false,
    daemonVersion: '',
    logLines: [
      "[INFO] Application started."
    ]
  });

  const [profiles, setProfiles] = React.useState<GamepadProfile[]>(INITIAL_PROFILES);
  const [activeProfileId, setActiveProfileId] = React.useState('efootball');
  const [selectedMainView, setSelectedMainView] = React.useState<'shizuku' | 'overlay' | 'profile' | 'macro' | 'tester' | 'credits' | 'games'>('shizuku');
  const [isKilling, setIsKilling] = React.useState(false);

  // Recovery Engine for Shizuku
  const [recoveryDialogOpen, setRecoveryDialogOpen] = React.useState(false);
  const [recoveryFailedCount, setRecoveryFailedCount] = React.useState(0);
  const nextRetryTimeRef = React.useRef(0);
  const retryIntervals = [5000, 10000, 20000, 40000, 60000];

  // Settings state
  const [socketIpcName, setSocketIpcName] = React.useState('@gamepad_mapper_ipc');
  const [inputPolling, setInputPolling] = React.useState(250);
  const [isEditingSettings, setIsEditingSettings] = React.useState(false);

  const [macros, setMacros] = React.useState<GamepadMacro[]>(INITIAL_MACROS);

  // Persistence
  React.useEffect(() => {
    import('@capacitor/preferences').then(({ Preferences }) => {
      Preferences.get({ key: 'nexion_profiles' }).then((res) => {
        if (res.value) {
          try {
            const parsed = JSON.parse(res.value);
            if (Array.isArray(parsed) && parsed.length > 0) {
              setProfiles(parsed);
            }
          } catch (e) { console.error('Failed to parse profiles', e); }
        }
      }).catch(e => console.warn('capacitor get profiles error', e));

      Preferences.get({ key: 'nexion_active_profile' }).then((res) => {
        if (res.value) {
          setActiveProfileId(res.value);
        }
      }).catch(e => console.warn('capacitor get active profile error', e));

      Preferences.get({ key: 'nexion_macros' }).then((res) => {
        if (res.value) {
          try {
            const parsed = JSON.parse(res.value);
            if (Array.isArray(parsed) && parsed.length > 0) {
              setMacros(parsed);
            }
          } catch (e) { console.error('Failed to parse macros', e); }
        }
      }).catch(e => console.warn('capacitor get macros error', e));

      Preferences.get({ key: 'nexion_settings' }).then((res) => {
        if (res.value) {
           try {
             const parsed = JSON.parse(res.value);
             if (parsed.socketIpcName) setSocketIpcName(parsed.socketIpcName);
             if (parsed.inputPolling) setInputPolling(parsed.inputPolling);
           } catch(e) { /* ignore */ }
        }
      }).catch(e => console.warn('capacitor get settings error', e));
    }).catch(e => console.warn('capacitor preferences import error', e));
  }, []);

  const saveProfilesToStorage = async (newProfiles: GamepadProfile[]) => {
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_profiles', value: JSON.stringify(newProfiles) });
    } catch(e) { console.warn('Failed to save profiles', e); }
  };

  const saveMacrosToStorage = async (newMacros: GamepadMacro[]) => {
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_macros', value: JSON.stringify(newMacros) });
    } catch(e) { console.warn('Failed to save macros', e); }
  };

  const [activeKeys, setActiveKeys] = React.useState<string[]>([]);
  const [activeAxes, setActiveAxes] = React.useState<{lx: number, ly: number, rx: number, ry: number}>({lx: 0, ly: 0, rx: 0, ry: 0});

  React.useEffect(() => {
    const handleBtn = (e: Event) => {
      const data = (e as CustomEvent).detail;
      if (data.value === 1) {
        setActiveKeys(prev => prev.includes(data.buttonName) ? prev : [...prev, data.buttonName]);
      } else {
        setActiveKeys(prev => prev.filter(k => k !== data.buttonName));
      }
    };
    const handleAxis = (e: Event) => {
      const data = (e as CustomEvent).detail;
      // DRIFT-FIX: Apply radial deadzone to display axes so the canvas cap
      // doesn't drift when stick is at rest. Auto-center calibration in
      // GamepadListenerService should fix the root cause, but this is a
      // safety net for small residual drift.
      const DEADZONE = 0.08;
      const lx = data.axes[0] || 0;
      const ly = data.axes[1] || 0;
      const rx = data.axes[2] || 0;
      const ry = data.axes[3] || 0;
      const lMag = Math.sqrt(lx * lx + ly * ly);
      const rMag = Math.sqrt(rx * rx + ry * ry);
      const applyDz = (x: number, y: number, mag: number) => {
        if (mag <= DEADZONE) return { x: 0, y: 0 };
        const scale = Math.min(1, (mag - DEADZONE) / (1 - DEADZONE));
        return { x: (x / mag) * scale, y: (y / mag) * scale };
      };
      const l = applyDz(lx, ly, lMag);
      const r = applyDz(rx, ry, rMag);
      setActiveAxes({ lx: l.x, ly: l.y, rx: r.x, ry: r.y });
    };
    window.addEventListener('native-gamepad-button', handleBtn);
    window.addEventListener('native-gamepad-axis', handleAxis);
    return () => {
      window.removeEventListener('native-gamepad-button', handleBtn);
      window.removeEventListener('native-gamepad-axis', handleAxis);
    };
  }, []);

  const saveSettingsToStorage = async (socket: string, polling: number) => {
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_settings', value: JSON.stringify({ socketIpcName: socket, inputPolling: polling }) });
    } catch(e) { console.warn('Failed to save settings', e); }
  };

  const handleUpdateMacros = (newMacros: GamepadMacro[]) => {
    setMacros(newMacros);
    saveMacrosToStorage(newMacros);
  };

  const handleUpdateProfile = (updatedProfile: GamepadProfile) => {
    setProfiles(prev => {
      const next = prev.map(p => p.id === updatedProfile.id ? updatedProfile : p);
      saveProfilesToStorage(next);
      return next;
    });
  };

  const handleCreateProfile = (profile: GamepadProfile) => {
    setProfiles(prev => {
      const next = [profile, ...prev];
      saveProfilesToStorage(next);
      return next;
    });
    setActiveProfileId(profile.id);
  };

  const handleDeleteProfile = (profileId: string) => {
    setProfiles(prev => {
      const next = prev.filter(p => p.id !== profileId);
      if (next.length === 0) {
         // Fallback if deleting the last profile
         next.push(INITIAL_PROFILES[0]);
      }
      saveProfilesToStorage(next);
      if (profileId === activeProfileId) {
        setActiveProfileId(next[0].id);
      }
      return next;
    });
  };

  const handleProfileSelect = async (id: string) => {
    setActiveProfileId(id);
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_active_profile', value: id });
    } catch(e) { console.warn('Failed to save active profile', e); }
    syncActiveProfileIdOnServer(id);
  };

  const [overlayActive, setOverlayActive] = React.useState(false);
  const [toastMessage, setToastMessage] = React.useState<{text: string, type: 'success' | 'error'} | null>(null);

  const showToast = (text: string, type: 'success' | 'error') => {
    setToastMessage({text, type});
    setTimeout(() => setToastMessage(null), 3000);
  };
  
  // Handle Keep-Awake and Screen Orientation based on overlay status
  // BUG-P6 FIX: Remove `profiles` and `activeProfileId` from deps — they don't affect
  // KeepAwake/ScreenOrientation logic. Only `overlayActive` and the resolved `orientation`
  // matter. Previously, changing profile (without toggling overlay) re-ran this effect,
  // calling KeepAwake.keepAwake() again unnecessarily.
  React.useEffect(() => {
    if (overlayActive) {
      KeepAwake.keepAwake().catch(console.warn);

      const currentProfile = profiles.find(p => p.id === activeProfileId) || profiles[0];
      if (currentProfile?.orientation === 'landscape') {
        ScreenOrientation.lock({ orientation: 'landscape' }).catch(console.warn);
      } else if (currentProfile?.orientation === 'portrait') {
        ScreenOrientation.lock({ orientation: 'portrait' }).catch(console.warn);
      }
    } else {
      KeepAwake.allowSleep().catch(console.warn);
      ScreenOrientation.unlock().catch(console.warn);
    }

    // Cleanup if unmounted while active
    return () => {
      KeepAwake.allowSleep().catch(console.warn);
      ScreenOrientation.unlock().catch(console.warn);
    };
  }, [overlayActive, activeProfileId]);

  const handleToggleOverlay = async () => {
    try {
      if (overlayActive) {
        await stopOverlay();
        setOverlayActive(false);
        handleLogMessage('SYSTEM: Native floating overlay deactivated.');
        showToast('Overlay Deactivated', 'success');
      } else {
        await startOverlay(activeProfile);
        setOverlayActive(true);
        handleLogMessage('SYSTEM: Native floating overlay activated. You can now minimize the app.');
        showToast('Overlay Service Started Successfully!', 'success');
      }
    } catch (e: any) {
      handleLogMessage(`ERROR: Cannot start Native Overlay. ${e.message || e}`);
      showToast(`Failed to start overlay: ${e.message || e}`, 'error');
    }
  };

  const handleGlobalKillSwitch = async () => {
    setIsKilling(true);
    try {
      // Simulate hardware interrupt sequence
      await new Promise(r => setTimeout(r, 600));
      window.dispatchEvent(new CustomEvent('emergency-kill'));
      if (overlayActive) {
        await stopOverlay();
        setOverlayActive(false);
      }
      await stopDaemon();
      setShizukuState(prev => ({
        ...prev,
        status: 'DISCONNECTED',
        daemonRunning: false
      }));
      handleLogMessage('CRITICAL: Global emergency kill-switch initiated. All services, Daemon, and Overlay terminated.');
    } catch (err) {
      console.error("Failed to trigger emergency kill-switch", err);
    } finally {
      setIsKilling(false);
    }
  };

  const shizukuStateRef = React.useRef(shizukuState);
  React.useEffect(() => {
    shizukuStateRef.current = shizukuState;
  }, [shizukuState]);

  const recoveryFailedCountRef = React.useRef(0);

  // Query real simulation logs and stats from server, override with Native plugin state if on device
  const fetchStatus = React.useCallback(async () => {
    try {
      const now = Date.now();
      if (now < nextRetryTimeRef.current) return; // Wait for backoff

      const nextState = await checkShizukuStatus(shizukuStateRef.current);
      setShizukuState(nextState);

      if (nextState.recoveryState && nextState.recoveryState !== 'DAEMON_ALIVE') {
        recoveryFailedCountRef.current += 1;
        setRecoveryFailedCount(recoveryFailedCountRef.current);
        const idx = Math.min(recoveryFailedCountRef.current - 1, retryIntervals.length - 1);
        nextRetryTimeRef.current = Date.now() + retryIntervals[idx];

        if (recoveryFailedCountRef.current >= 3) {
          setRecoveryDialogOpen(true);
        }
      } else if (nextState.recoveryState === 'DAEMON_ALIVE') {
        recoveryFailedCountRef.current = 0;
        setRecoveryFailedCount(0);
        nextRetryTimeRef.current = 0;
        setRecoveryDialogOpen(false);
      }
    } catch (err) {
      console.error('Failed to sync native state', err);
    }
  }, [checkShizukuStatus]);

  const syncActiveProfileIdOnServer = (id: string) => {
    // Local processing only via Capacitor
  };

  React.useEffect(() => {
    fetchStatus();
    // SHIZUKU-PERSIST FIX: Reduced polling from 5s to 20s.
    // 5s polling was too aggressive — every poll calls Shizuku.pingBinder() +
    // checkSelfPermission() which can trigger binder re-evaluation and put
    // pressure on the Shizuku process. 20s is enough for UI status updates
    // without causing binder stress.
    const interval = setInterval(fetchStatus, 20000);
    
    let appListener: any;
    import('@capacitor/app').then(({ App: CapacitorApp }) => {
      appListener = CapacitorApp.addListener('appStateChange', async (state) => {
        if (state.isActive) {
           // SHIZUKU-PERSIST FIX: On app resume, fetch status AND do a one-shot rebind
           // if permission is granted but touchService is dead. This is NOT churn —
           // it only fires once per resume (guarded by isBindingRef in useShizuku).
           // Previously, polling was read-only which meant if binding died while
           // backgrounded, user had to manually tap "Start Daemon" again.
           await fetchStatus();
           // After fetchStatus updates state, check if we need to rebind.
           // The rebind logic is in useShizuku.checkShizukuStatus — but that's
           // read-only now. So we call bindAndStart directly if service is dead.
           // This is safe because isBindingRef prevents concurrent binds.
           try {
             const { granted, touchServiceAlive } = await TouchInjection.checkPermission();
             if (granted && !touchServiceAlive) {
               // One-shot rebind on resume — prevents "app disappeared from Shizuku"
               // after backgrounding. The isBindingRef guard in useShizuku prevents churn.
               await startDaemon();
             }
           } catch (e) {
             console.warn("Resume rebind check failed:", e);
           }
        }
      });
    }).catch(console.warn);

    return () => {
      clearInterval(interval);
      if (appListener) {
         if (typeof appListener.then === 'function') {
             appListener.then((l: any) => l && l.remove && l.remove());
         } else if (appListener.remove) {
             appListener.remove();
         }
      }
    };
  
  }, []);

  const activeProfile = profiles.find(p => p.id === activeProfileId) || profiles[0];

  useGamepadLoop(
    activeProfile, 
    shizukuState.status === 'CONNECTED_SHIZUKU' || shizukuState.status === 'CONNECTED_ADB',
    overlayActive
  );

  const handleLogMessage = React.useCallback((msg: string) => {
    setShizukuState(prev => {
      const newLine = `[${new Date().toLocaleTimeString('en-US', { hour12: false, hour: "numeric", minute: "numeric", second: "numeric" })}] ${msg}`;
      const newLines = [...prev.logLines, newLine];
      // BUG-P12 FIX: Use while loop to handle case where multiple logs are added in batch
      // (e.g., macro engine logs 10 actions rapidly). Previously, only 1 shift per call,
      // so array could grow beyond 50 if many logs arrived in same render cycle.
      while (newLines.length > 50) newLines.shift();
      return { ...prev, logLines: newLines };
    });
  }, []);

  const handleKillSwitch = async () => {
    if (overlayActive) {
      await stopOverlay();
      setOverlayActive(false);
    }
    await stopDaemon();
    setShizukuState(prev => ({
        ...prev,
        daemonRunning: false,
        status: 'DISCONNECTED'
    }));
    handleLogMessage('SYSTEM: Kill-switch engaged. All services and injections terminated.');
  };

  // BUG-R5 FIX: Overlay detection is handled by main.tsx -> OverlayApp.tsx
  // Do NOT duplicate overlay rendering in App.tsx — causes conflict.
  // main.tsx checks window.location.search for 'overlay=true' and renders OverlayApp directly.

  return (
    <div className="min-h-screen bg-[#060608] text-slate-100 flex flex-col font-sans selection:bg-indigo-500 selection:text-white">
      {/* Visual background atmospheric elements */}
      <div className="absolute inset-x-0 top-0 h-[450px] bg-gradient-to-b from-indigo-950/15 via-transparent to-transparent pointer-events-none" />

      {/* Corporate Brand Header Section */}
      <header className="border-b border-slate-900/80 bg-slate-950/40 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex justify-between items-center">
          <div className="flex items-center gap-3.5 group">
            {/* High-fidelity gaming controller & crosshair cyber badge */}
            <div className="relative flex items-center justify-center w-12 h-12 rounded-xl bg-slate-950 border-2 border-indigo-500/30 group-hover:border-pink-500/60 shadow-lg shadow-indigo-950/50 transition-all duration-300 overflow-hidden cursor-crosshair">
              {/* Futuristic honeycomb layout behind */}
              <div className="absolute inset-0 opacity-[0.06] bg-[radial-gradient(#ffffff_1px,transparent_1px)] [background-size:16px_16px] pointer-events-none" />
              
              {/* Real Game Controller + Crosshair Laser sight Vector */}
              <img src="/icon.svg" alt="Gamepad Mind Logo" className="w-8 h-8 group-hover:scale-110 group-hover:rotate-[15deg] transition-all duration-500 relative z-10" />

              
              {/* Outer corner cyber brackets detailing */}
              <div className="absolute top-1 left-1 w-2 h-2 border-t-2 border-l-2 border-indigo-500/60 rounded-tl" />
              <div className="absolute top-1 right-1 w-2 h-2 border-t-2 border-r-2 border-indigo-500/60 rounded-tr" />
              <div className="absolute bottom-1 left-1 w-2 h-2 border-b-2 border-l-2 border-indigo-500/60 rounded-bl" />
              <div className="absolute bottom-1 right-1 w-2 h-2 border-b-2 border-r-2 border-indigo-500/60 rounded-br" />

              {/* Futuristic neon scanline aura overlay */}
              <div className="absolute inset-0 bg-gradient-to-r from-transparent via-indigo-500/10 to-transparent -translate-x-full group-hover:translate-x-full transition-transform duration-1000 pointer-events-none" />
              <div className="absolute -inset-1.5 rounded-xl bg-gradient-to-tr from-indigo-500 to-pink-500 opacity-0 group-hover:opacity-15 blur transition-opacity duration-300 pointer-events-none" />
            </div>

            {/* Typography brand styling */}
            <div>
              <div className="flex items-center gap-2">
                <span className="font-orbitron text-xs sm:text-sm font-black tracking-widest text-slate-100 uppercase group-hover:text-pink-400 group-hover:text-glow-pink transition-all duration-300">
                  GAMEPAD <span className="text-transparent bg-clip-text bg-gradient-to-r from-indigo-400 via-purple-400 to-pink-500 font-extrabold text-glow-indigo">MAPPER</span>
                </span>
                <span className="text-[8px] font-mono leading-none font-bold bg-pink-950/50 text-pink-400 border border-pink-900/60 px-1.5 py-0.5 rounded-md uppercase tracking-wider animate-pulse">
                  X-PRO AGENT
                </span>
              </div>
              <p className="text-[9px] uppercase font-mono text-slate-400 tracking-widest mt-0.5 group-hover:text-indigo-400 transition-colors">
                LOW-LEVEL TACTILE CORE
              </p>
            </div>
          </div>

          {/* Quick HUD State indications & Emergency Kill Switch */}
          <div className="flex items-center gap-4">
            {/* BUG-FIX: Removed GamepadStatusBadge (Web Gamepad API doesn't work in native
                Android WebView — events are intercepted by MainActivity.dispatchKeyEvent).
                Also removed the confusing "GP: CONNECTED/DISCONNECTED" text which used
                Shizuku status but was labeled as "GP" (gamepad), misleading users into
                thinking their physical gamepad was/wasn't connected.
                Now: single Shizuku daemon status indicator (green dot = daemon alive). */}
            <div className="hidden md:flex items-center gap-6 text-[10px] text-slate-400 font-mono pr-2 border-r border-slate-800">
              <div className="flex items-center gap-2" title={shizukuState.recoveryState === 'DAEMON_ALIVE' ? 'Shizuku daemon running and touch service bound' : 'Shizuku daemon not running — tap Start Daemon in Orchestration Control'}>
                <span className={`w-1.5 h-1.5 rounded-full ${shizukuState.recoveryState === 'DAEMON_ALIVE' ? 'bg-green-500 animate-pulse' : 'bg-slate-600'}`}></span>
                <span className={shizukuState.recoveryState === 'DAEMON_ALIVE' ? 'text-green-400' : 'text-slate-500'}>
                  Daemon: {shizukuState.recoveryState === 'DAEMON_ALIVE' ? 'ALIVE' : shizukuState.recoveryState || 'OFFLINE'}
                </span>
              </div>
            </div>
            
            <div className="hidden md:flex items-center gap-6 text-xs text-slate-400 font-mono pr-2">
              {isEditingSettings ? (
                <div className="flex items-center gap-4">
                  <div className="flex items-center gap-2">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
                    <input 
                      type="text" 
                      value={socketIpcName}
                      onChange={(e) => setSocketIpcName(e.target.value)}
                      className="bg-slate-900 border border-slate-800 rounded px-2 py-0.5 text-xs text-slate-200 outline-none w-40"
                    />
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="w-1.5 h-1.5 rounded-full bg-violet-400"></span>
                    <select
                      value={inputPolling}
                      onChange={(e) => setInputPolling(Number(e.target.value))}
                      className="bg-slate-900 border border-slate-800 rounded px-2 py-0.5 text-xs text-slate-200 outline-none"
                    >
                      <option value={125}>125Hz</option>
                      <option value={250}>250Hz</option>
                      <option value={500}>500Hz</option>
                      <option value={1000}>1000Hz</option>
                    </select>
                  </div>
                  <button 
                    onClick={() => {
                      setIsEditingSettings(false);
                      saveSettingsToStorage(socketIpcName, inputPolling);
                      handleLogMessage(`Configuration updated: Socket=${socketIpcName}, Polling=${inputPolling}Hz`);
                    }}
                    className="px-2 py-1 bg-indigo-500 hover:bg-indigo-400 text-white rounded text-[10px] uppercase font-bold"
                  >
                    Save
                  </button>
                </div>
              ) : (
                <div 
                  className="flex items-center gap-6 cursor-pointer hover:bg-slate-900 px-2 py-1 rounded transition-colors group/settings"
                  onClick={() => setIsEditingSettings(true)}
                  title="Click to configure settings"
                >
                  <div className="flex items-center gap-2 group-hover/settings:text-indigo-300">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-400"></span>
                    <span>Socket IPC: {socketIpcName}</span>
                  </div>
                  <div className="flex items-center gap-2 group-hover/settings:text-indigo-300">
                    <span className="w-1.5 h-1.5 rounded-full bg-violet-400 animate-pulse"></span>
                    <span>Input Polling: {inputPolling}Hz</span>
                  </div>
                </div>
              )}
            </div>

            <button
              onClick={handleGlobalKillSwitch}
              disabled={isKilling}
              id="global-kill-switch-btn"
              className="relative group px-3.5 py-1.5 text-xs font-bold font-mono uppercase bg-red-950/40 hover:bg-red-900/40 border border-red-500/50 hover:border-red-500 text-red-400 rounded-lg shadow-md shadow-red-500/5 hover:shadow-red-500/15 active:scale-[0.97] transition-all flex items-center gap-2"
              title="Stop all active inputs and purge macro buffers"
            >
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-500 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2 w-2 bg-red-500"></span>
              </span>
              <ShieldAlert className="w-3.5 h-3.5 text-red-400 group-hover:scale-110 transition-transform" />
              <span>KILL SWITCH</span>
            </button>
          </div>
        </div>
      </header>

      {/* Main Container */}
      <main className="max-w-7xl mx-auto w-full px-4 sm:px-6 lg:px-8 py-8 flex-1 flex flex-col gap-6 relative z-10">
        
        {/* Navigation Toolbar */}
        <div className="flex flex-wrap gap-2 border-b border-slate-900 pb-2">
          <button
            onClick={() => setSelectedMainView('shizuku')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'shizuku' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Shield className="w-3.5 h-3.5" />
            Orchestration Control
          </button>
          <button
            onClick={() => setSelectedMainView('profile')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'profile' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Cpu className="w-3.5 h-3.5" />
            Profile Manager
          </button>
          <button
            onClick={() => setSelectedMainView('overlay')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'overlay' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            WYSIWYG Overlay Canvas
          </button>
          <button
            onClick={() => setSelectedMainView('macro')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'macro' 
                ? 'bg-slate-900 text-indigo-400 border border-slate-800' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Activity className="w-3.5 h-3.5" />
            Tactile Playback Macros
          </button>
          <button
            onClick={() => setSelectedMainView('tester')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'tester'
                ? 'bg-slate-900 text-indigo-400 border border-slate-800'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'
            }`}
          >
            <Compass className="w-3.5 h-3.5" />
            Sensor & Input Diagnostics
          </button>
          <button
            onClick={() => setSelectedMainView('games')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'games'
                ? 'bg-slate-900 text-emerald-400 border border-slate-800'
                : 'text-slate-400 hover:text-emerald-300 hover:bg-slate-950/40'
            }`}
          >
            <Gamepad2 className="w-3.5 h-3.5" />
            Installed Games
          </button>
          <button
            onClick={() => setSelectedMainView('credits')}
            className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${
              selectedMainView === 'credits' 
                ? 'bg-slate-900 text-pink-400 border border-slate-800' 
                : 'text-slate-400 hover:text-pink-300 hover:bg-slate-950/40'
            }`}
          >
            <Heart className="w-3.5 h-3.5 text-pink-400" />
            Socials & Credits
          </button>
        </div>

        {/* Dynamic Inner views representing our monorepo features */}
        <div className="flex-1 flex flex-col gap-6">
          {selectedMainView === 'shizuku' && (
            <ShizukuPanel 
              shizukuState={shizukuState} 
              setShizukuState={setShizukuState} 
              onLogMessage={handleLogMessage} 
            />
          )}

          {selectedMainView === 'profile' && (
            <GameSelector
              profiles={profiles}
              activeProfileId={activeProfileId}
              onProfileSelect={handleProfileSelect}
              onUpdateProfile={handleUpdateProfile}
              onCreateProfile={handleCreateProfile}
              onDeleteProfile={handleDeleteProfile}
              onLogMessage={handleLogMessage}
            />
          )}

          {selectedMainView === 'overlay' && (
            <OverlayWysiwyg
              activeProfile={activeProfile}
              onUpdateProfile={handleUpdateProfile}
              onLogMessage={handleLogMessage}
              activeKeys={activeKeys}
              activeAxes={activeAxes}
            />
          )}

          {selectedMainView === 'macro' && (
            <MacroEngine
              macros={macros}
              onUpdateMacros={handleUpdateMacros}
              onLogMessage={handleLogMessage}
            />
          )}

          {selectedMainView === 'tester' && (
            <GamepadTester
              onLogMessage={handleLogMessage}
            />
          )}

          {selectedMainView === 'games' && (
            <InstalledGamesPanel
              onLogMessage={handleLogMessage}
              profiles={profiles}
              onProfileSelect={handleProfileSelect}
              onCreateProfile={handleCreateProfile}
            />
          )}

          {selectedMainView === 'credits' && (
            <CreditsPanel onLogMessage={handleLogMessage} />
          )}
        </div>

        {/* Informative corporate tech section */}
        <section className="bg-slate-900/30 border border-slate-900/60 rounded-xl p-6 grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5">
              <Shield className="w-4 h-4 text-indigo-400" />
              1. PHYSICAL TOUCH INJECTION
            </h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">
              Locks directly onto raw kernel buffers utilizing unique device descriptor aliases. Evades memory-scanners easily by varying coordinate distribution speeds.
            </p>
          </div>
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5">
              <Compass className="w-4 h-4 text-emerald-400" />
              2. SENSOR FUSION ENGINE
            </h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">
              Leverages high frequency Madgwick filters to translate gyroscopic coordinates into responsive multi-finger sweeps, matching competitive input lag standards (under 8ms).
            </p>
          </div>
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5">
              <Sparkles className="w-4 h-4 text-pink-400" />
              3. AIDL ISOLATION BOUNDS
            </h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">
              Interlocks safely with the Shizuku Binder pipeline, spinning the daemon process within background shelter boundaries so it safely survives foreground client destructions.
            </p>
          </div>
        </section>

      </main>

      {/* Corporate Technical Footer */}
      <footer className="border-t border-slate-900 py-6 bg-slate-950 mt-auto text-center text-[10px] font-mono text-slate-500">
        <div className="max-w-7xl mx-auto px-4 flex flex-col items-center gap-3">
          <div className="flex flex-col sm:flex-row justify-between items-center w-full gap-3">
             <span>🎮 Gamepad Mapper Mind – Nexion Orchestrator Platform</span>
             <span className="text-indigo-400/80">Author Signature: @author NanoMind Explorer</span>
             <span>© 2026 NanoMind Systems Inc.</span>
          </div>
          <div className="text-amber-500/80 font-semibold px-4 py-1.5 bg-amber-950/30 rounded border border-amber-900/50 mt-3 text-[11px] leading-relaxed max-w-4xl text-center">
            DISCLAIMER: Gunakan hanya di mode yang mengizinkan controller. Ranked kompetitif tetap berisiko flag input pihak ketiga. Uji di akun alt lebih dulu. Kami tidak bertanggung jawab atas akun yang di-banned.
          </div>
        </div>
      </footer>

      {recoveryDialogOpen && (
        <div className="fixed inset-0 z-[10000] bg-slate-950/80 backdrop-blur flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-indigo-500/50 rounded-xl max-w-lg w-full p-6 shadow-2xl relative">
            <h2 className="text-xl font-bold text-white mb-2 flex items-center gap-2">
              <AlertTriangle className="w-6 h-6 text-amber-500" />
              Daemon Recovery Tutorial
            </h2>
            <p className="text-slate-300 text-sm mb-4">
              It seems the background daemon has failed to stay alive multiple times (State: {shizukuState.recoveryState}). 
              Follow these steps to recover functionality:
            </p>
            
            <div className="space-y-4 mb-6">
              <div className="bg-slate-800 p-3 rounded border border-slate-700">
                <p className="font-bold text-indigo-300 text-sm mb-1">Step 1: Check Permissions</p>
                <p className="text-xs text-slate-400">Ensure Shizuku is authorized in Developer Settings. Disable and re-enable USB Debugging if necessary.</p>
                <div className="mt-2 text-[10px] bg-slate-950 p-2 rounded text-emerald-400 font-mono">Screenshot-Placeholder: USB_DEBUG.png</div>
              </div>
              <div className="bg-slate-800 p-3 rounded border border-slate-700">
                <p className="font-bold text-indigo-300 text-sm mb-1">Step 2: Start Daemon</p>
                <p className="text-xs text-slate-400">If the daemon crashed, open the Shizuku app and tap "Start". Then return back to Nexion.</p>
                <div className="mt-2 text-[10px] bg-slate-950 p-2 rounded text-emerald-400 font-mono">Screenshot-Placeholder: START_DAEMON.png</div>
              </div>
              <div className="bg-slate-800 p-3 rounded border border-slate-700">
                <p className="font-bold text-indigo-300 text-sm mb-1">Step 3: Force Re-bind</p>
                <p className="text-xs text-slate-400">Click the button below to forcefully recreate the binder IPC channels.</p>
              </div>
            </div>

            <div className="flex justify-end gap-3">
              <button 
                onClick={() => setRecoveryDialogOpen(false)}
                className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white rounded text-sm font-semibold transition"
              >
                Tutup
              </button>
              <button 
                onClick={() => {
                  setRecoveryDialogOpen(false);
                  setRecoveryFailedCount(0);
                  import('./plugins/TouchInjection').then(({ default: TouchInjection }) => {
                    TouchInjection.bindService().catch(()=>{});
                  });
                }}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded text-sm font-semibold transition"
              >
                Force Recovery Re-Bind
              </button>
            </div>
          </div>
        </div>
      )}

      {toastMessage && (
        <div className="fixed bottom-10 left-1/2 transform -translate-x-1/2 z-50 animate-in fade-in slide-in-from-bottom-4 duration-300">
          <div className={`px-6 py-3 rounded-full flex items-center gap-3 shadow-2xl backdrop-blur-md border ${toastMessage.type === 'success' ? 'bg-emerald-950/80 border-emerald-500/50 text-emerald-50' : 'bg-red-950/80 border-red-500/50 text-red-50'}`}>
            {toastMessage.type === 'success' ? <Compass className="w-5 h-5 text-emerald-400" /> : <ShieldAlert className="w-5 h-5 text-red-400" />}
            <span className="font-mono text-sm font-bold tracking-wide">{toastMessage.text}</span>
          </div>
        </div>
      )}

    </div>
  );
}
