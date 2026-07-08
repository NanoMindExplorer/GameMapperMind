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
import { Shield, Activity, Compass, Cpu, Layers, ShieldAlert, Heart, AlertTriangle, Gamepad2 } from 'lucide-react';
import { KeepAwake } from '@capacitor-community/keep-awake';
import { ScreenOrientation } from '@capacitor/screen-orientation';
import { useShizuku } from './hooks/useShizuku';
import { useGamepadLoop } from './hooks/useGamepadLoop';
import { useInputInjector } from './hooks/useInputInjector';
import TouchInjection from './plugins/TouchInjection';

// === PHASE 1: Onboarding Wizard ===
import { OnboardingWizard } from './components/OnboardingWizard';

export default function App() {
  const { checkShizukuStatus, stopDaemon, startDaemon } = useShizuku();
  const { startOverlay, stopOverlay } = useInputInjector();

  const [shizukuState, setShizukuState] = React.useState<ShizukuState>({
    status: 'DISCONNECTED',
    daemonRunning: false,
    daemonVersion: '',
    logLines: ["[INFO] Application started."]
  });

  const [profiles, setProfiles] = React.useState<GamepadProfile[]>(INITIAL_PROFILES);
  const [activeProfileId, setActiveProfileId] = React.useState('efootball');
  const [selectedMainView, setSelectedMainView] = React.useState<'shizuku' | 'overlay' | 'profile' | 'macro' | 'tester' | 'credits' | 'games'>('shizuku');
  const [isKilling, setIsKilling] = React.useState(false);

  const [recoveryDialogOpen, setRecoveryDialogOpen] = React.useState(false);
  const [recoveryFailedCount, setRecoveryFailedCount] = React.useState(0);
  const nextRetryTimeRef = React.useRef(0);
  const retryIntervals = [5000, 10000, 20000, 40000, 60000];

  const [socketIpcName, setSocketIpcName] = React.useState('@gamepad_mapper_ipc');
  const [inputPolling, setInputPolling] = React.useState(250);
  const [isEditingSettings, setIsEditingSettings] = React.useState(false);

  const [overlayMode, setOverlayMode] = React.useState<'canvas' | 'floating'>('canvas');
  const [macros, setMacros] = React.useState<GamepadMacro[]>(INITIAL_MACROS);

  // === PHASE 1: Onboarding Wizard State ===
  const [showOnboarding, setShowOnboarding] = React.useState(false);

  // Persistence
  React.useEffect(() => {
    import('@capacitor/preferences').then(({ Preferences }) => {
      Preferences.get({ key: 'nexion_profiles' }).then((res) => {
        if (res.value) {
          try {
            const parsed = JSON.parse(res.value);
            if (Array.isArray(parsed) && parsed.length > 0) setProfiles(parsed);
          } catch (e) { console.error('Failed to parse profiles', e); }
        }
      }).catch(e => console.warn('capacitor get profiles error', e));

      Preferences.get({ key: 'nexion_active_profile' }).then((res) => {
        if (res.value) setActiveProfileId(res.value);
      }).catch(e => console.warn('capacitor get active profile error', e));

      Preferences.get({ key: 'nexion_macros' }).then((res) => {
        if (res.value) {
          try {
            const parsed = JSON.parse(res.value);
            if (Array.isArray(parsed) && parsed.length > 0) setMacros(parsed);
          } catch (e) { console.error('Failed to parse macros', e); }
        }
      }).catch(e => console.warn('capacitor get macros error', e));

      Preferences.get({ key: 'nexion_settings' }).then((res) => {
        if (res.value) {
          try {
            const parsed = JSON.parse(res.value);
            if (parsed.socketIpcName) setSocketIpcName(parsed.socketIpcName);
            if (parsed.inputPolling) setInputPolling(parsed.inputPolling);
            if (parsed.overlayMode === 'floating' || parsed.overlayMode === 'canvas') {
              setOverlayMode(parsed.overlayMode);
            }
          } catch(e) {}
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

  const saveSettingsToStorage = async (socket: string, polling: number, mode: 'canvas' | 'floating' = overlayMode) => {
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_settings', value: JSON.stringify({ socketIpcName: socket, inputPolling: polling, overlayMode: mode }) });
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
      if (next.length === 0) next.push(INITIAL_PROFILES[0]);
      saveProfilesToStorage(next);
      if (profileId === activeProfileId) setActiveProfileId(next[0].id);
      return next;
    });
  };

  const handleProfileSelect = async (id: string) => {
    setActiveProfileId(id);
    try {
      const { Preferences } = await import('@capacitor/preferences');
      await Preferences.set({ key: 'nexion_active_profile', value: id });
    } catch(e) { console.warn('Failed to save active profile', e); }
  };

  const [overlayActive, setOverlayActive] = React.useState(false);
  const [toastMessage, setToastMessage] = React.useState<{text: string, type: 'success' | 'error'} | null>(null);

  const showToast = (text: string, type: 'success' | 'error') => {
    setToastMessage({text, type});
    setTimeout(() => setToastMessage(null), 3000);
  };

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
        await startOverlay(activeProfile, overlayMode);
        setOverlayActive(true);
        handleLogMessage(`SYSTEM: Native overlay activated (mode: ${overlayMode}).`);
        showToast('Overlay Service Started Successfully!', 'success');
      }
    } catch (e: any) {
      handleLogMessage(`ERROR: Cannot start Native Overlay. ${e.message || e}`);
      showToast(`Failed to start overlay: ${e.message || e}`, 'error');
    }
  };

  const handleOverlayModeChange = async (mode: 'canvas' | 'floating') => {
    setOverlayMode(mode);
    saveSettingsToStorage(socketIpcName, inputPolling, mode);
    if (overlayActive) {
      try {
        await stopOverlay();
        await startOverlay(activeProfile, mode);
        handleLogMessage(`SYSTEM: Overlay mode switched to "${mode}".`);
      } catch (e: any) {
        setOverlayActive(false);
        handleLogMessage(`ERROR: Failed to switch overlay mode. ${e.message || e}`);
      }
    }
  };

  const handleGlobalKillSwitch = async () => {
    setIsKilling(true);
    try {
      await new Promise(r => setTimeout(r, 600));
      window.dispatchEvent(new CustomEvent('emergency-kill'));
      if (overlayActive) {
        await stopOverlay();
        setOverlayActive(false);
      }
      await stopDaemon();
      setShizukuState(prev => ({ ...prev, status: 'DISCONNECTED', daemonRunning: false }));
      handleLogMessage('CRITICAL: Global emergency kill-switch initiated.');
    } catch (err) {
      console.error("Failed to trigger emergency kill-switch", err);
    } finally {
      setIsKilling(false);
    }
  };

  const shizukuStateRef = React.useRef(shizukuState);
  React.useEffect(() => { shizukuStateRef.current = shizukuState; }, [shizukuState]);

  const recoveryFailedCountRef = React.useRef(0);

  const fetchStatus = React.useCallback(async () => {
    try {
      const now = Date.now();
      if (now < nextRetryTimeRef.current) return;

      const nextState = await checkShizukuStatus(shizukuStateRef.current);
      setShizukuState(nextState);

      if (nextState.recoveryState && nextState.recoveryState !== 'DAEMON_ALIVE') {
        recoveryFailedCountRef.current += 1;
        setRecoveryFailedCount(recoveryFailedCountRef.current);
        const idx = Math.min(recoveryFailedCountRef.current - 1, retryIntervals.length - 1);
        nextRetryTimeRef.current = Date.now() + retryIntervals[idx];
        if (recoveryFailedCountRef.current >= 3) setRecoveryDialogOpen(true);
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

  React.useEffect(() => {
    fetchStatus();
    const interval = setInterval(fetchStatus, 20000);

    let appListener: any;
    import('@capacitor/app').then(({ App: CapacitorApp }) => {
      appListener = CapacitorApp.addListener('appStateChange', async (state) => {
        if (state.isActive) {
          await fetchStatus();
          try {
            const { granted } = await TouchInjection.checkPermission();
            if (granted) await startDaemon();
          } catch (e) {
            console.warn("Resume rebind failed:", e);
          }
        }
      });
    }).catch(console.warn);

    return () => {
      clearInterval(interval);
      if (appListener?.remove) appListener.remove();
    };
  }, []);

  const activeProfile = profiles.find(p => p.id === activeProfileId) || profiles[0];

  useGamepadLoop(
    activeProfile,
    shizukuState.status === 'CONNECTED_SHIZUKU' || shizukuState.status === 'CONNECTED_ADB',
    overlayActive
  );

  // === PHASE 1: Onboarding Wizard Logic ===
  React.useEffect(() => {
    const checkOnboarding = async () => {
      try {
        const { Preferences } = await import('@capacitor/preferences');
        const { value } = await Preferences.get({ key: 'onboardingCompleted' });
        if (!value) {
          setTimeout(() => setShowOnboarding(true), 700);
        }
      } catch (e) {
        console.warn('Onboarding check failed', e);
      }
    };
    checkOnboarding();
  }, []);

  const handleOnboardingComplete = () => {
    setShowOnboarding(false);
  };

  const handleLogMessage = React.useCallback((msg: string) => {
    setShizukuState(prev => {
      const newLine = `[${new Date().toLocaleTimeString('en-US', { hour12: false, hour: "numeric", minute: "numeric", second: "numeric" })}] ${msg}`;
      const newLines = [...prev.logLines, newLine];
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
    setShizukuState(prev => ({ ...prev, daemonRunning: false, status: 'DISCONNECTED' }));
    handleLogMessage('SYSTEM: Kill-switch engaged.');
  };

  return (
    <div className="min-h-screen bg-[#060608] text-slate-100 flex flex-col font-sans selection:bg-indigo-500 selection:text-white">
      <div className="absolute inset-x-0 top-0 h-[450px] bg-gradient-to-b from-indigo-950/15 via-transparent to-transparent pointer-events-none" />

      {/* Header */}
      <header className="border-b border-slate-900/80 bg-slate-950/40 backdrop-blur-md sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex justify-between items-center">
          <div className="flex items-center gap-3.5 group">
            <div className="relative flex items-center justify-center w-12 h-12 rounded-xl bg-slate-950 border-2 border-indigo-500/30 group-hover:border-pink-500/60 shadow-lg shadow-indigo-950/50 transition-all duration-300 overflow-hidden cursor-crosshair">
              <div className="absolute inset-0 opacity-[0.06] bg-[radial-gradient(#ffffff_1px,transparent_1px)] [background-size:16px_16px] pointer-events-none" />
              <img src="/icon.svg" alt="Gamepad Mind Logo" className="w-8 h-8 group-hover:scale-110 group-hover:rotate-[15deg] transition-all duration-500 relative z-10" />
              <div className="absolute top-1 left-1 w-2 h-2 border-t-2 border-l-2 border-indigo-500/60 rounded-tl" />
              <div className="absolute top-1 right-1 w-2 h-2 border-t-2 border-r-2 border-indigo-500/60 rounded-tr" />
              <div className="absolute bottom-1 left-1 w-2 h-2 border-b-2 border-l-2 border-indigo-500/60 rounded-bl" />
              <div className="absolute bottom-1 right-1 w-2 h-2 border-b-2 border-r-2 border-indigo-500/60 rounded-br" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-orbitron text-xs sm:text-sm font-black tracking-widest text-slate-100 uppercase group-hover:text-pink-400 transition-all duration-300">
                  GAMEPAD <span className="text-transparent bg-clip-text bg-gradient-to-r from-indigo-400 via-purple-400 to-pink-500 font-extrabold">MAPPER</span>
                </span>
                <span className="text-[8px] font-mono font-bold bg-pink-950/50 text-pink-400 border border-pink-900/60 px-1.5 py-0.5 rounded-md uppercase tracking-wider animate-pulse">
                  X-PRO AGENT
                </span>
              </div>
              <p className="text-[9px] uppercase font-mono text-slate-400 tracking-widest mt-0.5 group-hover:text-indigo-400 transition-colors">
                LOW-LEVEL TACTILE CORE
              </p>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <div className="hidden md:flex items-center gap-6 text-[10px] text-slate-400 font-mono pr-2 border-r border-slate-800">
              <div className="flex items-center gap-2">
                <span className={`w-1.5 h-1.5 rounded-full ${shizukuState.recoveryState === 'DAEMON_ALIVE' ? 'bg-green-500 animate-pulse' : 'bg-slate-600'}`}></span>
                <span className={shizukuState.recoveryState === 'DAEMON_ALIVE' ? 'text-green-400' : 'text-slate-500'}>
                  Daemon: {shizukuState.recoveryState === 'DAEMON_ALIVE' ? 'ALIVE' : shizukuState.recoveryState || 'OFFLINE'}
                </span>
              </div>
            </div>

            <button
              onClick={handleGlobalKillSwitch}
              disabled={isKilling}
              className="relative group px-3.5 py-1.5 text-xs font-bold font-mono uppercase bg-red-950/40 hover:bg-red-900/40 border border-red-500/50 hover:border-red-500 text-red-400 rounded-lg flex items-center gap-2"
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

      <main className="max-w-7xl mx-auto w-full px-4 sm:px-6 lg:px-8 py-8 flex-1 flex flex-col gap-6 relative z-10">
        {/* Navigation */}
        <div className="flex flex-wrap gap-2 border-b border-slate-900 pb-2">
          <button onClick={() => setSelectedMainView('shizuku')} className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${selectedMainView === 'shizuku' ? 'bg-slate-900 text-indigo-400 border border-slate-800' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'}`}>
            <Shield className="w-3.5 h-3.5" /> Orchestration Control
          </button>
          <button onClick={() => setSelectedMainView('profile')} className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${selectedMainView === 'profile' ? 'bg-slate-900 text-indigo-400 border border-slate-800' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'}`}>
            <Cpu className="w-3.5 h-3.5" /> Profile Manager
          </button>
          <button onClick={() => setSelectedMainView('overlay')} className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${selectedMainView === 'overlay' ? 'bg-slate-900 text-indigo-400 border border-slate-800' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'}`}>
            <Layers className="w-3.5 h-3.5" /> WYSIWYG Overlay Canvas
          </button>
          <button onClick={() => setSelectedMainView('macro')} className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${selectedMainView === 'macro' ? 'bg-slate-900 text-indigo-400 border border-slate-800' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'}`}>
            <Activity className="w-3.5 h-3.5" /> Tactile Playback Macros
          </button>
          <button onClick={() => setSelectedMainView('tester')} className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${selectedMainView === 'tester' ? 'bg-slate-900 text-indigo-400 border border-slate-800' : 'text-slate-400 hover:text-slate-200 hover:bg-slate-950/40'}`}>
            <Compass className="w-3.5 h-3.5" /> Sensor & Input Diagnostics
          </button>
          <button onClick={() => setSelectedMainView('games')} className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${selectedMainView === 'games' ? 'bg-slate-900 text-emerald-400 border border-slate-800' : 'text-slate-400 hover:text-emerald-300 hover:bg-slate-950/40'}`}>
            <Gamepad2 className="w-3.5 h-3.5" /> Installed Games
          </button>
          <button onClick={() => setSelectedMainView('credits')} className={`px-4 py-2 text-xs font-semibold rounded-lg flex items-center gap-2 transition-all ${selectedMainView === 'credits' ? 'bg-slate-900 text-pink-400 border border-slate-800' : 'text-slate-400 hover:text-pink-300 hover:bg-slate-950/40'}`}>
            <Heart className="w-3.5 h-3.5 text-pink-400" /> Socials & Credits
          </button>
        </div>

        <div className="flex-1 flex flex-col gap-6">
          {selectedMainView === 'shizuku' && <ShizukuPanel shizukuState={shizukuState} setShizukuState={setShizukuState} onLogMessage={handleLogMessage} />}
          {selectedMainView === 'profile' && <GameSelector profiles={profiles} activeProfileId={activeProfileId} onProfileSelect={handleProfileSelect} onUpdateProfile={handleUpdateProfile} onCreateProfile={handleCreateProfile} onDeleteProfile={handleDeleteProfile} onLogMessage={handleLogMessage} />}
          
          {selectedMainView === 'overlay' && (
            <>
              <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3 mb-3 p-3 bg-slate-900/60 border border-slate-800 rounded-lg">
                <div className="flex items-center gap-2 text-xs font-mono text-slate-400">
                  <span className="uppercase tracking-wider text-slate-500">Overlay Style:</span>
                  <div className="flex rounded-md overflow-hidden border border-slate-700">
                    <button onClick={() => handleOverlayModeChange('canvas')} className={`px-3 py-1.5 uppercase font-bold text-[10px] tracking-wide transition-colors ${overlayMode === 'canvas' ? 'bg-indigo-500 text-white' : 'bg-slate-900 text-slate-400 hover:text-slate-200'}`}>Canvas (WYSIWYG)</button>
                    <button onClick={() => handleOverlayModeChange('floating')} className={`px-3 py-1.5 uppercase font-bold text-[10px] tracking-wide transition-colors ${overlayMode === 'floating' ? 'bg-indigo-500 text-white' : 'bg-slate-900 text-slate-400 hover:text-slate-200'}`}>Floating (K2-style)</button>
                  </div>
                </div>
                <div className="flex-1" />
                <button onClick={handleToggleOverlay} className={`px-4 py-2 rounded-lg text-xs font-bold font-mono uppercase tracking-wide transition-all ${overlayActive ? 'bg-red-950/40 border border-red-500/50 text-red-400 hover:bg-red-900/40' : 'bg-emerald-950/40 border border-emerald-500/50 text-emerald-400 hover:bg-emerald-900/40'}`}>
                  {overlayActive ? 'Stop Overlay' : 'Start Overlay'}
                </button>
              </div>
              <OverlayWysiwyg activeProfile={activeProfile} onUpdateProfile={handleUpdateProfile} onLogMessage={handleLogMessage} activeKeys={activeKeys} activeAxes={activeAxes} />
            </>
          )}

          {selectedMainView === 'macro' && <MacroEngine macros={macros} onUpdateMacros={handleUpdateMacros} onLogMessage={handleLogMessage} />}
          {selectedMainView === 'tester' && <GamepadTester onLogMessage={handleLogMessage} />}
          {selectedMainView === 'games' && <InstalledGamesPanel onLogMessage={handleLogMessage} profiles={profiles} onProfileSelect={handleProfileSelect} onCreateProfile={handleCreateProfile} />}
          {selectedMainView === 'credits' && <CreditsPanel onLogMessage={handleLogMessage} />}
        </div>

        <section className="bg-slate-900/30 border border-slate-900/60 rounded-xl p-6 grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5"><Shield className="w-4 h-4 text-indigo-400" />1. PHYSICAL TOUCH INJECTION</h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">Locks directly onto raw kernel buffers utilizing unique device descriptor aliases.</p>
          </div>
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5"><Compass className="w-4 h-4 text-emerald-400" />2. SENSOR FUSION ENGINE</h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">High frequency input processing with low latency.</p>
          </div>
          <div className="space-y-1">
            <h4 className="text-xs font-bold text-slate-200 flex items-center gap-1.5"><Sparkles className="w-4 h-4 text-pink-400" />3. AIDL ISOLATION BOUNDS</h4>
            <p className="text-[11px] text-slate-400 leading-relaxed text-justify">Interlocks safely with the Shizuku Binder pipeline for background stability.</p>
          </div>
        </section>
      </main>

      <footer className="border-t border-slate-900 py-6 bg-slate-950 mt-auto text-center text-[10px] font-mono text-slate-500">
        <div className="max-w-7xl mx-auto px-4 flex flex-col items-center gap-3">
          <div className="flex flex-col sm:flex-row justify-between items-center w-full gap-3">
            <span>🎮 Gamepad Mapper Mind – Nexion Orchestrator Platform</span>
            <span className="text-indigo-400/80">Author: NanoMind Explorer</span>
            <span>© 2026 NanoMind Systems</span>
          </div>
          <div className="text-amber-500/80 font-semibold px-4 py-1.5 bg-amber-950/30 rounded border border-amber-900/50 mt-3 text-[11px] max-w-4xl text-center">
            DISCLAIMER: Gunakan hanya di mode yang mengizinkan controller. Ranked kompetitif tetap berisiko.
          </div>
        </div>
      </footer>

      {/* Recovery Dialog */}
      {recoveryDialogOpen && (
        <div className="fixed inset-0 z-[10000] bg-slate-950/80 backdrop-blur flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-indigo-500/50 rounded-xl max-w-lg w-full p-6 shadow-2xl">
            <h2 className="text-xl font-bold text-white mb-2 flex items-center gap-2">
              <AlertTriangle className="w-6 h-6 text-amber-500" /> Daemon Recovery
            </h2>
            <p className="text-slate-300 text-sm mb-4">Daemon gagal beberapa kali. Ikuti langkah recovery.</p>
            <div className="flex justify-end gap-3">
              <button onClick={() => setRecoveryDialogOpen(false)} className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white rounded text-sm font-semibold">Tutup</button>
              <button onClick={() => { setRecoveryDialogOpen(false); import('./plugins/TouchInjection').then(({ default: TouchInjection }) => TouchInjection.bindService().catch(()=>{})); }} className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded text-sm font-semibold">Force Re-Bind</button>
            </div>
          </div>
        </div>
      )}

      {/* Toast */}
      {toastMessage && (
        <div className="fixed bottom-10 left-1/2 transform -translate-x-1/2 z-50">
          <div className={`px-6 py-3 rounded-full flex items-center gap-3 shadow-2xl border ${toastMessage.type === 'success' ? 'bg-emerald-950/80 border-emerald-500/50 text-emerald-50' : 'bg-red-950/80 border-red-500/50 text-red-50'}`}>
            {toastMessage.type === 'success' ? <Compass className="w-5 h-5 text-emerald-400" /> : <ShieldAlert className="w-5 h-5 text-red-400" />}
            <span className="font-mono text-sm font-bold tracking-wide">{toastMessage.text}</span>
          </div>
        </div>
      )}

      {/* === PHASE 1: Onboarding Wizard === */}
      {showOnboarding && (
        <OnboardingWizard onComplete={handleOnboardingComplete} />
      )}
    </div>
  );
}
